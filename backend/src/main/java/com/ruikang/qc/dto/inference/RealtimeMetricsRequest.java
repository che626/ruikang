package com.ruikang.qc.dto.inference;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record RealtimeMetricsRequest(
        @PositiveOrZero long totalFrameCount,
        @PositiveOrZero long defectFrameCount,
        @PositiveOrZero long totalDefectCount,
        @PositiveOrZero int previewFps,
        @PositiveOrZero int inferenceFps,
        @PositiveOrZero int latencyMs,
        @NotBlank String providerStrategy
) {
}
