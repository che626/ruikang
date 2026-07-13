package com.ruikang.qc.controller.inspection;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionTaskRequest;
import com.ruikang.qc.dto.inspection.ImageInspectionTaskResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionRecordResponse;
import com.ruikang.qc.dto.inspection.ImageInspectionUploadResponse;
import com.ruikang.qc.service.ImageInspectionTaskService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping(value = "/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageInspectionUploadResponse> uploadAndInspect(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "remark", required = false) String remark
    ) {
        return ApiResponse.success("图片已上传并完成质检", imageInspectionTaskService.uploadAndInspect(files, remark));
    }

    @GetMapping("/tasks/{taskNo}/records")
    public ApiResponse<List<ImageInspectionRecordResponse>> listTaskRecords(@PathVariable String taskNo) {
        return ApiResponse.success(imageInspectionTaskService.listTaskRecords(taskNo));
    }
}
