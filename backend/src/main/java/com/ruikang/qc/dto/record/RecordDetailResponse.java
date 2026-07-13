package com.ruikang.qc.dto.record;

import com.ruikang.qc.dto.inspection.DetectionResultResponse;

import java.time.LocalDateTime;
import java.util.List;

public record RecordDetailResponse(
        String recordNo,
        String inspectType,
        String sourceType,
        String resultJudge,
        Double confidenceScore,
        int defectCount,
        String reviewStatus,
        String reviewRemark,
        LocalDateTime inspectTime,
        Integer preprocessTimeMs,
        Integer inferenceTimeMs,
        Integer postprocessTimeMs,
        String sourceImagePath,
        String resultImagePath,
        List<String> evidenceImagePaths,
        List<DetectionResultResponse> detections,
        String exceptionNo,
        String exceptionStatus
) {
}
