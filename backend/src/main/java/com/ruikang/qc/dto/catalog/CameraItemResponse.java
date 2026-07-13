package com.ruikang.qc.dto.catalog;

public record CameraItemResponse(
        Long id,
        String cameraCode,
        String cameraName,
        String cameraType,
        Integer status,
        String locationDesc
) {
}
