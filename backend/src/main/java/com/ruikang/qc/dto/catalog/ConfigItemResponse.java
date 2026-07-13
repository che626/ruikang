package com.ruikang.qc.dto.catalog;

public record ConfigItemResponse(
        Long id,
        String configCode,
        String configName,
        String configValue,
        String valueType,
        String configGroup,
        String remark
) {
}
