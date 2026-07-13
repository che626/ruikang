package com.ruikang.qc.dto.inspection;

import java.time.LocalDateTime;

public record RealtimeEventResponse(
        String type,
        String title,
        String message,
        String tone,
        String taskNo,
        String recordNo,
        LocalDateTime eventTime
) {
}
