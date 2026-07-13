package com.ruikang.qc.dto.exceptioncase;

import java.time.LocalDateTime;

public record ExceptionListItemResponse(
        String exceptionNo,
        String exceptionTitle,
        String exceptionLevel,
        String status,
        String recordNo,
        LocalDateTime createTime
) {
}
