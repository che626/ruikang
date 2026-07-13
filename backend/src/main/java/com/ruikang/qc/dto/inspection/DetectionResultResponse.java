package com.ruikang.qc.dto.inspection;

public record DetectionResultResponse(
        String defectCode,
        String defectName,
        String displayColor,
        double confidence,
        int x1,
        int y1,
        int x2,
        int y2,
        int width,
        int height,
        int area
) {
}
