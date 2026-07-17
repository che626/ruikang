package com.ruikang.qc.controller.inspection;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.inference.RealtimeClientEventRequest;
import com.ruikang.qc.dto.inference.RealtimeEventAcceptedResponse;
import com.ruikang.qc.dto.inference.RealtimeMetricsRequest;
import com.ruikang.qc.dto.inspection.RealtimeConfigRequest;
import com.ruikang.qc.dto.inspection.RealtimeEvidenceResponse;
import com.ruikang.qc.dto.inspection.RealtimeEventResponse;
import com.ruikang.qc.dto.inspection.RealtimeStatusResponse;
import com.ruikang.qc.service.RealtimeInspectionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/inspection/realtime")
public class RealtimeInspectionController {

    private final RealtimeInspectionService realtimeInspectionService;

    public RealtimeInspectionController(RealtimeInspectionService realtimeInspectionService) {
        this.realtimeInspectionService = realtimeInspectionService;
    }

    @GetMapping("/status")
    public ApiResponse<RealtimeStatusResponse> status() {
        return ApiResponse.success(realtimeInspectionService.status());
    }

    @PostMapping("/start")
    public ApiResponse<RealtimeStatusResponse> start() {
        return ApiResponse.success("实时检测已启动", realtimeInspectionService.start());
    }

    @PostMapping("/stop")
    public ApiResponse<RealtimeStatusResponse> stop() {
        return ApiResponse.success("实时检测已停止", realtimeInspectionService.stop());
    }

    @GetMapping("/events")
    public ApiResponse<List<RealtimeEventResponse>> events() {
        return ApiResponse.success(realtimeInspectionService.listEvents());
    }

    @GetMapping("/evidence")
    public ApiResponse<List<RealtimeEvidenceResponse>> evidence() {
        return ApiResponse.success(realtimeInspectionService.listEvidence());
    }

    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory() {
        realtimeInspectionService.clearRealtimeHistory();
        return ApiResponse.success("实时事件和证据图已清空", null);
    }

    @PutMapping("/config")
    public ApiResponse<RealtimeStatusResponse> updateConfig(@Valid @RequestBody RealtimeConfigRequest request) {
        return ApiResponse.success("实时参数已更新", realtimeInspectionService.updateConfig(request));
    }

    @PutMapping("/metrics")
    public ApiResponse<RealtimeStatusResponse> updateMetrics(@Valid @RequestBody RealtimeMetricsRequest request) {
        return ApiResponse.success(realtimeInspectionService.updateMetrics(request));
    }

    @PostMapping(value = "/client-events", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RealtimeEventAcceptedResponse> acceptClientEvent(
            @Valid @RequestPart("payload") RealtimeClientEventRequest payload,
            @RequestPart("sourceFiles") List<MultipartFile> sourceFiles,
            @RequestPart("resultFiles") List<MultipartFile> resultFiles
    ) {
        return ApiResponse.success(
                "浏览器实时检测事件已处理",
                realtimeInspectionService.acceptClientEvent(payload, sourceFiles, resultFiles)
        );
    }
}
