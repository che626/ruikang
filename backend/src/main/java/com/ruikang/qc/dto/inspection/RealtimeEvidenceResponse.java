package com.ruikang.qc.dto.inspection;

import java.time.LocalDateTime;

public record RealtimeEvidenceResponse(
        String recordNo,
        String fileType,
        String relativePath,
        LocalDateTime createTime
) {
}
