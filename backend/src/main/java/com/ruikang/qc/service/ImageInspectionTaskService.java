package com.ruikang.qc.service;

import com.ruikang.qc.dto.inspection.DetectionResultResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionRecordResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionTaskResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionUploadResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
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

    private static final DateTimeFormatter TASK_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final JdbcTemplate jdbcTemplate;
    private final StoragePathService storagePathService;
    private final OnnxImageInspectionService onnxImageInspectionService;
    private final CatalogService catalogService;

    public ImageInspectionTaskService(
            JdbcTemplate jdbcTemplate,
            StoragePathService storagePathService,
            OnnxImageInspectionService onnxImageInspectionService,
            CatalogService catalogService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storagePathService = storagePathService;
        this.onnxImageInspectionService = onnxImageInspectionService;
        this.catalogService = catalogService;
    }

    public ImageInspectionUploadResponse uploadAndInspect(List<MultipartFile> files, String remark) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("至少上传一张图片");
        }

        String taskNo = "IMG-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
        Long modelId = catalogService.getActiveModelId();
        Long cameraId = catalogService.getPrimaryCameraId();

        jdbcTemplate.update("""
                INSERT INTO qc_task (
                    task_no, task_name, task_type, camera_id, model_id, task_status,
                    total_image_count, processed_image_count, start_time, remark
                )
                VALUES (?, ?, 'image', ?, ?, 'RUNNING', ?, 0, CURRENT_TIMESTAMP, ?)
                """, taskNo, "图片质检任务-" + taskNo, cameraId, modelId, files.size(), remark);

        Long taskId = queryTaskId(taskNo);
        List<ImageInspectionRecordResponse> recordResponses = new ArrayList<>();

        int totalDefectCount = 0;
        int processedCount = 0;
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            String safeName = sanitizeFilename(file.getOriginalFilename());
            String datePath = LocalDate.now().format(DATE_PATH_FORMAT);
            String sourceRelativePath = "image/" + datePath + "/" + taskNo + "/source/" + safeName;
            Path sourcePath = storagePathService.resolveAndEnsureParent(sourceRelativePath);
            try {
                file.transferTo(sourcePath);
            } catch (IOException exception) {
                throw new IllegalStateException("保存原图失败: " + safeName, exception);
            }

            InferenceModels.InferenceOutput output = onnxImageInspectionService.inspect(sourcePath);

            String baseName = stripExtension(safeName);
            String resultFileName = baseName + "-result.png";
            String resultRelativePath = "image/" + datePath + "/" + taskNo + "/result/" + resultFileName;
            Path resultPath = storagePathService.resolveAndEnsureParent(resultRelativePath);
            try {
                ImageIO.write(output.resultImage(), "png", resultPath.toFile());
            } catch (IOException exception) {
                throw new IllegalStateException("保存结果图失败: " + resultFileName, exception);
            }

            String recordNo = "REC-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
            String resultJudge = output.detections().isEmpty() ? "PASS" : "FAIL";
            String resultSummary = output.detections().isEmpty()
                    ? "未检测到缺陷"
                    : "检测到 " + output.detections().size() + " 个缺陷";
            Double topConfidence = output.detections().isEmpty() ? null : output.detections().get(0).confidence();

            jdbcTemplate.update("""
                    INSERT INTO qc_record (
                        record_no, task_id, camera_id, model_id, inspect_type, source_type, result_judge,
                        confidence_score, defect_count, result_summary, inference_time_ms, preprocess_time_ms,
                        postprocess_time_ms, review_status, inspect_time
                    )
                    VALUES (?, ?, ?, ?, 'image', 'upload', ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', CURRENT_TIMESTAMP)
                    """,
                    recordNo,
                    taskId,
                    cameraId,
                    modelId,
                    resultJudge,
                    topConfidence,
                    output.detections().size(),
                    resultSummary,
                    output.inferenceTimeMs(),
                    output.preprocessTimeMs(),
                    output.postprocessTimeMs()
            );

            Long recordId = queryRecordId(recordNo);

            persistFile(taskId, recordId, "source_image", safeName, sourceRelativePath, file.getContentType(), file.getSize(), output.imageWidth(), output.imageHeight(), null);
            persistFile(taskId, recordId, "result_image", resultFileName, resultRelativePath, "image/png", FilesExistsSize(resultPath), output.imageWidth(), output.imageHeight(), null);

            List<DetectionResultResponse> detectionResponses = new ArrayList<>();
            for (InferenceModels.Detection detection : output.detections()) {
                Long defectTypeId = queryDefectTypeId(detection.defectCode());
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
                        output.imageWidth(),
                        output.imageHeight()
                );
                detectionResponses.add(new DetectionResultResponse(
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

            if ("FAIL".equals(resultJudge)) {
                String exceptionNo = "EX-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
                String exceptionTitle = "图片质检发现 " + output.detections().get(0).defectName() + " 缺陷";
                jdbcTemplate.update("""
                        INSERT INTO qc_exception (
                            exception_no, record_id, task_id, exception_level, exception_title, exception_desc, status
                        )
                        VALUES (?, ?, ?, ?, ?, ?, 'OPEN')
                        """,
                        exceptionNo,
                        recordId,
                        taskId,
                        output.detections().size() >= 2 ? "HIGH" : "MEDIUM",
                        exceptionTitle,
                        resultSummary
                );
            }

            processedCount++;
            totalDefectCount += output.detections().size();
            recordResponses.add(new ImageInspectionRecordResponse(
                    recordNo,
                    resultJudge,
                    "CONFIRMED",
                    output.detections().size(),
                    topConfidence,
                    output.preprocessTimeMs(),
                    output.inferenceTimeMs(),
                    output.postprocessTimeMs(),
                    resultSummary,
                    sourceRelativePath,
                    resultRelativePath,
                    detectionResponses
            ));
        }

        jdbcTemplate.update("""
                UPDATE qc_task
                SET processed_image_count = ?, total_defect_count = ?, task_status = 'FINISHED', finish_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, processedCount, totalDefectCount, taskId);

        return new ImageInspectionUploadResponse(taskNo, "FINISHED", recordResponses.size(), recordResponses);
    }

    public ImageInspectionTaskResponse createTask(List<String> fileNames, String remark) {
        String taskNo = "IMG-" + LocalDateTime.now().format(TASK_FORMAT) + randomSuffix();
        jdbcTemplate.update("""
                INSERT INTO qc_task (
                    task_no, task_name, task_type, task_status, total_image_count, processed_image_count, remark
                )
                VALUES (?, ?, 'image', 'CREATED', ?, 0, ?)
                """, taskNo, "图片质检任务-" + taskNo, fileNames.size(), remark);
        return new ImageInspectionTaskResponse(taskNo, "image", "CREATED", fileNames.size(), fileNames, "图片质检任务已创建，可继续上传文件执行质检。");
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
                    List<DetectionResultResponse> detections = queryDetections(recordNo);
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
                            detections
                    );
                },
                taskNo);
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

    private Long queryDefectTypeId(String defectCode) {
        return jdbcTemplate.query("""
                        SELECT id
                        FROM defect_type
                        WHERE defect_code = ?
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong("id") : null,
                defectCode);
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

    private long FilesExistsSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
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
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
