package com.ruikang.qc.dto.aigc;

import java.time.LocalDateTime;

public record QualityReportListItemResponse(
        String reportNo,
        String recordNo,
        String reportTitle,
        String riskLevel,
        String aiProvider,
        String aiModel,
        LocalDateTime createTime
) {
}
