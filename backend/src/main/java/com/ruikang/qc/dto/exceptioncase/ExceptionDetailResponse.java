package com.ruikang.qc.dto.exceptioncase;

import java.time.LocalDateTime;
import java.util.List;

public record ExceptionDetailResponse(
        String exceptionNo,
        String exceptionTitle,
        String exceptionDesc,
        String exceptionLevel,
        String status,
        String recordNo,
        String handleResult,
        String handleRemark,
        LocalDateTime createTime,
        LocalDateTime handleTime,
        List<String> evidenceImagePaths
) {
}
