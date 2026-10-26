package com.ruikang.qc.dto.record;

import java.time.LocalDateTime;

public record RecordListItemResponse(
        String recordNo,
        String inspectType,
        String resultJudge,
        String reviewStatus,
        int defectCount,
        Double confidenceScore,
        String resultSummary,
        LocalDateTime inspectTime,
        String thumbnailImagePath
) {
}
