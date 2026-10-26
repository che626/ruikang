package com.ruikang.qc.dto.inspection;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RealtimeConfigRequest(
        @DecimalMin("0.01") @DecimalMax("0.99") Double confidenceThreshold,
        @DecimalMin("0.01") @DecimalMax("0.99") Double iouThreshold,
        @Min(1) @Max(20) Integer continuousHitFrames,
        @Min(1) @Max(60) Integer targetInferenceFps,
        @Min(1) @Max(5) Integer evidenceImageCount
) {
}
