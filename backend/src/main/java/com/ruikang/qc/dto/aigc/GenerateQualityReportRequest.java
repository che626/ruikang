package com.ruikang.qc.dto.aigc;

import jakarta.validation.constraints.NotBlank;

public record GenerateQualityReportRequest(
        @NotBlank String recordNo
) {
}
