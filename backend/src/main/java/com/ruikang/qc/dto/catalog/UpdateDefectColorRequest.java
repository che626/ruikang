package com.ruikang.qc.dto.catalog;

import jakarta.validation.constraints.NotBlank;

public record UpdateDefectColorRequest(
        @NotBlank String displayColor
) {
}
