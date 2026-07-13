package com.ruikang.qc.dto.record;

import jakarta.validation.constraints.NotBlank;

public record ReviewRecordRequest(
        @NotBlank(message = "复核状态不能为空") String reviewStatus,
        String reviewRemark
) {
}
