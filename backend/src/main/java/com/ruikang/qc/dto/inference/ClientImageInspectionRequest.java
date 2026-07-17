package com.ruikang.qc.dto.inference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ClientImageInspectionRequest(
        @NotBlank String modelSha256,
        String remark,
        @NotNull @Size(min = 1, max = 10) List<@Valid ClientImageResultRequest> records
) {
}
