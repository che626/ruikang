package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import com.ruikang.qc.dto.inference.BrowserInferenceManifestResponse;
import com.ruikang.qc.dto.inference.BrowserModelClassResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class BrowserInferenceModelService {

    private static final int DEFAULT_INPUT_SIZE = 640;

    private final CatalogService catalogService;
    private final QcProperties qcProperties;
    private final Object cacheLock = new Object();
    private volatile CachedModel cachedModel;

    public BrowserInferenceModelService(CatalogService catalogService, QcProperties qcProperties) {
        this.catalogService = catalogService;
        this.qcProperties = qcProperties;
    }

    public BrowserInferenceManifestResponse manifest() {
        CachedModel model = currentModel();
        List<CatalogService.DefectDictionaryItem> defectTypes = catalogService.getDefectTypes();
        List<BrowserModelClassResponse> classes = new java.util.ArrayList<>();
        for (int index = 0; index < defectTypes.size(); index++) {
            CatalogService.DefectDictionaryItem item = defectTypes.get(index);
            classes.add(new BrowserModelClassResponse(
                    index,
                    item.defectCode(),
                    item.defectName(),
                    item.displayColor()
            ));
        }
        int[] inputSize = parseInputSize(model.asset().inputSize());
        return new BrowserInferenceManifestResponse(
                model.asset().modelName(),
                normalizeVersion(model.asset().modelVersion(), model.sha256()),
                "/api/inference/model?sha256=" + model.sha256(),
                model.sha256(),
                model.fileSize(),
                "images",
                "output0",
                inputSize[0],
                inputSize[1],
                classes,
                doubleConfig("conf_threshold", qcProperties.getInference().getConfidenceThreshold()),
                doubleConfig("iou_threshold", qcProperties.getInference().getIouThreshold()),
                List.of("webgpu", "wasm")
        );
    }

    public ModelResource modelResource(String expectedSha256) {
        CachedModel model = currentModel();
        if (expectedSha256 == null || !model.sha256().equalsIgnoreCase(expectedSha256.trim())) {
            throw new IllegalArgumentException("模型版本已变化，请刷新模型清单后重试");
        }
        return new ModelResource(
                new FileSystemResource(model.asset().filePath()),
                model.asset().modelName(),
                model.sha256(),
                model.fileSize()
        );
    }

    public void assertCurrentHash(String sha256) {
        if (sha256 == null || !currentModel().sha256().equalsIgnoreCase(sha256.trim())) {
            throw new IllegalArgumentException("客户端模型版本与当前激活模型不一致");
        }
    }

    private CachedModel currentModel() {
        CatalogService.ActiveModelAsset asset = catalogService.getActiveModelAsset();
        Path path = asset.filePath().toAbsolutePath().normalize();
        try {
            long size = Files.size(path);
            long modifiedAt = Files.getLastModifiedTime(path).toMillis();
            CachedModel current = cachedModel;
            if (current != null && current.matches(path, size, modifiedAt)) {
                return current;
            }
            synchronized (cacheLock) {
                current = cachedModel;
                if (current != null && current.matches(path, size, modifiedAt)) {
                    return current;
                }
                CachedModel refreshed = new CachedModel(asset, size, modifiedAt, sha256(path));
                cachedModel = refreshed;
                return refreshed;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取当前激活模型", exception);
        }
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算模型摘要", exception);
        }
    }

    private int[] parseInputSize(String value) {
        if (value == null || value.isBlank()) {
            return new int[]{DEFAULT_INPUT_SIZE, DEFAULT_INPUT_SIZE};
        }
        String[] parts = value.toLowerCase().split("x");
        if (parts.length != 2) {
            return new int[]{DEFAULT_INPUT_SIZE, DEFAULT_INPUT_SIZE};
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            return new int[]{DEFAULT_INPUT_SIZE, DEFAULT_INPUT_SIZE};
        }
    }

    private String normalizeVersion(String version, String sha256) {
        return version == null || version.isBlank() || "auto".equalsIgnoreCase(version)
                ? sha256.substring(0, 12)
                : version;
    }

    private double doubleConfig(String code, double defaultValue) {
        try {
            return Double.parseDouble(catalogService.getConfigValue(code, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record CachedModel(
            CatalogService.ActiveModelAsset asset,
            long fileSize,
            long modifiedAt,
            String sha256
    ) {
        private boolean matches(Path path, long size, long modified) {
            return asset.filePath().toAbsolutePath().normalize().equals(path)
                    && fileSize == size
                    && modifiedAt == modified;
        }
    }

    public record ModelResource(Resource resource, String fileName, String sha256, long fileSize) {
    }
}
