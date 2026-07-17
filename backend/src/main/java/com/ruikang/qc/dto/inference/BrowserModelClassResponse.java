package com.ruikang.qc.dto.inference;

public record BrowserModelClassResponse(
        int index,
        String defectCode,
        String defectName,
        String displayColor
) {
}
