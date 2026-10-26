package com.ruikang.qc.dto.record;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ReviewRecordRequest(
        @NotBlank(message = "复核状态不能为空")
        @Pattern(
                regexp = "PENDING|CONFIRMED|PASS|REJECTED|MISSED|FALSE_ALARM",
                message = "复核状态必须为 PENDING、CONFIRMED、PASS、REJECTED、MISSED 或 FALSE_ALARM"
        )
        String reviewStatus,
        String reviewRemark
) {
}
