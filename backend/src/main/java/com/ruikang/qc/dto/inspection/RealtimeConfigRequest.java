package com.ruikang.qc.dto.inspection;

public record RealtimeConfigRequest(
        Double confidenceThreshold,
        Double iouThreshold,
        Integer continuousHitFrames,
        Integer targetInferenceFps,
        Integer evidenceImageCount
) {
}
