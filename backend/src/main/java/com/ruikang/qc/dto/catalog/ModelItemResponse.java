package com.ruikang.qc.dto.catalog;

public record ModelItemResponse(
        Long id,
        String modelName,
        String modelVersion,
        String modelPath,
        String inputSize,
        Double confThreshold,
        Double iouThreshold,
        Boolean preferGpu,
        Boolean fallbackCpu,
        Integer isActive
) {
}
