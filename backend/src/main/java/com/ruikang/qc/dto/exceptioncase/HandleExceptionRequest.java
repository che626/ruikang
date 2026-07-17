package com.ruikang.qc.dto.exceptioncase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HandleExceptionRequest(
        @NotBlank(message = "异常状态不能为空")
        @Pattern(
                regexp = "OPEN|PROCESSING|CLOSED|IGNORED",
                message = "异常状态必须为 OPEN、PROCESSING、CLOSED 或 IGNORED"
        )
        String status,
        String handleResult,
        String handleRemark
) {
}
