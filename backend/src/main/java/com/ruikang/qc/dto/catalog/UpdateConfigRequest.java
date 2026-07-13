package com.ruikang.qc.dto.catalog;

import jakarta.validation.constraints.NotBlank;

public record UpdateConfigRequest(
        @NotBlank(message = "配置值不能为空") String configValue
) {
}
