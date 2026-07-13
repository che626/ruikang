package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import com.ruikang.qc.dto.catalog.CameraItemResponse;
import com.ruikang.qc.dto.catalog.ConfigItemResponse;
import com.ruikang.qc.dto.catalog.DefectTypeItemResponse;
import com.ruikang.qc.dto.catalog.ModelItemResponse;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CatalogService {

    private static final String MODEL_SUFFIX = ".onnx";
    private static final Pattern YAML_NAME_ENTRY = Pattern.compile("^\\s*(\\d+)\\s*:\\s*['\"]?([^'\"#]+?)['\"]?\\s*(?:#.*)?$");
    private static final Map<String, String> DEFECT_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("oil_stain", "油渍"),
            Map.entry("ink_stain", "墨渍"),
            Map.entry("hole", "破洞"),
            Map.entry("broken_yarn", "断纱"),
            Map.entry("wrinkle", "褶皱"),
            Map.entry("foreign_matter", "异物")
    );
    private static final List<String> DEFECT_COLORS = List.of(
            "#facc15",
            "#22d3ee",
            "#fb923c",
            "#fb7185",
            "#38bdf8",
            "#34d399"
    );

    private final JdbcTemplate jdbcTemplate;
    private final QcProperties qcProperties;
    private final AtomicBoolean modelsSynced = new AtomicBoolean(false);
    private final Object modelSyncLock = new Object();

    public CatalogService(JdbcTemplate jdbcTemplate, QcProperties qcProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.qcProperties = qcProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncModelsOnStartup() {
        ensureModelsSyncedOnce();
    }

    public List<DefectDictionaryItem> getDefectTypes() {
        return jdbcTemplate.query("""
                        SELECT id, defect_code, defect_name, display_color
                        FROM defect_type
                        WHERE status = 1
                        ORDER BY sort_no ASC, id ASC
                        """,
                (resultSet, rowNum) -> new DefectDictionaryItem(
                        resultSet.getLong("id"),
                        resultSet.getString("defect_code"),
                        resultSet.getString("defect_name"),
                        resultSet.getString("display_color")
                ));
    }

    public Map<String, DefectDictionaryItem> getDefectDictionaryMap() {
        Map<String, DefectDictionaryItem> result = new LinkedHashMap<>();
        for (DefectDictionaryItem item : getDefectTypes()) {
            result.put(item.defectCode(), item);
        }
        return result;
    }

    public List<DefectTypeItemResponse> listDefectTypes() {
        return jdbcTemplate.query("""
                        SELECT id, defect_code, defect_name, defect_name_en, display_color, sort_no, status
                        FROM defect_type
                        WHERE status = 1
                        ORDER BY sort_no ASC, id ASC
                        """,
                (resultSet, rowNum) -> new DefectTypeItemResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("defect_code"),
                        resultSet.getString("defect_name"),
                        resultSet.getString("defect_name_en"),
                        resultSet.getString("display_color"),
                        resultSet.getInt("sort_no"),
                        resultSet.getInt("status")
                ));
    }

    public List<ModelItemResponse> listModels() {
        ensureModelsSyncedOnce();
        return jdbcTemplate.query("""
                        SELECT id, model_name, model_version, model_path, input_size, conf_threshold, iou_threshold,
                               prefer_gpu, fallback_cpu, is_active
                        FROM ai_model
                        ORDER BY is_active DESC, model_name ASC, id ASC
                        """,
                (resultSet, rowNum) -> new ModelItemResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("model_name"),
                        resultSet.getString("model_version"),
                        resultSet.getString("model_path"),
                        resultSet.getString("input_size"),
                        resultSet.getDouble("conf_threshold"),
                        resultSet.getDouble("iou_threshold"),
                        resultSet.getInt("prefer_gpu") == 1,
                        resultSet.getInt("fallback_cpu") == 1,
                        resultSet.getInt("is_active")
                ));
    }

    public List<CameraItemResponse> listCameras() {
        return jdbcTemplate.query("""
                        SELECT id, camera_code, camera_name, camera_type, status, location_desc
                        FROM qc_camera
                        ORDER BY id ASC
                        """,
                (resultSet, rowNum) -> new CameraItemResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("camera_code"),
                        resultSet.getString("camera_name"),
                        resultSet.getString("camera_type"),
                        resultSet.getInt("status"),
                        resultSet.getString("location_desc")
                ));
    }

    public List<ConfigItemResponse> listConfigs() {
        return jdbcTemplate.query("""
                        SELECT id, config_code, config_name, config_value, value_type, config_group, remark
                        FROM qc_config
                        ORDER BY config_group ASC, id ASC
                        """,
                (resultSet, rowNum) -> new ConfigItemResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("config_code"),
                        resultSet.getString("config_name"),
                        resultSet.getString("config_value"),
                        resultSet.getString("value_type"),
                        resultSet.getString("config_group"),
                        resultSet.getString("remark")
                ));
    }

    public void updateConfig(String configCode, String configValue) {
        jdbcTemplate.update("""
                UPDATE qc_config
                SET config_value = ?, update_time = CURRENT_TIMESTAMP
                WHERE config_code = ?
                """, configValue, configCode);
    }

    public void updateDefectTypeColor(String defectCode, String displayColor) {
        int updated = jdbcTemplate.update("""
                UPDATE defect_type
                SET display_color = ?, update_time = CURRENT_TIMESTAMP
                WHERE defect_code = ?
                """, displayColor, defectCode);
        if (updated == 0) {
            throw new IllegalArgumentException("Defect type does not exist: " + defectCode);
        }
    }

    public void setModelActive(Long modelId, boolean active) {
        ensureModelsSyncedOnce();
        if (!existsById("ai_model", modelId)) {
            throw new IllegalArgumentException("Model does not exist: " + modelId);
        }
        if (active) {
            jdbcTemplate.update("UPDATE ai_model SET is_active = 0, update_time = CURRENT_TIMESTAMP");
        }
        jdbcTemplate.update("""
                UPDATE ai_model
                SET is_active = ?, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, active ? 1 : 0, modelId);
    }

    public void activateCamera(String cameraCode) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qc_camera WHERE camera_code = ?",
                Integer.class,
                cameraCode
        );
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Camera config does not exist: " + cameraCode);
        }
        jdbcTemplate.update("UPDATE qc_camera SET status = 0, update_time = CURRENT_TIMESTAMP");
        jdbcTemplate.update("""
                UPDATE qc_camera
                SET status = 1, update_time = CURRENT_TIMESTAMP
                WHERE camera_code = ?
                """, cameraCode);
    }

    public Long getActiveModelId() {
        ensureModelsSyncedOnce();
        return jdbcTemplate.query("""
                        SELECT id
                        FROM ai_model
                        WHERE is_active = 1
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong("id") : null);
    }

    public String getActiveModelPath() {
        ensureModelsSyncedOnce();
        return jdbcTemplate.query("""
                        SELECT model_path
                        FROM ai_model
                        WHERE is_active = 1
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getString("model_path")
                        : qcProperties.getInference().getModelPath());
    }

    public Long getPrimaryCameraId() {
        return jdbcTemplate.query("""
                        SELECT id
                        FROM qc_camera
                        WHERE status = 1
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong("id") : null);
    }

    public String getConfigValue(String configCode, String defaultValue) {
        return jdbcTemplate.query("""
                        SELECT config_value
                        FROM qc_config
                        WHERE config_code = ?
                        """,
                resultSet -> resultSet.next() ? resultSet.getString("config_value") : defaultValue,
                configCode);
    }

    private void ensureModelsSyncedOnce() {
        if (modelsSynced.get()) {
            return;
        }
        synchronized (modelSyncLock) {
            if (modelsSynced.get()) {
                return;
            }
            syncModelsFromDirectory();
            modelsSynced.set(true);
        }
    }

    private void syncModelsFromDirectory() {
        Path modelDirectory = modelDirectory();
        try {
            Files.createDirectories(modelDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create model directory: " + modelDirectory, exception);
        }

        Set<String> discoveredPaths = discoverModelPaths();
        for (Path modelFile : modelFiles(modelDirectory)) {
            upsertModel(modelFile, toRuntimeModelPath(modelFile));
        }

        for (ModelDbRow row : modelRows()) {
            if (isManagedModel(row.modelPath(), modelDirectory) && !discoveredPaths.contains(row.modelPath())) {
                jdbcTemplate.update("DELETE FROM ai_model WHERE id = ?", row.id());
            }
        }

        ensureSingleActiveModel(discoveredPaths);
        syncDefectTypesFromActiveModelMetadata();
    }

    private Set<String> discoverModelPaths() {
        Set<String> discoveredPaths = new HashSet<>();
        for (Path modelFile : modelFiles(modelDirectory())) {
            discoveredPaths.add(toRuntimeModelPath(modelFile));
        }
        return discoveredPaths;
    }

    private List<Path> modelFiles(Path modelDirectory) {
        try (var stream = Files.list(modelDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(MODEL_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan model directory: " + modelDirectory, exception);
        }
    }

    private void upsertModel(Path modelFile, String modelPath) {
        String modelName = modelFile.getFileName().toString();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_model WHERE model_path = ?",
                Integer.class,
                modelPath
        );
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    UPDATE ai_model
                    SET model_name = ?, update_time = CURRENT_TIMESTAMP
                    WHERE model_path = ?
                    """, modelName, modelPath);
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO ai_model (
                    model_name, model_version, model_type, model_path, input_size,
                    conf_threshold, iou_threshold, prefer_gpu, fallback_cpu, is_active, remark
                )
                VALUES (?, 'auto', 'YOLO', ?, '640x640', ?, ?, ?, ?, 0, ?)
                """,
                modelName,
                modelPath,
                qcProperties.getInference().getConfidenceThreshold(),
                qcProperties.getInference().getIouThreshold(),
                qcProperties.getInference().isPreferGpu() ? 1 : 0,
                qcProperties.getInference().isFallbackToCpu() ? 1 : 0,
                "Auto-synced from model directory"
        );
    }

    private void ensureSingleActiveModel(Set<String> discoveredPaths) {
        if (discoveredPaths.isEmpty()) {
            jdbcTemplate.update("UPDATE ai_model SET is_active = 0, update_time = CURRENT_TIMESTAMP");
            return;
        }

        List<ModelDbRow> currentRows = modelRows().stream()
                .filter(row -> discoveredPaths.contains(row.modelPath()))
                .sorted(Comparator.comparing(ModelDbRow::id))
                .toList();
        if (currentRows.isEmpty()) {
            return;
        }

        List<ModelDbRow> activeRows = currentRows.stream()
                .filter(row -> row.isActive() == 1)
                .toList();
        if (activeRows.size() == 1) {
            return;
        }

        Long activeId = activeRows.isEmpty() ? currentRows.get(0).id() : activeRows.get(0).id();
        jdbcTemplate.update("UPDATE ai_model SET is_active = 0, update_time = CURRENT_TIMESTAMP");
        jdbcTemplate.update("UPDATE ai_model SET is_active = 1, update_time = CURRENT_TIMESTAMP WHERE id = ?", activeId);
    }

    private void syncDefectTypesFromActiveModelMetadata() {
        String activeModelPath = activeModelPathOrConfigured();
        Path modelPath = resolveModelPath(activeModelPath);
        Path yamlPath = resolveModelMetadataPath(modelPath);
        if (yamlPath == null) {
            return;
        }

        List<ModelClassItem> classItems = readModelClasses(yamlPath);
        if (classItems.isEmpty()) {
            return;
        }

        jdbcTemplate.update("UPDATE defect_type SET status = 0, update_time = CURRENT_TIMESTAMP");
        for (ModelClassItem item : classItems) {
            jdbcTemplate.update("""
                    INSERT INTO defect_type (
                        defect_code, defect_name, defect_name_en, defect_desc, display_color, sort_no, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                        defect_name = VALUES(defect_name),
                        defect_name_en = VALUES(defect_name_en),
                        defect_desc = VALUES(defect_desc),
                        display_color = VALUES(display_color),
                        sort_no = VALUES(sort_no),
                        status = 1,
                        update_time = CURRENT_TIMESTAMP
                    """,
                    item.code(),
                    displayNameFor(item.code(), item.rawName()),
                    item.code(),
                    displayNameFor(item.code(), item.rawName()) + "检测类别",
                    colorFor(item.index()),
                    item.index()
            );
        }
    }

    private String activeModelPathOrConfigured() {
        return jdbcTemplate.query("""
                        SELECT model_path
                        FROM ai_model
                        WHERE is_active = 1
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getString("model_path")
                        : qcProperties.getInference().getModelPath());
    }

    private Path resolveModelMetadataPath(Path modelPath) {
        Path modelDirectory = modelPath.getParent();
        if (modelDirectory == null) {
            return null;
        }

        String fileName = modelPath.getFileName().toString();
        String modelStem = fileName.toLowerCase(Locale.ROOT).endsWith(MODEL_SUFFIX)
                ? fileName.substring(0, fileName.length() - MODEL_SUFFIX.length())
                : fileName;
        List<Path> candidates = List.of(
                modelDirectory.resolve(modelStem + ".yaml"),
                modelDirectory.resolve(modelStem + ".yml"),
                modelDirectory.resolve("data.yaml"),
                modelDirectory.resolve("data.yml")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<ModelClassItem> readModelClasses(Path yamlPath) {
        List<ModelClassItem> result = new ArrayList<>();
        boolean inNames = false;
        try {
            for (String line : Files.readAllLines(yamlPath)) {
                String trimmed = line.trim();
                if (trimmed.equals("names:")) {
                    inNames = true;
                    continue;
                }
                if (!inNames || trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) {
                    break;
                }

                Matcher matcher = YAML_NAME_ENTRY.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                int index = Integer.parseInt(matcher.group(1));
                String rawName = matcher.group(2).trim();
                result.add(new ModelClassItem(index, normalizeDefectCode(rawName), rawName));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read model metadata: " + yamlPath, exception);
        }
        return result.stream()
                .sorted(Comparator.comparingInt(ModelClassItem::index))
                .toList();
    }

    private String normalizeDefectCode(String rawName) {
        String normalized = rawName.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Invalid model class name: " + rawName);
        }
        return normalized;
    }

    private String displayNameFor(String code, String rawName) {
        return DEFECT_DISPLAY_NAMES.getOrDefault(code, rawName);
    }

    private String colorFor(int index) {
        return DEFECT_COLORS.get(Math.floorMod(index, DEFECT_COLORS.size()));
    }

    private List<ModelDbRow> modelRows() {
        return jdbcTemplate.query("""
                        SELECT id, model_path, is_active
                        FROM ai_model
                        ORDER BY id ASC
                        """,
                (resultSet, rowNum) -> new ModelDbRow(
                        resultSet.getLong("id"),
                        resultSet.getString("model_path"),
                        resultSet.getInt("is_active")
                ));
    }

    private boolean isManagedModel(String modelPath, Path modelDirectory) {
        if (modelPath == null || !modelPath.toLowerCase().endsWith(MODEL_SUFFIX)) {
            return false;
        }
        Path parent = resolveModelPath(modelPath).getParent();
        return parent != null && parent.equals(modelDirectory.toAbsolutePath().normalize());
    }

    private Path modelDirectory() {
        Path configuredPath = resolveModelPath(qcProperties.getInference().getModelPath());
        Path parent = configuredPath.getParent();
        return parent == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : parent.toAbsolutePath().normalize();
    }

    private Path resolveModelPath(String modelPath) {
        Path path = Path.of(modelPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String toRuntimeModelPath(Path modelFile) {
        Path projectDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path normalizedFile = modelFile.toAbsolutePath().normalize();
        if (normalizedFile.startsWith(projectDirectory)) {
            return projectDirectory.relativize(normalizedFile).toString().replace('\\', '/');
        }
        return normalizedFile.toString();
    }

    private boolean existsById(String tableName, Long id) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?",
                Integer.class,
                id
        );
        return exists != null && exists > 0;
    }

    public record DefectDictionaryItem(
            Long id,
            String defectCode,
            String defectName,
            String displayColor
    ) {
    }

    private record ModelDbRow(Long id, String modelPath, int isActive) {
    }

    private record ModelClassItem(int index, String code, String rawName) {
    }
}
