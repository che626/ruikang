package com.ruikang.qc.dto.aigc;

import java.time.LocalDateTime;

public record QualityReportResponse(
        String reportNo,
        String recordNo,
        String reportTitle,
        String defectSummary,
        String causeAnalysis,
        String riskAssessment,
        String handlingSuggestion,
        String qualityConclusion,
        String riskLevel,
        String aiProvider,
        String aiModel,
        LocalDateTime createTime
) {
}
