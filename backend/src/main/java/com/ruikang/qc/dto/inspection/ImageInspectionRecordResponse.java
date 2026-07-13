package com.ruikang.qc.dto.inspection;

import java.util.List;

public record ImageInspectionRecordResponse(
        String recordNo,
        String resultJudge,
        String reviewStatus,
        int defectCount,
        Double confidenceScore,
        Integer preprocessTimeMs,
        Integer inferenceTimeMs,
        Integer postprocessTimeMs,
        String resultSummary,
        String sourceImagePath,
        String resultImagePath,
        List<DetectionResultResponse> detections
) {
}
