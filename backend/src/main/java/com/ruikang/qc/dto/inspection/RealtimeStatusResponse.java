package com.ruikang.qc.dto.inspection;

public record RealtimeStatusResponse(
        String cameraName,
        String runtimeStatus,
        int previewFps,
        int inferenceFps,
        int latencyMs,
        String providerStrategy,
        String modelPath,
        double confidenceThreshold,
        double iouThreshold,
        int continuousHitFrames,
        int targetInferenceFps,
        int evidenceImageCount
) {
}
