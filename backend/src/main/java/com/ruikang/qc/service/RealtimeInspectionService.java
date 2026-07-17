package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import com.ruikang.qc.dto.inference.RealtimeClientEventRequest;
import com.ruikang.qc.dto.inference.RealtimeEventAcceptedResponse;
import com.ruikang.qc.dto.inference.RealtimeMetricsRequest;
import com.ruikang.qc.dto.inspection.RealtimeConfigRequest;
import com.ruikang.qc.dto.inspection.RealtimeEvidenceResponse;
import com.ruikang.qc.dto.inspection.RealtimeEventResponse;
import com.ruikang.qc.dto.inspection.RealtimeStatusResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RealtimeInspectionService {

    private static final DateTimeFormatter TASK_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final long PIECE_DEDUPE_WINDOW_MS = 20_000L;

    private final QcProperties qcProperties;
    private final CatalogService catalogService;
    private final JdbcTemplate jdbcTemplate;
    private final StoragePathService storagePathService;
    private final ClientInferenceResultValidator resultValidator;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<RealtimeStatusResponse> latestStatus = new AtomicReference<>();
    private final Deque<RealtimeEventResponse> recentEvents = new ArrayDeque<>();
    private final Deque<RealtimeEvidenceResponse> recentEvidence = new ArrayDeque<>();

    private volatile String currentTaskNo;
    private volatile Long currentTaskId;
    private volatile long totalFrameCount;
    private volatile long defectFrameCount;
    private volatile long totalDefectCount;
    private volatile int latestInferenceFps;
    private volatile int latestPreviewFps;
    private volatile int latestLatencyMs;
    private volatile String latestProviderStrategy = "BROWSER_PENDING";
    private volatile String lastPersistedFingerprint;
    private volatile long lastPersistedAt;
    private volatile int pieceSequence;

    public RealtimeInspectionService(
            QcProperties qcProperties,
            CatalogService catalogService,
            JdbcTemplate jdbcTemplate,
            StoragePathService storagePathService,
            ClientInferenceResultValidator resultValidator
    ) {
        this.qcProperties = qcProperties;
        this.catalogService = catalogService;
        this.jdbcTemplate = jdbcTemplate;
        this.storagePathService = storagePathService;
        this.resultValidator = resultValidator;
        this.latestStatus.set(buildStatus("STOPPED"));
    }

    @Transactional
    public synchronized RealtimeStatusResponse start() {
        if (running.get()) {
            return latestStatus.get();
        }
        currentTaskNo = "RTC-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
        Long cameraId = catalogService.getPrimaryCameraId();
        Long modelId = catalogService.getActiveModelId();
        jdbcTemplate.update("""
                INSERT INTO qc_task (
                    task_no, task_name, task_type, camera_id, model_id, task_status,
                    total_frame_count, defect_frame_count, total_defect_count, start_time, remark
                )
                VALUES (?, ?, 'realtime', ?, ?, 'RUNNING', 0, 0, 0, CURRENT_TIMESTAMP, ?)
                """,
                currentTaskNo,
                "实时质检任务-" + currentTaskNo,
                cameraId,
                modelId,
                "浏览器端 ONNX 推理实时质检"
        );
        currentTaskId = jdbcTemplate.queryForObject("SELECT id FROM qc_task WHERE task_no = ?", Long.class, currentTaskNo);
        totalFrameCount = 0;
        defectFrameCount = 0;
        totalDefectCount = 0;
        latestInferenceFps = 0;
        latestPreviewFps = 0;
        latestLatencyMs = 0;
        latestProviderStrategy = "BROWSER_PENDING";
        lastPersistedFingerprint = null;
        lastPersistedAt = 0;
        pieceSequence = 0;
        synchronized (recentEvents) {
            recentEvents.clear();
        }
        synchronized (recentEvidence) {
            recentEvidence.clear();
        }
        running.set(true);
        latestStatus.set(buildStatus("RUNNING"));
        pushEvent(new RealtimeEventResponse(
                "status",
                "实时质检已启动",
                "等待浏览器模型推理结果",
                "info",
                currentTaskNo,
                null,
                LocalDateTime.now()
        ));
        return latestStatus.get();
    }

    @Transactional
    public synchronized RealtimeStatusResponse stop() {
        running.set(false);
        if (currentTaskId != null) {
            jdbcTemplate.update("""
                    UPDATE qc_task
                    SET task_status = 'FINISHED', finish_time = CURRENT_TIMESTAMP,
                        total_frame_count = ?, defect_frame_count = ?, total_defect_count = ?
                    WHERE id = ?
                    """,
                    totalFrameCount,
                    defectFrameCount,
                    totalDefectCount,
                    currentTaskId
            );
        }
        latestStatus.set(buildStatus("STOPPED"));
        pushEvent(new RealtimeEventResponse(
                "status",
                "实时质检已停止",
                "浏览器端检测任务已结束",
                "warn",
                currentTaskNo,
                null,
                LocalDateTime.now()
        ));
        currentTaskId = null;
        currentTaskNo = null;
        lastPersistedFingerprint = null;
        lastPersistedAt = 0;
        pieceSequence = 0;
        return latestStatus.get();
    }

    public RealtimeStatusResponse status() {
        return latestStatus.get();
    }

    public List<RealtimeEventResponse> listEvents() {
        synchronized (recentEvents) {
            return new ArrayList<>(recentEvents);
        }
    }

    public List<RealtimeEvidenceResponse> listEvidence() {
        synchronized (recentEvidence) {
            return new ArrayList<>(recentEvidence);
        }
    }

    public void clearRealtimeHistory() {
        synchronized (recentEvents) {
            recentEvents.clear();
        }
        synchronized (recentEvidence) {
            recentEvidence.clear();
        }
    }

    public synchronized RealtimeStatusResponse updateMetrics(RealtimeMetricsRequest request) {
        requireRunning();
        if (request.defectFrameCount() > request.totalFrameCount()) {
            throw new IllegalArgumentException("缺陷帧数不能大于总帧数");
        }
        totalFrameCount = Math.max(totalFrameCount, request.totalFrameCount());
        defectFrameCount = Math.max(defectFrameCount, request.defectFrameCount());
        totalDefectCount = Math.max(totalDefectCount, request.totalDefectCount());
        latestPreviewFps = Math.min(request.previewFps(), 120);
        latestInferenceFps = Math.min(request.inferenceFps(), 120);
        latestLatencyMs = Math.min(request.latencyMs(), 60_000);
        latestProviderStrategy = request.providerStrategy().trim();
        latestStatus.set(buildStatus("RUNNING"));
        return latestStatus.get();
    }

    @Transactional
    public synchronized RealtimeEventAcceptedResponse acceptClientEvent(
            RealtimeClientEventRequest payload,
            List<MultipartFile> sourceFiles,
            List<MultipartFile> resultFiles
    ) {
        requireRunning();
        resultValidator.validateModelHash(payload.modelSha256());
        validateEvidenceFiles(sourceFiles, resultFiles);

        List<ClientInferenceResultValidator.ValidatedDetection> detections = resultValidator.validateDetections(
                        payload.detections(),
                        payload.imageWidth(),
                        payload.imageHeight()
                ).stream()
                .sorted(Comparator.comparingDouble(ClientInferenceResultValidator.ValidatedDetection::confidence).reversed())
                .toList();
        if (detections.isEmpty()) {
            throw new IllegalArgumentException("实时缺陷事件必须包含至少一个检测框");
        }

        String fingerprint = defectFingerprint(detections.get(0));
        long now = System.currentTimeMillis();
        if (isDuplicatePiece(fingerprint, now)) {
            pushEvent(new RealtimeEventResponse(
                    "duplicate-hit",
                    "同一面料重复命中",
                    "已识别为同一位置的重复事件，本次未重复保存",
                    "info",
                    currentTaskNo,
                    null,
                    LocalDateTime.now()
            ));
            return new RealtimeEventAcceptedResponse(false, true, null);
        }

        latestProviderStrategy = payload.providerStrategy().trim();
        latestLatencyMs = Math.min(
                payload.preprocessTimeMs() + payload.inferenceTimeMs() + payload.postprocessTimeMs(),
                60_000
        );

        String recordNo = "REC-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
        String pieceNo = "FAB-" + LocalDateTime.now().format(TASK_FORMAT) + "-" + String.format("%03d", ++pieceSequence);
        String resultSummary = "面料事件 " + pieceNo + " 连续命中，保存 " + sourceFiles.size()
                + " 组证据，检测到 " + detections.size() + " 个缺陷";
        Long cameraId = catalogService.getPrimaryCameraId();
        Long modelId = catalogService.getActiveModelId();

        jdbcTemplate.update("""
                INSERT INTO qc_record (
                    record_no, task_id, camera_id, model_id, inspect_type, source_type, result_judge,
                    confidence_score, defect_count, frame_snapshot_no, result_summary,
                    inference_time_ms, preprocess_time_ms, postprocess_time_ms, review_status, inspect_time
                )
                VALUES (?, ?, ?, ?, 'realtime', 'camera', 'FAIL', ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', CURRENT_TIMESTAMP)
                """,
                recordNo,
                currentTaskId,
                cameraId,
                modelId,
                detections.get(0).confidence(),
                detections.size(),
                pieceNo + ":" + fingerprint,
                resultSummary,
                payload.inferenceTimeMs(),
                payload.preprocessTimeMs(),
                payload.postprocessTimeMs()
        );
        Long recordId = jdbcTemplate.queryForObject("SELECT id FROM qc_record WHERE record_no = ?", Long.class, recordNo);

        saveEvidenceFiles(recordId, recordNo, payload, sourceFiles, resultFiles);
        persistDetections(recordId, payload.imageWidth(), payload.imageHeight(), detections);
        String exceptionNo = persistException(recordId, detections, resultSummary);

        lastPersistedFingerprint = fingerprint;
        lastPersistedAt = now;
        latestStatus.set(buildStatus("RUNNING"));
        pushEvent(new RealtimeEventResponse(
                "defect-event",
                "实时缺陷事件",
                "已生成面料事件 " + pieceNo + "、记录 " + recordNo + " 和异常单 " + exceptionNo,
                "danger",
                currentTaskNo,
                recordNo,
                LocalDateTime.now()
        ));
        return new RealtimeEventAcceptedResponse(true, false, recordNo);
    }

    public RealtimeStatusResponse updateConfig(RealtimeConfigRequest request) {
        updateConfigValue("conf_threshold", request.confidenceThreshold());
        updateConfigValue("iou_threshold", request.iouThreshold());
        updateConfigValue("continuous_hit_frames", request.continuousHitFrames());
        updateConfigValue("target_inference_fps", request.targetInferenceFps());
        updateConfigValue("evidence_image_count", request.evidenceImageCount());
        latestStatus.set(buildStatus(running.get() ? "RUNNING" : "STOPPED"));
        pushEvent(new RealtimeEventResponse(
                "config",
                "参数已更新",
                "浏览器推理参数已更新",
                "info",
                currentTaskNo,
                null,
                LocalDateTime.now()
        ));
        return latestStatus.get();
    }

    private void requireRunning() {
        if (!running.get() || currentTaskId == null || currentTaskNo == null) {
            throw new IllegalStateException("实时质检任务尚未启动");
        }
    }

    private void validateEvidenceFiles(List<MultipartFile> sourceFiles, List<MultipartFile> resultFiles) {
        if (sourceFiles == null || resultFiles == null || sourceFiles.isEmpty()
                || sourceFiles.size() != resultFiles.size()) {
            throw new IllegalArgumentException("实时证据原图与结果图数量不一致");
        }
        int maxEvidence = Math.max(1, intConfig("evidence_image_count", qcProperties.getInference().getEvidenceImageCount()));
        if (sourceFiles.size() > maxEvidence) {
            throw new IllegalArgumentException("实时证据数量超过系统配置");
        }
        sourceFiles.forEach(this::validateImageFile);
        resultFiles.forEach(this::validateImageFile);
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("证据图片不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("证据文件必须为图片");
        }
    }

    private void saveEvidenceFiles(
            Long recordId,
            String recordNo,
            RealtimeClientEventRequest payload,
            List<MultipartFile> sourceFiles,
            List<MultipartFile> resultFiles
    ) {
        String datePath = LocalDate.now().format(DATE_PATH_FORMAT);
        LocalDateTime createTime = LocalDateTime.now();
        for (int index = 0; index < sourceFiles.size(); index++) {
            int sequence = index + 1;
            MultipartFile sourceFile = sourceFiles.get(index);
            MultipartFile resultFile = resultFiles.get(index);
            String sourceExtension = imageExtension(sourceFile, "jpg");
            String resultExtension = imageExtension(resultFile, "jpg");
            String sourceName = recordNo + "-source-" + sequence + "." + sourceExtension;
            String resultName = recordNo + "-result-" + sequence + "." + resultExtension;
            String sourceRelative = "realtime/" + datePath + "/" + currentTaskNo + "/" + recordNo + "/source/" + sourceName;
            String resultRelative = "realtime/" + datePath + "/" + currentTaskNo + "/" + recordNo + "/result/" + resultName;
            Path sourcePath = storagePathService.resolveAndEnsureParent(sourceRelative);
            Path resultPath = storagePathService.resolveAndEnsureParent(resultRelative);
            try {
                sourceFile.transferTo(sourcePath);
                resultFile.transferTo(resultPath);
                int frameIndex = (int) Math.min(Integer.MAX_VALUE, Math.max(0, payload.frameIndex() - sourceFiles.size() + sequence));
                insertFileRecord(
                        recordId,
                        "source_image",
                        sourceName,
                        sourceRelative,
                        sourceExtension,
                        Files.size(sourcePath),
                        sourceFile.getContentType(),
                        payload.imageWidth(),
                        payload.imageHeight(),
                        frameIndex
                );
                String resultType = sequence == sourceFiles.size() ? "result_image" : "evidence_image";
                insertFileRecord(
                        recordId,
                        resultType,
                        resultName,
                        resultRelative,
                        resultExtension,
                        Files.size(resultPath),
                        resultFile.getContentType(),
                        payload.imageWidth(),
                        payload.imageHeight(),
                        frameIndex
                );
                addEvidence(new RealtimeEvidenceResponse(recordNo, resultType, resultRelative, createTime));
            } catch (IOException exception) {
                throw new IllegalStateException("保存实时质检证据失败", exception);
            }
        }
    }

    private void persistDetections(
            Long recordId,
            int imageWidth,
            int imageHeight,
            List<ClientInferenceResultValidator.ValidatedDetection> detections
    ) {
        for (ClientInferenceResultValidator.ValidatedDetection detection : detections) {
            jdbcTemplate.update("""
                    INSERT INTO qc_defect_result (
                        record_id, defect_type_id, defect_code, defect_name, confidence,
                        x1, y1, x2, y2, box_width, box_height, area, image_width, image_height
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    recordId,
                    detection.defectTypeId(),
                    detection.defectCode(),
                    detection.defectName(),
                    detection.confidence(),
                    detection.x1(),
                    detection.y1(),
                    detection.x2(),
                    detection.y2(),
                    detection.width(),
                    detection.height(),
                    detection.area(),
                    imageWidth,
                    imageHeight
            );
        }
    }

    private String persistException(
            Long recordId,
            List<ClientInferenceResultValidator.ValidatedDetection> detections,
            String resultSummary
    ) {
        String exceptionNo = "EX-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
        jdbcTemplate.update("""
                INSERT INTO qc_exception (
                    exception_no, record_id, task_id, exception_level, exception_title, exception_desc, status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN')
                """,
                exceptionNo,
                recordId,
                currentTaskId,
                detections.size() >= 2 ? "HIGH" : "MEDIUM",
                "实时质检发现 " + detections.get(0).defectName() + " 缺陷",
                resultSummary
        );
        return exceptionNo;
    }

    private void insertFileRecord(
            Long recordId,
            String fileType,
            String fileName,
            String relativePath,
            String extension,
            long fileSize,
            String mimeType,
            int width,
            int height,
            int frameIndex
    ) {
        jdbcTemplate.update("""
                INSERT INTO qc_file (
                    task_id, record_id, file_type, file_name, relative_path,
                    file_ext, file_size, mime_type, width, height, frame_index
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                currentTaskId,
                recordId,
                fileType,
                fileName,
                relativePath,
                extension,
                fileSize,
                mimeType,
                width,
                height,
                frameIndex
        );
    }

    private String defectFingerprint(ClientInferenceResultValidator.ValidatedDetection detection) {
        int centerXBucket = ((detection.x1() + detection.x2()) / 2) / 80;
        int centerYBucket = ((detection.y1() + detection.y2()) / 2) / 80;
        int widthBucket = Math.max(1, detection.width() / 80);
        int heightBucket = Math.max(1, detection.height() / 80);
        return detection.defectCode() + ":" + centerXBucket + ":" + centerYBucket + ":" + widthBucket + ":" + heightBucket;
    }

    private boolean isDuplicatePiece(String fingerprint, long now) {
        return fingerprint.equals(lastPersistedFingerprint) && now - lastPersistedAt <= PIECE_DEDUPE_WINDOW_MS;
    }

    private RealtimeStatusResponse buildStatus(String status) {
        return new RealtimeStatusResponse(
                "Webcam 0",
                status,
                latestPreviewFps,
                latestInferenceFps,
                latestLatencyMs,
                latestProviderStrategy,
                catalogService.getActiveModelPath(),
                doubleConfig("conf_threshold", qcProperties.getInference().getConfidenceThreshold()),
                doubleConfig("iou_threshold", qcProperties.getInference().getIouThreshold()),
                intConfig("continuous_hit_frames", qcProperties.getInference().getContinuousHitFrames()),
                intConfig("target_inference_fps", qcProperties.getInference().getTargetInferenceFps()),
                intConfig("evidence_image_count", qcProperties.getInference().getEvidenceImageCount())
        );
    }

    private void pushEvent(RealtimeEventResponse event) {
        synchronized (recentEvents) {
            while (recentEvents.size() >= 30) {
                recentEvents.removeFirst();
            }
            recentEvents.addLast(event);
        }
    }

    private void addEvidence(RealtimeEvidenceResponse evidence) {
        synchronized (recentEvidence) {
            while (recentEvidence.size() >= 12) {
                recentEvidence.removeFirst();
            }
            recentEvidence.addLast(evidence);
        }
    }

    private void updateConfigValue(String code, Object value) {
        if (value != null) {
            catalogService.updateConfig(code, String.valueOf(value));
        }
    }

    private int intConfig(String code, int defaultValue) {
        try {
            return Integer.parseInt(catalogService.getConfigValue(code, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double doubleConfig(String code, double defaultValue) {
        try {
            return Double.parseDouble(catalogService.getConfigValue(code, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String imageExtension(MultipartFile file, String fallback) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            return fallback;
        }
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT) : fallback;
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
