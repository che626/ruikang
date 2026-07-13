package com.ruikang.qc.dto.exceptioncase;

import jakarta.validation.constraints.NotBlank;

public record HandleExceptionRequest(
        @NotBlank(message = "异常状态不能为空") String status,
        String handleResult,
        String handleRemark
) {
}
