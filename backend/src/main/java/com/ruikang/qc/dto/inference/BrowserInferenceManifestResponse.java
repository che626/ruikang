package com.ruikang.qc.dto.inference;

import java.util.List;

public record BrowserInferenceManifestResponse(
        String modelName,
        String modelVersion,
        String modelUrl,
        String sha256,
        long fileSize,
        String inputName,
        String outputName,
        int inputWidth,
        int inputHeight,
        List<BrowserModelClassResponse> classes,
        double confidenceThreshold,
        double iouThreshold,
        List<String> preferredExecutionProviders
) {
}
