package com.ruikang.qc.service;

import java.awt.image.BufferedImage;
import java.util.List;

public final class InferenceModels {

    private InferenceModels() {
    }

    public record Detection(
            String defectCode,
            String defectName,
            String displayColor,
            double confidence,
            int x1,
            int y1,
            int x2,
            int y2,
            int width,
            int height,
            int area
    ) {
    }

    public record InferenceOutput(
            String providerStrategy,
            int imageWidth,
            int imageHeight,
            int preprocessTimeMs,
            int inferenceTimeMs,
            int postprocessTimeMs,
            List<Detection> detections,
            BufferedImage resultImage
    ) {
    }
}
