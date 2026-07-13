package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import com.ruikang.qc.dto.inspection.DetectionResultResponse;
import com.ruikang.qc.dto.inspection.RealtimeConfigRequest;
import com.ruikang.qc.dto.inspection.RealtimeEvidenceResponse;
import com.ruikang.qc.dto.inspection.RealtimeEventResponse;
import com.ruikang.qc.dto.inspection.RealtimeStatusResponse;
import com.ruikang.qc.websocket.RealtimeStatusWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RealtimeInspectionService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeInspectionService.class);
    private static final DateTimeFormatter TASK_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final long DEFAULT_SAMPLE_INTERVAL_MS = 250L;
    private static final long PIECE_DEDUPE_WINDOW_MS = 20_000L;

    private final QcProperties qcProperties;
    private final CatalogService catalogService;
    private final JdbcTemplate jdbcTemplate;
    private final StoragePathService storagePathService;
    private final OnnxImageInspectionService onnxImageInspectionService;
    private final ObjectProvider<RealtimeStatusWebSocketHandler> realtimeStatusWebSocketHandlerProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicReference<RealtimeStatusResponse> latestStatus = new AtomicReference<>();
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();
    private final AtomicReference<RealtimeFrameResultResponse> latestFrameResult = new AtomicReference<>();
    private final Deque<RealtimeEventResponse> recentEvents = new ArrayDeque<>();
    private final Deque<RealtimeEvidenceResponse> recentEvidence = new ArrayDeque<>();
    private final Deque<FrameSnapshot> evidenceBuffer = new ArrayDeque<>();

    private volatile String currentTaskNo;
    private volatile Long currentTaskId;
    private volatile long totalFrameCount;
    private volatile long defectFrameCount;
    private volatile long totalDefectCount;
    private volatile int latestInferenceFps;
    private volatile int latestPreviewFps;
    private volatile int latestLatencyMs;
    private volatile String latestProviderStrategy = "UNINITIALIZED";
    private volatile long lastProcessedAt;
    private volatile long previewWindowStart;
    private volatile int previewWindowFrames;
    private volatile long inferenceWindowStart;
    private volatile int inferenceWindowFrames;
    private volatile int hitStreak;
    private volatile boolean streakPersisted;
    private volatile String lastPersistedFingerprint;
    private volatile long lastPersistedAt;
    private volatile int pieceSequence;

    public RealtimeInspectionService(
            QcProperties qcProperties,
            CatalogService catalogService,
            JdbcTemplate jdbcTemplate,
            StoragePathService storagePathService,
            OnnxImageInspectionService onnxImageInspectionService,
            ObjectProvider<RealtimeStatusWebSocketHandler> realtimeStatusWebSocketHandlerProvider
    ) {
        this.qcProperties = qcProperties;
        this.catalogService = catalogService;
        this.jdbcTemplate = jdbcTemplate;
        this.storagePathService = storagePathService;
        this.onnxImageInspectionService = onnxImageInspectionService;
        this.realtimeStatusWebSocketHandlerProvider = realtimeStatusWebSocketHandlerProvider;
        this.latestStatus.set(buildStatus("STOPPED"));
    }

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
                "浏览器推流实时质检"
        );
        currentTaskId = jdbcTemplate.queryForObject("SELECT id FROM qc_task WHERE task_no = ?", Long.class, currentTaskNo);
        totalFrameCount = 0;
        defectFrameCount = 0;
        totalDefectCount = 0;
        latestInferenceFps = 0;
        latestPreviewFps = 0;
        latestLatencyMs = 0;
        latestProviderStrategy = "UNINITIALIZED";
        latestFrame.set(null);
        latestFrameResult.set(null);
        recentEvents.clear();
        recentEvidence.clear();
        evidenceBuffer.clear();
        previewWindowStart = System.currentTimeMillis();
        inferenceWindowStart = System.currentTimeMillis();
        previewWindowFrames = 0;
        inferenceWindowFrames = 0;
        hitStreak = 0;
        streakPersisted = false;
        lastPersistedFingerprint = null;
        lastPersistedAt = 0;
        pieceSequence = 0;
        lastProcessedAt = 0;
        running.set(true);
        latestStatus.set(buildStatus("RUNNING"));
        pushEvent(new RealtimeEventResponse("status", "实时质检已启动", "等待浏览器视频帧输入", "info", currentTaskNo, null, LocalDateTime.now()));
        return latestStatus.get();
    }

    public synchronized RealtimeStatusResponse stop() {
        running.set(false);
        processing.set(false);
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
        pushEvent(new RealtimeEventResponse("status", "实时质检已停止", "浏览器视频流检测已停止", "warn", currentTaskNo, null, LocalDateTime.now()));
        currentTaskId = null;
        currentTaskNo = null;
        evidenceBuffer.clear();
        latestFrameResult.set(null);
        lastPersistedFingerprint = null;
        lastPersistedAt = 0;
        pieceSequence = 0;
        return latestStatus.get();
    }

    public RealtimeStatusResponse status() {
        return latestStatus.get();
    }

    public List<RealtimeEventResponse> listEvents() {
        return new ArrayList<>(recentEvents);
    }

    public List<RealtimeEvidenceResponse> listEvidence() {
        return new ArrayList<>(recentEvidence);
    }

    public void clearRealtimeHistory() {
        recentEvents.clear();
        recentEvidence.clear();
    }

    public byte[] latestFrame() {
        return latestFrame.get();
    }

    public RealtimeStatusResponse updateConfig(RealtimeConfigRequest request) {
        updateConfigValue("conf_threshold", request.confidenceThreshold());
        updateConfigValue("iou_threshold", request.iouThreshold());
        updateConfigValue("continuous_hit_frames", request.continuousHitFrames());
        updateConfigValue("target_inference_fps", request.targetInferenceFps());
        updateConfigValue("evidence_image_count", request.evidenceImageCount());
        latestStatus.set(buildStatus(running.get() ? "RUNNING" : "STOPPED"));
        pushEvent(new RealtimeEventResponse("config", "参数已更新", "实时质检参数已更新", "info", currentTaskNo, null, LocalDateTime.now()));
        return latestStatus.get();
    }

    public RealtimeFrameResultResponse processFrame(byte[] frameBytes) {
        if (!running.get()) {
            return RealtimeFrameResultResponse.idle(buildStatus("STOPPED"));
        }
        if (frameBytes == null || frameBytes.length == 0) {
            return RealtimeFrameResultResponse.idle(buildStatus("RUNNING"));
        }

        totalFrameCount++;
        touchPreviewFps();

        long now = System.currentTimeMillis();
        RealtimeFrameResultResponse cachedResult = latestFrameResult.get();
        if (cachedResult != null && now - lastProcessedAt < sampleIntervalMs()) {
            return cachedResult.withRenderMode("reuse");
        }

        if (!processing.compareAndSet(false, true)) {
            return cachedResult != null ? cachedResult.withRenderMode("reuse") : RealtimeFrameResultResponse.idle(buildStatus("RUNNING"));
        }

        try {
            BufferedImage sourceImage = readImage(frameBytes);
            InferenceModels.InferenceOutput output = onnxImageInspectionService.inspect(sourceImage);
            latestProviderStrategy = output.providerStrategy();
            latestLatencyMs = output.preprocessTimeMs() + output.inferenceTimeMs() + output.postprocessTimeMs();
            touchInferenceFps();
            latestFrame.set(toJpeg(output.resultImage()));

            List<DetectionResultResponse> detectionResponses = output.detections().stream()
                    .map(item -> new DetectionResultResponse(
                            item.defectCode(),
                            item.defectName(),
                            item.displayColor(),
                            item.confidence(),
                            item.x1(),
                            item.y1(),
                            item.x2(),
                            item.y2(),
                            item.width(),
                            item.height(),
                            item.area()
                    ))
                    .toList();

            boolean hasDefect = !output.detections().isEmpty();
            log.info("Frame #{}: {} detections found, judge={}, strategy={}, latency={}ms (pre={} infer={} post={})",
                    totalFrameCount, output.detections().size(),
                    hasDefect ? "FAIL" : "PASS",
                    output.providerStrategy(),
                    latestLatencyMs,
                    output.preprocessTimeMs(), output.inferenceTimeMs(), output.postprocessTimeMs());
            if (hasDefect) {
                defectFrameCount++;
                totalDefectCount += output.detections().size();
                hitStreak++;
                evidenceBuffer.addLast(new FrameSnapshot(sourceImage, output.resultImage(), output));
                trimEvidence();
                pushEvent(new RealtimeEventResponse(
                        "hit",
                        "缺陷命中",
                        "连续命中第 " + hitStreak + " 帧 / " + output.detections().get(0).defectName(),
                        "warn",
                        currentTaskNo,
                        null,
                        LocalDateTime.now()
                ));
                if (!streakPersisted && hitStreak >= intConfig("continuous_hit_frames", qcProperties.getInference().getContinuousHitFrames())) {
                    streakPersisted = true;
                    String fingerprint = defectFingerprint(output);
                    if (isDuplicatePiece(fingerprint, now)) {
                        pushEvent(new RealtimeEventResponse(
                                "duplicate-hit",
                                "同一面料重复命中",
                                "已识别为同一块面料的同一处瑕疵，本次不重复保存记录",
                                "info",
                                currentTaskNo,
                                null,
                                LocalDateTime.now()
                        ));
                    } else {
                        persistRealtimeEvent(new ArrayList<>(evidenceBuffer), output, fingerprint);
                        lastPersistedFingerprint = fingerprint;
                        lastPersistedAt = now;
                    }
                }
            } else {
                hitStreak = 0;
                streakPersisted = false;
                evidenceBuffer.clear();
            }

            latestStatus.set(buildStatus("RUNNING"));
            broadcastStatus(latestStatus.get());
            lastProcessedAt = now;

            RealtimeFrameResultResponse result = new RealtimeFrameResultResponse(
                    "frame-result",
                    "overlay",
                    latestStatus.get().runtimeStatus(),
                    latestStatus.get().providerStrategy(),
                    latestStatus.get().previewFps(),
                    latestStatus.get().inferenceFps(),
                    latestStatus.get().latencyMs(),
                    hasDefect ? "FAIL" : "PASS",
                    detectionResponses.isEmpty() ? null : detectionResponses.get(0).confidence(),
                    detectionResponses.isEmpty() ? null : detectionResponses.get(0).defectName(),
                    hasDefect ? "检测到 " + detectionResponses.size() + " 个缺陷" : "当前帧未检测到缺陷",
                    output.imageWidth(),
                    output.imageHeight(),
                    detectionResponses
            );
            latestFrameResult.set(result);
            return result;
        } catch (Exception exception) {
            latestStatus.set(buildStatus("ERROR"));
            pushEvent(new RealtimeEventResponse(
                    "error",
                    "实时线程异常",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    "danger",
                    currentTaskNo,
                    null,
                    LocalDateTime.now()
            ));
            return RealtimeFrameResultResponse.error(buildStatus("ERROR"), exception.getMessage());
        } finally {
            processing.set(false);
        }
    }

    private void persistRealtimeEvent(List<FrameSnapshot> snapshots, InferenceModels.InferenceOutput currentOutput, String fingerprint) {
        if (currentTaskId == null || currentTaskNo == null) {
            return;
        }

        String recordNo = "REC-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
        String pieceNo = "FAB-" + LocalDateTime.now().format(TASK_FORMAT) + "-" + String.format("%03d", ++pieceSequence);
        Long cameraId = catalogService.getPrimaryCameraId();
        Long modelId = catalogService.getActiveModelId();
        String resultSummary = "面料事件 " + pieceNo + " 实时质检连续命中 " + snapshots.size()
                + " 帧，检测到 " + currentOutput.detections().size() + " 个缺陷";
        Double topConfidence = currentOutput.detections().isEmpty() ? null : currentOutput.detections().get(0).confidence();

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
                topConfidence,
                currentOutput.detections().size(),
                pieceNo + ":" + fingerprint,
                resultSummary,
                currentOutput.inferenceTimeMs(),
                currentOutput.preprocessTimeMs(),
                currentOutput.postprocessTimeMs()
        );

        Long recordId = jdbcTemplate.queryForObject("SELECT id FROM qc_record WHERE record_no = ?", Long.class, recordNo);
        LocalDateTime now = LocalDateTime.now();
        String datePath = LocalDate.now().format(DATE_PATH_FORMAT);

        int index = 0;
        for (FrameSnapshot snapshot : snapshots) {
            index++;
            String sourceName = recordNo + "-source-" + index + ".png";
            String resultName = recordNo + "-result-" + index + ".png";
            String sourceRelative = "realtime/" + datePath + "/" + currentTaskNo + "/" + recordNo + "/source/" + sourceName;
            String resultRelative = "realtime/" + datePath + "/" + currentTaskNo + "/" + recordNo + "/result/" + resultName;
            writeImage(snapshot.sourceImage(), sourceRelative);
            writeImage(snapshot.resultImage(), resultRelative);
            insertFileRecord(recordId, "source_image", sourceName, sourceRelative, snapshot.sourceImage().getWidth(), snapshot.sourceImage().getHeight(), index);
            insertFileRecord(recordId, index == snapshots.size() ? "result_image" : "evidence_image", resultName, resultRelative, snapshot.resultImage().getWidth(), snapshot.resultImage().getHeight(), index);
            addEvidence(new RealtimeEvidenceResponse(recordNo, index == snapshots.size() ? "result_image" : "evidence_image", resultRelative, now));
        }

        for (InferenceModels.Detection detection : currentOutput.detections()) {
            Long defectTypeId = jdbcTemplate.query("""
                            SELECT id FROM defect_type WHERE defect_code = ?
                            """,
                    resultSet -> resultSet.next() ? resultSet.getLong("id") : null,
                    detection.defectCode());
            jdbcTemplate.update("""
                    INSERT INTO qc_defect_result (
                        record_id, defect_type_id, defect_code, defect_name, confidence,
                        x1, y1, x2, y2, box_width, box_height, area, image_width, image_height
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    recordId,
                    defectTypeId,
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
                    currentOutput.imageWidth(),
                    currentOutput.imageHeight()
            );
        }

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
                currentOutput.detections().size() >= 2 ? "HIGH" : "MEDIUM",
                "实时质检发现 " + currentOutput.detections().get(0).defectName() + " 缺陷",
                resultSummary
        );

        pushEvent(new RealtimeEventResponse(
                "defect-event",
                "实时缺陷事件",
                "已生成面料事件 " + pieceNo + "、记录 " + recordNo + " 和异常单 " + exceptionNo,
                "danger",
                currentTaskNo,
                recordNo,
                LocalDateTime.now()
        ));
    }

    private String defectFingerprint(InferenceModels.InferenceOutput output) {
        InferenceModels.Detection topDetection = output.detections().get(0);
        int centerXBucket = ((topDetection.x1() + topDetection.x2()) / 2) / 80;
        int centerYBucket = ((topDetection.y1() + topDetection.y2()) / 2) / 80;
        int widthBucket = Math.max(1, topDetection.width() / 80);
        int heightBucket = Math.max(1, topDetection.height() / 80);
        return topDetection.defectCode() + ":" + centerXBucket + ":" + centerYBucket + ":" + widthBucket + ":" + heightBucket;
    }

    private boolean isDuplicatePiece(String fingerprint, long now) {
        return fingerprint != null
                && fingerprint.equals(lastPersistedFingerprint)
                && now - lastPersistedAt <= PIECE_DEDUPE_WINDOW_MS;
    }

    private void insertFileRecord(Long recordId, String fileType, String fileName, String relativePath, int width, int height, int frameIndex) {
        try {
            Path path = storagePathService.resolveRelativePath(relativePath);
            jdbcTemplate.update("""
                    INSERT INTO qc_file (
                        task_id, record_id, file_type, file_name, relative_path, file_ext, file_size, mime_type, width, height, frame_index
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    currentTaskId,
                    recordId,
                    fileType,
                    fileName,
                    relativePath,
                    "png",
                    Files.size(path),
                    "image/png",
                    width,
                    height,
                    frameIndex
            );
        } catch (IOException exception) {
            throw new IllegalStateException("写入文件记录失败", exception);
        }
    }

    private void writeImage(BufferedImage image, String relativePath) {
        Path path = storagePathService.resolveAndEnsureParent(relativePath);
        try {
            ImageIO.write(image, "png", path.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("保存实时质检图片失败: " + relativePath, exception);
        }
    }

    private BufferedImage readImage(byte[] frameBytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(frameBytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IllegalStateException("实时帧不是有效图片");
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("解析实时帧失败", exception);
        }
    }

    private byte[] toJpeg(BufferedImage image) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("结果图转 JPEG 失败", exception);
        }
    }

    private void trimEvidence() {
        int maxEvidence = Math.max(2, intConfig("evidence_image_count", qcProperties.getInference().getEvidenceImageCount()));
        while (evidenceBuffer.size() > maxEvidence) {
            evidenceBuffer.removeFirst();
        }
    }

    private void touchPreviewFps() {
        previewWindowFrames++;
        long now = System.currentTimeMillis();
        if (now - previewWindowStart >= 1000L) {
            latestPreviewFps = previewWindowFrames;
            previewWindowStart = now;
            previewWindowFrames = 0;
        }
    }

    private void touchInferenceFps() {
        inferenceWindowFrames++;
        long now = System.currentTimeMillis();
        if (now - inferenceWindowStart >= 1000L) {
            latestInferenceFps = inferenceWindowFrames;
            inferenceWindowFrames = 0;
            inferenceWindowStart = now;
        }
    }

    private long sampleIntervalMs() {
        int targetFps = Math.max(1, intConfig("target_inference_fps", qcProperties.getInference().getTargetInferenceFps()));
        return Math.max(DEFAULT_SAMPLE_INTERVAL_MS, 1000L / targetFps);
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

    private void broadcastStatus(RealtimeStatusResponse status) {
        try {
            RealtimeStatusWebSocketHandler handler = realtimeStatusWebSocketHandlerProvider.getIfAvailable();
            if (handler == null) {
                return;
            }
            handler.broadcast("""
                    {
                      "type": "status",
                      "runtimeStatus": %s,
                      "previewFps": %d,
                      "inferenceFps": %d,
                      "latencyMs": %d,
                      "providerStrategy": %s
                    }
                    """.formatted(
                    json(status.runtimeStatus()),
                    status.previewFps(),
                    status.inferenceFps(),
                    status.latencyMs(),
                    json(status.providerStrategy())
            ));
        } catch (IOException ignored) {
        }
    }

    private void pushEvent(RealtimeEventResponse event) {
        while (recentEvents.size() >= 30) {
            recentEvents.removeFirst();
        }
        recentEvents.addLast(event);
        try {
            RealtimeStatusWebSocketHandler handler = realtimeStatusWebSocketHandlerProvider.getIfAvailable();
            if (handler == null) {
                return;
            }
            handler.broadcast("""
                    {
                      "type": %s,
                      "title": %s,
                      "message": %s,
                      "tone": %s,
                      "taskNo": %s,
                      "recordNo": %s,
                      "eventTime": %s
                    }
                    """.formatted(
                    json(event.type()),
                    json(event.title()),
                    json(event.message()),
                    json(event.tone()),
                    json(event.taskNo()),
                    json(event.recordNo()),
                    json(event.eventTime().toString())
            ));
        } catch (IOException ignored) {
        }
    }

    private void addEvidence(RealtimeEvidenceResponse evidence) {
        while (recentEvidence.size() >= 12) {
            recentEvidence.removeFirst();
        }
        recentEvidence.addLast(evidence);
    }

    private void updateConfigValue(String code, Object value) {
        if (value != null) {
            catalogService.updateConfig(code, String.valueOf(value));
        }
    }

    private int intConfig(String code, int defaultValue) {
        try {
            return Integer.parseInt(catalogService.getConfigValue(code, String.valueOf(defaultValue)));
        } catch (Exception exception) {
            return defaultValue;
        }
    }

    private double doubleConfig(String code, double defaultValue) {
        try {
            return Double.parseDouble(catalogService.getConfigValue(code, String.valueOf(defaultValue)));
        } catch (Exception exception) {
            return defaultValue;
        }
    }

    private String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private record FrameSnapshot(
            BufferedImage sourceImage,
            BufferedImage resultImage,
            InferenceModels.InferenceOutput output
    ) {
    }

    public record RealtimeFrameResultResponse(
            String type,
            String renderMode,
            String runtimeStatus,
            String providerStrategy,
            int previewFps,
            int inferenceFps,
            int latencyMs,
            String resultJudge,
            Double confidenceScore,
            String defectType,
            String resultSummary,
            int imageWidth,
            int imageHeight,
            List<DetectionResultResponse> detections
    ) {
        public static RealtimeFrameResultResponse idle(RealtimeStatusResponse status) {
            return new RealtimeFrameResultResponse(
                    "frame-result",
                    "idle",
                    status.runtimeStatus(),
                    status.providerStrategy(),
                    status.previewFps(),
                    status.inferenceFps(),
                    status.latencyMs(),
                    "IDLE",
                    null,
                    null,
                    "等待视频帧",
                    0,
                    0,
                    List.of()
            );
        }

        public static RealtimeFrameResultResponse error(RealtimeStatusResponse status, String message) {
            return new RealtimeFrameResultResponse(
                    "frame-result",
                    "error",
                    status.runtimeStatus(),
                    status.providerStrategy(),
                    status.previewFps(),
                    status.inferenceFps(),
                    status.latencyMs(),
                    "ERROR",
                    null,
                    null,
                    message,
                    0,
                    0,
                    List.of()
            );
        }

        public RealtimeFrameResultResponse withRenderMode(String mode) {
            return new RealtimeFrameResultResponse(
                    type,
                    mode,
                    runtimeStatus,
                    providerStrategy,
                    previewFps,
                    inferenceFps,
                    latencyMs,
                    resultJudge,
                    confidenceScore,
                    defectType,
                    resultSummary,
                    imageWidth,
                    imageHeight,
                    detections
            );
        }
    }
}
