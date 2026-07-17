package com.ruikang.qc.controller.inspection;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.inference.ClientImageInspectionRequest;
import com.ruikang.qc.dto.inspection.ImageInspectionTaskRequest;
import com.ruikang.qc.dto.inspection.ImageInspectionTaskResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionRecordResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionUploadResponse;
import com.ruikang.qc.service.ImageInspectionTaskService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/inspection/image")
public class ImageInspectionController {

    private final ImageInspectionTaskService imageInspectionTaskService;

    public ImageInspectionController(ImageInspectionTaskService imageInspectionTaskService) {
        this.imageInspectionTaskService = imageInspectionTaskService;
    }

    @PostMapping("/tasks")
    public ApiResponse<ImageInspectionTaskResponse> createTask(@Valid @RequestBody ImageInspectionTaskRequest request) {
        return ApiResponse.success("图片质检任务已创建", imageInspectionTaskService.createTask(request.fileNames(), request.remark()));
    }

    @PostMapping(value = "/tasks/client-results", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageInspectionUploadResponse> acceptClientResults(
            @Valid @RequestPart("payload") ClientImageInspectionRequest payload,
            @RequestPart("sourceFiles") List<MultipartFile> sourceFiles,
            @RequestPart("resultFiles") List<MultipartFile> resultFiles
    ) {
        return ApiResponse.success(
                "浏览器质检结果已保存",
                imageInspectionTaskService.acceptClientResults(payload, sourceFiles, resultFiles)
        );
    }

    @GetMapping("/tasks/{taskNo}/records")
    public ApiResponse<List<ImageInspectionRecordResponse>> listTaskRecords(@PathVariable String taskNo) {
        return ApiResponse.success(imageInspectionTaskService.listTaskRecords(taskNo));
    }
}
