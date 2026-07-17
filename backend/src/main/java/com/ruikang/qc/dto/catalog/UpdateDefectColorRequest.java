package com.ruikang.qc.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateDefectColorRequest(
        @NotBlank(message = "显示颜色不能为空")
        @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "显示颜色必须为 #RRGGBB 格式")
        String displayColor
) {
}
