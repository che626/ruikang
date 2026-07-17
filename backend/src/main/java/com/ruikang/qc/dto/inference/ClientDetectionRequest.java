package com.ruikang.qc.dto.inference;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record ClientDetectionRequest(
        @NotBlank String defectCode,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @PositiveOrZero int x1,
        @PositiveOrZero int y1,
        @PositiveOrZero int x2,
        @PositiveOrZero int y2
) {
}
