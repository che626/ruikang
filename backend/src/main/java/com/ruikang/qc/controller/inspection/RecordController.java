package com.ruikang.qc.controller.inspection;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.record.RecordDetailResponse;
import com.ruikang.qc.dto.record.RecordListItemResponse;
import com.ruikang.qc.dto.record.ReviewRecordRequest;
import com.ruikang.qc.service.RecordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping
    public ApiResponse<List<RecordListItemResponse>> list() {
        return ApiResponse.success(recordService.listRecords());
    }

    @DeleteMapping
    public ApiResponse<Void> clear() {
        recordService.clearRecords();
        return ApiResponse.success("质检记录已清空", null);
    }

    @GetMapping("/{recordNo}")
    public ApiResponse<RecordDetailResponse> detail(@PathVariable String recordNo) {
        return ApiResponse.success(recordService.getRecordDetail(recordNo));
    }

    @PostMapping("/{recordNo}/review")
    public ApiResponse<RecordDetailResponse> review(@PathVariable String recordNo, @Valid @RequestBody ReviewRecordRequest request) {
        return ApiResponse.success("复核结果已更新", recordService.reviewRecord(recordNo, request));
    }
}
