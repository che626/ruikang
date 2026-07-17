package com.ruikang.qc.dto.inference;

public record RealtimeEventAcceptedResponse(
        boolean accepted,
        boolean duplicate,
        String recordNo
) {
}
