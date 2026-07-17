package com.ruikang.qc.dto.inference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ClientImageResultRequest(
        @NotBlank String originalFileName,
        @Positive int imageWidth,
        @Positive int imageHeight,
        @PositiveOrZero int preprocessTimeMs,
        @PositiveOrZero int inferenceTimeMs,
        @PositiveOrZero int postprocessTimeMs,
        @NotBlank String providerStrategy,
        @NotNull @Size(max = 300) List<@Valid ClientDetectionRequest> detections
) {
}
