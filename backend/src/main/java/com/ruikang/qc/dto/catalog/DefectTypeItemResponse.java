package com.ruikang.qc.dto.catalog;

public record DefectTypeItemResponse(
        Long id,
        String defectCode,
        String defectName,
        String defectNameEn,
        String displayColor,
        Integer sortNo,
        Integer status
) {
}
