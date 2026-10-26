package com.ruikang.qc.service;

import com.ruikang.qc.dto.inference.ClientImageInspectionRequest;
import com.ruikang.qc.dto.inference.ClientImageResultRequest;
import com.ruikang.qc.dto.inspection.DetectionResultResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionRecordResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionTaskResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionUploadResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImageInspectionTaskService {

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final JdbcTemplate jdbcTemplate;
    private final StoragePathService storagePathService;
    private final CatalogService catalogService;
    private final ClientInferenceResultValidator resultValidator;

    public ImageInspectionTaskService(
            JdbcTemplate jdbcTemplate,
            StoragePathService storagePathService,
            CatalogService catalogService,
            ClientInferenceResultValidator resultValidator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storagePathService = storagePathService;
        this.catalogService = catalogService;
        this.resultValidator = resultValidator;
    }

    @Transactional
    public ImageInspectionUploadResponse acceptClientResults(
            ClientImageInspectionRequest payload,
            List<MultipartFile> sourceFiles,
            List<MultipartFile> resultFiles
    ) {
        resultValidator.validateModelHash(payload.modelSha256());
        validateFileLists(payload.records(), sourceFiles, resultFiles);

        String taskNo = "IMG-" + randomSuffix();
        Long modelId = catalogService.getActiveModelId();
        Long cameraId = catalogService.getPrimaryCameraId();

        jdbcTemplate.update("""
                INSERT INTO qc_task (
                    task_no, task_name, task_type, camera_id, model_id, task_status,
                    total_image_count, processed_image_count, start_time, remark
                )
                VALUES (?, ?, 'image', ?, ?, 'RUNNING', ?, 0, CURRENT_TIMESTAMP, ?)
                """,
                taskNo,
                "图片质检任务-" + taskNo,
                cameraId,
                modelId,
                payload.records().size(),
                payload.remark()
        );

        Long taskId = queryTaskId(taskNo);
        List<ImageInspectionRecordResponse> responses = new ArrayList<>();
        int totalDefectCount = 0;

        for (int index = 0; index < payload.records().size(); index++) {
            ClientImageResultRequest clientResult = payload.records().get(index);
            MultipartFile sourceFile = sourceFiles.get(index);
            MultipartFile resultFile = resultFiles.get(index);
            List<ClientInferenceResultValidator.ValidatedDetection> detections = resultValidator.validateDetections(
                    clientResult.detections(),
                    clientResult.imageWidth(),
                    clientResult.imageHeight()
            );

            StoredImagePair storedFiles = storeImagePair(taskNo, index, clientResult, sourceFile, resultFile);
            String recordNo = "REC-" + randomSuffix();
            String resultJudge = detections.isEmpty() ? "PASS" : "FAIL";
            String resultSummary = detections.isEmpty()
                    ? "未检测到缺陷"
                    : "检测到 " + detections.size() + " 个缺陷";
            Double topConfidence = detections.stream()
                    .map(ClientInferenceResultValidator.ValidatedDetection::confidence)
                    .max(Double::compareTo)
                    .orElse(null);

            jdbcTemplate.update("""
                    INSERT INTO qc_record (
                        record_no, task_id, camera_id, model_id, inspect_type, source_type, result_judge,
                        confidence_score, defect_count, result_summary, inference_time_ms, preprocess_time_ms,
                        postprocess_time_ms, review_status, inspect_time
                    )
                    VALUES (?, ?, ?, ?, 'image', 'upload', ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """,
                    recordNo,
                    taskId,
                    cameraId,
                    modelId,
                    resultJudge,
                    topConfidence,
                    detections.size(),
                    resultSummary,
                    clientResult.inferenceTimeMs(),
                    clientResult.preprocessTimeMs(),
                    clientResult.postprocessTimeMs()
            );

            Long recordId = queryRecordId(recordNo);
            persistFile(taskId, recordId, "source_image", storedFiles.sourceFileName(), storedFiles.sourcePath(),
                    sourceFile.getContentType(), storedFiles.sourceSize(), clientResult.imageWidth(), clientResult.imageHeight(), null);
            persistFile(taskId, recordId, "result_image", storedFiles.resultFileName(), storedFiles.resultPath(),
                    resultFile.getContentType(), storedFiles.resultSize(), clientResult.imageWidth(), clientResult.imageHeight(), null);

            List<DetectionResultResponse> detectionResponses = persistDetections(
                    recordId,
                    clientResult.imageWidth(),
                    clientResult.imageHeight(),
                    detections
            );
            if (!detections.isEmpty()) {
                persistException(taskId, recordId, detections, resultSummary, "图片质检发现 ");
            }

            totalDefectCount += detections.size();
            responses.add(new ImageInspectionRecordResponse(
                    recordNo,
                    resultJudge,
                    "PENDING",
                    detections.size(),
                    topConfidence,
                    clientResult.preprocessTimeMs(),
                    clientResult.inferenceTimeMs(),
                    clientResult.postprocessTimeMs(),
                    resultSummary,
                    storedFiles.sourcePath(),
                    storedFiles.resultPath(),
                    detectionResponses
            ));
        }

        jdbcTemplate.update("""
                UPDATE qc_task
                SET processed_image_count = ?, total_defect_count = ?, task_status = 'FINISHED', finish_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, responses.size(), totalDefectCount, taskId);

        return new ImageInspectionUploadResponse(taskNo, "FINISHED", responses.size(), responses);
    }

    public ImageInspectionTaskResponse createTask(List<String> fileNames, String remark) {
        String taskNo = "IMG-" + randomSuffix();
        jdbcTemplate.update("""
                INSERT INTO qc_task (
                    task_no, task_name, task_type, task_status, total_image_count, processed_image_count, remark
                )
                VALUES (?, ?, 'image', 'CREATED', ?, 0, ?)
                """, taskNo, "图片质检任务-" + taskNo, fileNames.size(), remark);
        return new ImageInspectionTaskResponse(taskNo, "image", "CREATED", fileNames.size(), fileNames, "图片质检任务已创建，可继续提交浏览器检测结果。");
    }

    public List<ImageInspectionRecordResponse> listTaskRecords(String taskNo) {
        return jdbcTemplate.query("""
                        SELECT r.record_no, r.result_judge, r.review_status, r.defect_count, r.confidence_score,
                               r.preprocess_time_ms, r.inference_time_ms, r.postprocess_time_ms, r.result_summary
                        FROM qc_record r
                        JOIN qc_task t ON t.id = r.task_id
                        WHERE t.task_no = ?
                        ORDER BY r.inspect_time DESC
                        """,
                (resultSet, rowNum) -> {
                    String recordNo = resultSet.getString("record_no");
                    return new ImageInspectionRecordResponse(
                            recordNo,
                            resultSet.getString("result_judge"),
                            resultSet.getString("review_status"),
                            resultSet.getInt("defect_count"),
                            resultSet.getObject("confidence_score", Double.class),
                            resultSet.getObject("preprocess_time_ms", Integer.class),
                            resultSet.getObject("inference_time_ms", Integer.class),
                            resultSet.getObject("postprocess_time_ms", Integer.class),
                            resultSet.getString("result_summary"),
                            queryFilePath(recordNo, "source_image"),
                            queryFilePath(recordNo, "result_image"),
                            queryDetections(recordNo)
                    );
                },
                taskNo);
    }

    private void validateFileLists(
            List<ClientImageResultRequest> records,
            List<MultipartFile> sourceFiles,
            List<MultipartFile> resultFiles
    ) {
        if (sourceFiles == null || resultFiles == null
                || sourceFiles.size() != records.size()
                || resultFiles.size() != records.size()) {
            throw new IllegalArgumentException("检测结果与原图、结果图数量不一致");
        }
        sourceFiles.forEach(this::validateImageFile);
        resultFiles.forEach(this::validateImageFile);
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("仅支持图片文件");
        }
    }

    private StoredImagePair storeImagePair(
            String taskNo,
            int index,
            ClientImageResultRequest clientResult,
            MultipartFile sourceFile,
            MultipartFile resultFile
    ) {
        String datePath = LocalDate.now().format(DATE_PATH_FORMAT);
        String sourceName = String.format("%02d-%s", index + 1, sanitizeFilename(clientResult.originalFileName()));
        String resultExtension = imageExtension(resultFile, "jpg");
        String resultName = String.format("%02d-%s-result.%s", index + 1, stripExtension(sourceName), resultExtension);
        String sourceRelativePath = "image/" + datePath + "/" + taskNo + "/source/" + sourceName;
        String resultRelativePath = "image/" + datePath + "/" + taskNo + "/result/" + resultName;
        Path sourcePath = storagePathService.resolveAndEnsureParent(sourceRelativePath);
        Path resultPath = storagePathService.resolveAndEnsureParent(resultRelativePath);
        try {
            sourceFile.transferTo(sourcePath);
            resultFile.transferTo(resultPath);
            return new StoredImagePair(
                    sourceName,
                    resultName,
                    sourceRelativePath,
                    resultRelativePath,
                    Files.size(sourcePath),
                    Files.size(resultPath)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("保存图片质检文件失败", exception);
        }
    }

    private List<DetectionResultResponse> persistDetections(
            Long recordId,
            int imageWidth,
            int imageHeight,
            List<ClientInferenceResultValidator.ValidatedDetection> detections
    ) {
        List<DetectionResultResponse> responses = new ArrayList<>();
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
            responses.add(new DetectionResultResponse(
                    detection.defectCode(),
                    detection.defectName(),
                    detection.displayColor(),
                    detection.confidence(),
                    detection.x1(),
                    detection.y1(),
                    detection.x2(),
                    detection.y2(),
                    detection.width(),
                    detection.height(),
                    detection.area()
            ));
        }
        return responses;
    }

    private void persistException(
            Long taskId,
            Long recordId,
            List<ClientInferenceResultValidator.ValidatedDetection> detections,
            String resultSummary,
            String titlePrefix
    ) {
        String exceptionNo = "EX-" + randomSuffix();
        jdbcTemplate.update("""
                INSERT INTO qc_exception (
                    exception_no, record_id, task_id, exception_level, exception_title, exception_desc, status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN')
                """,
                exceptionNo,
                recordId,
                taskId,
                detections.size() >= 2 ? "HIGH" : "MEDIUM",
                titlePrefix + detections.get(0).defectName() + " 缺陷",
                resultSummary
        );
    }

    private void persistFile(
            Long taskId,
            Long recordId,
            String fileType,
            String fileName,
            String relativePath,
            String mimeType,
            long fileSize,
            int width,
            int height,
            Integer frameIndex
    ) {
        jdbcTemplate.update("""
                INSERT INTO qc_file (
                    task_id, record_id, file_type, file_name, relative_path,
                    file_ext, file_size, mime_type, width, height, frame_index
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskId,
                recordId,
                fileType,
                fileName,
                relativePath,
                extension(fileName),
                fileSize,
                mimeType,
                width,
                height,
                frameIndex
        );
    }

    private Long queryTaskId(String taskNo) {
        return jdbcTemplate.queryForObject("SELECT id FROM qc_task WHERE task_no = ?", Long.class, taskNo);
    }

    private Long queryRecordId(String recordNo) {
        return jdbcTemplate.queryForObject("SELECT id FROM qc_record WHERE record_no = ?", Long.class, recordNo);
    }

    private List<DetectionResultResponse> queryDetections(String recordNo) {
        return jdbcTemplate.query("""
                        SELECT d.defect_code, d.defect_name, t.display_color, d.confidence,
                               d.x1, d.y1, d.x2, d.y2, d.box_width, d.box_height, d.area
                        FROM qc_defect_result d
                        JOIN qc_record r ON r.id = d.record_id
                        LEFT JOIN defect_type t ON t.id = d.defect_type_id
                        WHERE r.record_no = ?
                        ORDER BY d.confidence DESC
                        """,
                (resultSet, rowNum) -> new DetectionResultResponse(
                        resultSet.getString("defect_code"),
                        resultSet.getString("defect_name"),
                        resultSet.getString("display_color"),
                        resultSet.getDouble("confidence"),
                        resultSet.getInt("x1"),
                        resultSet.getInt("y1"),
                        resultSet.getInt("x2"),
                        resultSet.getInt("y2"),
                        resultSet.getInt("box_width"),
                        resultSet.getInt("box_height"),
                        resultSet.getInt("area")
                ),
                recordNo);
    }

    private String queryFilePath(String recordNo, String fileType) {
        return jdbcTemplate.query("""
                        SELECT f.relative_path
                        FROM qc_file f
                        JOIN qc_record r ON r.id = f.record_id
                        WHERE r.record_no = ? AND f.file_type = ?
                        ORDER BY f.id ASC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getString("relative_path") : null,
                recordNo, fileType);
    }

    private String imageExtension(MultipartFile file, String fallback) {
        String originalName = file.getOriginalFilename();
        String value = extension(originalName == null ? "" : originalName);
        return value.isBlank() ? fallback : value;
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload-" + UUID.randomUUID().toString().replace("-", "") + ".png";
        }
        return originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String extension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record StoredImagePair(
            String sourceFileName,
            String resultFileName,
            String sourcePath,
            String resultPath,
            long sourceSize,
            long resultSize
    ) {
    }
}
