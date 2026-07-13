package com.ruikang.qc.dto.catalog;

import jakarta.validation.constraints.NotNull;

public record UpdateModelActiveRequest(
        @NotNull Boolean active
) {
}
