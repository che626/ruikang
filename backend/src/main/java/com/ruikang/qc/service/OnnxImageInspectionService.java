package com.ruikang.qc.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import com.ruikang.qc.config.QcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class OnnxImageInspectionService {

    private static final Logger log = LoggerFactory.getLogger(OnnxImageInspectionService.class);
    private static final int MODEL_SIZE = 640;
    private static final Color DEFAULT_PAD_COLOR = new Color(114, 114, 114);

    private final QcProperties qcProperties;
    private final CatalogService catalogService;
    private final AtomicReference<OrtSession> sessionRef = new AtomicReference<>();
    private final AtomicReference<String> sessionModelPath = new AtomicReference<>();
    private final AtomicReference<String> providerStrategy = new AtomicReference<>("UNINITIALIZED");
    private final java.util.concurrent.atomic.AtomicBoolean forceCpuFallback = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Object sessionLock = new Object();

    public OnnxImageInspectionService(QcProperties qcProperties, CatalogService catalogService) {
        this.qcProperties = qcProperties;
        this.catalogService = catalogService;
    }

    public InferenceModels.InferenceOutput inspect(Path imagePath) {
        return inspect(readImage(imagePath));
    }

    public InferenceModels.InferenceOutput inspect(BufferedImage originalImage) {
        PreparedImage preparedImage = prepareImage(originalImage);
        Map<String, CatalogService.DefectDictionaryItem> defectDictionary = catalogService.getDefectDictionaryMap();

        Instant inferStart = Instant.now();
        float[][][] output = runModel(preparedImage.tensorBuffer());
        int inferenceMs = (int) Duration.between(inferStart, Instant.now()).toMillis();

        Instant postStart = Instant.now();
        List<InferenceModels.Detection> detections = postProcess(output, preparedImage, defectDictionary);
        BufferedImage resultImage = drawDetections(originalImage, detections);
        int postMs = (int) Duration.between(postStart, Instant.now()).toMillis();

        return new InferenceModels.InferenceOutput(
                providerStrategy.get(),
                originalImage.getWidth(),
                originalImage.getHeight(),
                preparedImage.preprocessTimeMs(),
                inferenceMs,
                postMs,
                detections,
                resultImage
        );
    }

    private BufferedImage readImage(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                throw new IllegalArgumentException("无法读取图片文件: " + imagePath);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("读取图片失败: " + imagePath, exception);
        }
    }

    private PreparedImage prepareImage(BufferedImage originalImage) {
        Instant preprocessStart = Instant.now();

        int origWidth = originalImage.getWidth();
        int origHeight = originalImage.getHeight();

        double scale = Math.min((double) MODEL_SIZE / origWidth, (double) MODEL_SIZE / origHeight);
        int resizedWidth = Math.max(1, (int) Math.round(origWidth * scale));
        int resizedHeight = Math.max(1, (int) Math.round(origHeight * scale));
        int padX = (MODEL_SIZE - resizedWidth) / 2;
        int padY = (MODEL_SIZE - resizedHeight) / 2;

        BufferedImage canvas = new BufferedImage(MODEL_SIZE, MODEL_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(DEFAULT_PAD_COLOR);
        graphics.fillRect(0, 0, MODEL_SIZE, MODEL_SIZE);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(originalImage, padX, padY, resizedWidth, resizedHeight, null);
        graphics.dispose();

        float[] inputData = new float[3 * MODEL_SIZE * MODEL_SIZE];
        int offsetG = MODEL_SIZE * MODEL_SIZE;
        int offsetB = 2 * MODEL_SIZE * MODEL_SIZE;
        for (int y = 0; y < MODEL_SIZE; y++) {
            for (int x = 0; x < MODEL_SIZE; x++) {
                int rgb = canvas.getRGB(x, y);
                int index = y * MODEL_SIZE + x;
                float red = ((rgb >> 16) & 0xFF) / 255.0F;
                float green = ((rgb >> 8) & 0xFF) / 255.0F;
                float blue = (rgb & 0xFF) / 255.0F;
                inputData[index] = red;
                inputData[offsetG + index] = green;
                inputData[offsetB + index] = blue;
            }
        }

        int preprocessMs = (int) Duration.between(preprocessStart, Instant.now()).toMillis();
        return new PreparedImage(
                FloatBuffer.wrap(inputData),
                origWidth,
                origHeight,
                scale,
                padX,
                padY,
                preprocessMs
        );
    }

    private float[][][] runModel(FloatBuffer inputBuffer) {
        try {
            return runModelOnce(inputBuffer);
        } catch (OrtException exception) {
            if (!qcProperties.getInference().isFallbackToCpu() || forceCpuFallback.get()) {
                throw new IllegalStateException("执行 ONNX 推理失败", exception);
            }
            log.warn("ONNX CUDA 推理失败，切换到 CPU 重新执行: {}", exception.getMessage(), exception);
            forceCpuFallback.set(true);
            synchronized (sessionLock) {
                closeCurrentSession();
            }
            inputBuffer.rewind();
            try {
                return runModelOnce(inputBuffer);
            } catch (OrtException fallbackException) {
                throw new IllegalStateException("执行 ONNX 推理失败，CPU 回退也失败", fallbackException);
            }
        }
    }

    private float[][][] runModelOnce(FloatBuffer inputBuffer) throws OrtException {
        inputBuffer.rewind();
        OrtSession session = getOrCreateSession();
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), inputBuffer, new long[]{1, 3, MODEL_SIZE, MODEL_SIZE});
             OrtSession.Result result = session.run(Map.of("images", tensor))) {
            Object value = result.get(0).getValue();
            if (value instanceof float[][][] output) {
                int dim0 = output[0].length;
                int dim1 = output[0][0].length;
                log.info("ONNX output shape: [1, {}, {}]", dim0, dim1);
                return output;
            }
            throw new IllegalStateException("模型输出类型不符合预期: " + value.getClass().getName());
        }
    }

    private OrtSession getOrCreateSession() throws OrtException {
        String modelPath = resolveActiveModelPath();
        OrtSession existingSession = sessionRef.get();
        if (existingSession != null && modelPath.equals(sessionModelPath.get())) {
            return existingSession;
        }
        synchronized (sessionLock) {
            existingSession = sessionRef.get();
            if (existingSession != null && modelPath.equals(sessionModelPath.get())) {
                return existingSession;
            }

            closeCurrentSession();

            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

            log.info("ONNX model path resolved: {}", modelPath);

            EnumSet<OrtProvider> providers = OrtEnvironment.getAvailableProviders();
            boolean gpuAvailable = providers.contains(OrtProvider.CUDA)
                    && qcProperties.getInference().isPreferGpu()
                    && !forceCpuFallback.get();

            try {
                if (gpuAvailable) {
                    options.addCUDA(0);
                    providerStrategy.set("CUDAExecutionProvider");
                } else {
                    options.addCPU(true);
                    providerStrategy.set("CPUExecutionProvider");
                }
                existingSession = environment.createSession(modelPath, options);
            } catch (OrtException exception) {
                if (!qcProperties.getInference().isFallbackToCpu()) {
                    throw exception;
                }
                forceCpuFallback.set(true);
                OrtSession.SessionOptions fallback = new OrtSession.SessionOptions();
                fallback.addCPU(true);
                fallback.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                fallback.setInterOpNumThreads(1);
                fallback.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
                providerStrategy.set("CPUExecutionProvider(Fallback)");
                existingSession = environment.createSession(modelPath, fallback);
            }

            sessionRef.set(existingSession);
            sessionModelPath.set(modelPath);
            return existingSession;
        }
    }

    private String resolveActiveModelPath() {
        String configuredPath = catalogService.getActiveModelPath();
        Path modelFilePath = Path.of(configuredPath);
        if (!modelFilePath.isAbsolute()) {
            modelFilePath = Path.of(System.getProperty("user.dir")).resolve(modelFilePath);
        }
        return modelFilePath.toAbsolutePath().normalize().toString();
    }

    private void closeCurrentSession() {
        OrtSession previousSession = sessionRef.getAndSet(null);
        sessionModelPath.set(null);
        if (previousSession == null) {
            return;
        }
        try {
            previousSession.close();
        } catch (OrtException exception) {
            log.warn("Failed to close previous ONNX session", exception);
        }
    }

    private List<InferenceModels.Detection> postProcess(
            float[][][] output,
            PreparedImage preparedImage,
            Map<String, CatalogService.DefectDictionaryItem> defectDictionary
    ) {
        float[][] logits = output[0];
        int dim0 = logits.length;
        int dim1 = logits[0].length;
        int numClasses = catalogService.getDefectTypes().size();
        int expectedChannels = 4 + numClasses;

        if (dim1 == 6 || dim0 == 6) {
            return postProcessDetectedOutput(logits, dim0 == 6, preparedImage, defectDictionary);
        }

        // Detect output format:
        //   transposed = true  → shape [expectedChannels, numAnchors]  (code's original assumption)
        //   transposed = false → shape [numAnchors, expectedChannels]  (standard YOLO ONNX export)
        int numAnchors;
        boolean transposed;

        if (dim0 == expectedChannels) {
            numAnchors = dim1;
            transposed = true;
            log.info("ONNX output format: transposed [{} channels, {} anchors]", dim0, dim1);
        } else if (dim1 == expectedChannels) {
            numAnchors = dim0;
            transposed = false;
            log.info("ONNX output format: standard [{} anchors, {} channels]", dim0, dim1);
        } else {
            throw new IllegalStateException(
                    "Unexpected ONNX output shape: [" + dim0 + ", " + dim1 + "], " +
                    "expected 4+" + numClasses + "=" + expectedChannels + " channels in one dimension");
        }

        // Diagnostic: sample first few class scores to check if they need sigmoid.
        // YOLO model outputs may be raw logits (any real) or post-sigmoid (0–1 range).
        // We sample across anchors to decide.
        double sampleSum = 0;
        double sampleMin = Double.MAX_VALUE;
        double sampleMax = -Double.MAX_VALUE;
        int sampleCount = 0;
        for (int a = 0; a < Math.min(10, numAnchors); a++) {
            for (int c = 0; c < numClasses; c++) {
                double val = transposed ? logits[4 + c][a] : logits[a][4 + c];
                sampleSum += val;
                if (val < sampleMin) { sampleMin = val; }
                if (val > sampleMax) { sampleMax = val; }
                sampleCount++;
            }
        }
        double sampleAvg = sampleSum / sampleCount;
        // If values are outside (0, 1), the model outputs raw logits → apply sigmoid.
        boolean needsSigmoid = sampleMin < 0.0 || sampleMax > 1.0;
        log.info("Class score sample ({} values across {} anchors): min={}, avg={}, max={} -> needsSigmoid={}",
                sampleCount, Math.min(10, numAnchors), sampleMin, sampleAvg, sampleMax, needsSigmoid);

        List<Candidate> candidates = new ArrayList<>();
        List<CatalogService.DefectDictionaryItem> orderedTypes = catalogService.getDefectTypes();
        double confThreshold = doubleConfig("conf_threshold", qcProperties.getInference().getConfidenceThreshold());

        for (int anchorIndex = 0; anchorIndex < numAnchors; anchorIndex++) {
            double bestScore = -1;
            int bestClass = -1;
            for (int classIndex = 0; classIndex < orderedTypes.size(); classIndex++) {
                double rawScore = transposed
                        ? logits[4 + classIndex][anchorIndex]
                        : logits[anchorIndex][4 + classIndex];
                double score = needsSigmoid ? sigmoid(rawScore) : rawScore;
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = classIndex;
                }
            }
            if (bestClass < 0 || bestScore < confThreshold) {
                continue;
            }

            double cx = transposed ? logits[0][anchorIndex] : logits[anchorIndex][0];
            double cy = transposed ? logits[1][anchorIndex] : logits[anchorIndex][1];
            double width = transposed ? logits[2][anchorIndex] : logits[anchorIndex][2];
            double height = transposed ? logits[3][anchorIndex] : logits[anchorIndex][3];

            int x1 = clamp((int) Math.round((cx - width / 2 - preparedImage.padX()) / preparedImage.scale()), 0, preparedImage.origWidth());
            int y1 = clamp((int) Math.round((cy - height / 2 - preparedImage.padY()) / preparedImage.scale()), 0, preparedImage.origHeight());
            int x2 = clamp((int) Math.round((cx + width / 2 - preparedImage.padX()) / preparedImage.scale()), 0, preparedImage.origWidth());
            int y2 = clamp((int) Math.round((cy + height / 2 - preparedImage.padY()) / preparedImage.scale()), 0, preparedImage.origHeight());

            if (x2 <= x1 || y2 <= y1) {
                continue;
            }

            CatalogService.DefectDictionaryItem defectType = orderedTypes.get(bestClass);
            candidates.add(new Candidate(
                    defectType.defectCode(),
                    defectType.defectName(),
                    defectDictionary.getOrDefault(defectType.defectCode(), defectType).displayColor(),
                    bestScore,
                    x1,
                    y1,
                    x2,
                    y2
            ));
        }

        log.info("PostProcess: {} raw candidates (threshold={}), running NMS with iou={}",
                candidates.size(), confThreshold, doubleConfig("iou_threshold", qcProperties.getInference().getIouThreshold()));
        List<InferenceModels.Detection> detections = applyNms(candidates, doubleConfig("iou_threshold", qcProperties.getInference().getIouThreshold()));
        log.info("PostProcess: {} detections after NMS", detections.size());
        return detections;
    }

    private List<InferenceModels.Detection> postProcessDetectedOutput(
            float[][] logits,
            boolean transposed,
            PreparedImage preparedImage,
            Map<String, CatalogService.DefectDictionaryItem> defectDictionary
    ) {
        int rows = transposed ? logits[0].length : logits.length;
        List<CatalogService.DefectDictionaryItem> orderedTypes = catalogService.getDefectTypes();
        double confThreshold = doubleConfig("conf_threshold", qcProperties.getInference().getConfidenceThreshold());
        boolean normalizedCoords = hasNormalizedCoordinates(logits, transposed, rows);

        log.info(
                "ONNX output format: detected boxes [{} rows, 6 values: x1,y1,x2,y2,score,class], normalizedCoords={}",
                rows,
                normalizedCoords
        );

        List<Candidate> candidates = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            double score = valueAt(logits, transposed, row, 4);
            if (score < confThreshold) {
                continue;
            }

            int classIndex = (int) Math.round(valueAt(logits, transposed, row, 5));
            if (classIndex >= orderedTypes.size() && classIndex > 0 && classIndex - 1 < orderedTypes.size()) {
                classIndex -= 1;
            }
            if (classIndex < 0 || classIndex >= orderedTypes.size()) {
                log.debug("Skip detection with unknown class index {}. Known classes={}", classIndex, orderedTypes.size());
                continue;
            }

            double rawX1 = toModelPixels(valueAt(logits, transposed, row, 0), normalizedCoords);
            double rawY1 = toModelPixels(valueAt(logits, transposed, row, 1), normalizedCoords);
            double rawX2 = toModelPixels(valueAt(logits, transposed, row, 2), normalizedCoords);
            double rawY2 = toModelPixels(valueAt(logits, transposed, row, 3), normalizedCoords);

            int x1 = clamp((int) Math.round((rawX1 - preparedImage.padX()) / preparedImage.scale()), 0, preparedImage.origWidth());
            int y1 = clamp((int) Math.round((rawY1 - preparedImage.padY()) / preparedImage.scale()), 0, preparedImage.origHeight());
            int x2 = clamp((int) Math.round((rawX2 - preparedImage.padX()) / preparedImage.scale()), 0, preparedImage.origWidth());
            int y2 = clamp((int) Math.round((rawY2 - preparedImage.padY()) / preparedImage.scale()), 0, preparedImage.origHeight());

            if (x2 <= x1 || y2 <= y1) {
                continue;
            }

            CatalogService.DefectDictionaryItem defectType = orderedTypes.get(classIndex);
            candidates.add(new Candidate(
                    defectType.defectCode(),
                    defectType.defectName(),
                    defectDictionary.getOrDefault(defectType.defectCode(), defectType).displayColor(),
                    score,
                    x1,
                    y1,
                    x2,
                    y2
            ));
        }

        log.info("PostProcess detected output: {} raw candidates (threshold={}), running NMS with iou={}",
                candidates.size(), confThreshold, doubleConfig("iou_threshold", qcProperties.getInference().getIouThreshold()));
        List<InferenceModels.Detection> detections = applyNms(candidates, doubleConfig("iou_threshold", qcProperties.getInference().getIouThreshold()));
        log.info("PostProcess detected output: {} detections after NMS", detections.size());
        return detections;
    }

    private double valueAt(float[][] logits, boolean transposed, int row, int column) {
        return transposed ? logits[column][row] : logits[row][column];
    }

    private boolean hasNormalizedCoordinates(float[][] logits, boolean transposed, int rows) {
        double maxCoord = 0;
        for (int row = 0; row < Math.min(rows, 20); row++) {
            for (int column = 0; column < 4; column++) {
                maxCoord = Math.max(maxCoord, Math.abs(valueAt(logits, transposed, row, column)));
            }
        }
        return maxCoord <= 1.5;
    }

    private double toModelPixels(double value, boolean normalizedCoords) {
        return normalizedCoords ? value * MODEL_SIZE : value;
    }

    private List<InferenceModels.Detection> applyNms(List<Candidate> candidates, double iouThreshold) {
        Map<String, List<Candidate>> grouped = new java.util.LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            grouped.computeIfAbsent(candidate.defectCode(), key -> new ArrayList<>()).add(candidate);
        }

        List<InferenceModels.Detection> results = new ArrayList<>();
        for (List<Candidate> group : grouped.values()) {
            group.sort(Comparator.comparingDouble(Candidate::confidence).reversed());
            List<Candidate> kept = new ArrayList<>();
            while (!group.isEmpty()) {
                Candidate current = group.remove(0);
                kept.add(current);
                group.removeIf(candidate -> iou(current, candidate) > iouThreshold);
            }
            for (Candidate candidate : kept) {
                int width = candidate.x2() - candidate.x1();
                int height = candidate.y2() - candidate.y1();
                results.add(new InferenceModels.Detection(
                        candidate.defectCode(),
                        candidate.defectName(),
                        candidate.displayColor(),
                        candidate.confidence(),
                        candidate.x1(),
                        candidate.y1(),
                        candidate.x2(),
                        candidate.y2(),
                        width,
                        height,
                        width * height
                ));
            }
        }
        results.sort(Comparator.comparingDouble(InferenceModels.Detection::confidence).reversed());
        return results;
    }

    private BufferedImage drawDetections(BufferedImage originalImage, List<InferenceModels.Detection> detections) {
        BufferedImage resultImage = new BufferedImage(originalImage.getWidth(), originalImage.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resultImage.createGraphics();
        graphics.drawImage(originalImage, 0, 0, null);
        graphics.setStroke(new BasicStroke(Math.max(2, Math.round(originalImage.getWidth() / 320.0F))));
        graphics.setFont(new Font("Microsoft YaHei", Font.BOLD, Math.max(16, originalImage.getWidth() / 60)));
        for (InferenceModels.Detection detection : detections) {
            Color color = parseColor(detection.displayColor());
            graphics.setColor(color);
            graphics.drawRect(detection.x1(), detection.y1(), detection.width(), detection.height());
            String label = detection.defectCode() + " " + String.format("%.2f", detection.confidence());
            FontMetrics metrics = graphics.getFontMetrics();
            int textWidth = metrics.stringWidth(label) + 14;
            int textHeight = metrics.getHeight() + 6;
            int labelY = Math.max(0, detection.y1() - textHeight);
            graphics.fillRect(detection.x1(), labelY, textWidth, textHeight);
            graphics.setColor(Color.BLACK);
            graphics.drawString(label, detection.x1() + 7, labelY + metrics.getAscent() + 2);
        }
        graphics.dispose();
        return resultImage;
    }

    private Color parseColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException exception) {
            return Color.CYAN;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double doubleConfig(String code, double defaultValue) {
        try {
            return Double.parseDouble(catalogService.getConfigValue(code, String.valueOf(defaultValue)));
        } catch (Exception exception) {
            return defaultValue;
        }
    }

    private double sigmoid(double x) {
        // Clip extreme values to avoid overflow
        if (x >= 20.0) { return 1.0; }
        if (x <= -20.0) { return 0.0; }
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double iou(Candidate first, Candidate second) {
        int intersectionX1 = Math.max(first.x1(), second.x1());
        int intersectionY1 = Math.max(first.y1(), second.y1());
        int intersectionX2 = Math.min(first.x2(), second.x2());
        int intersectionY2 = Math.min(first.y2(), second.y2());
        if (intersectionX2 <= intersectionX1 || intersectionY2 <= intersectionY1) {
            return 0;
        }
        double intersectionArea = (double) (intersectionX2 - intersectionX1) * (intersectionY2 - intersectionY1);
        double firstArea = (double) (first.x2() - first.x1()) * (first.y2() - first.y1());
        double secondArea = (double) (second.x2() - second.x1()) * (second.y2() - second.y1());
        return intersectionArea / (firstArea + secondArea - intersectionArea);
    }

    private record PreparedImage(
            FloatBuffer tensorBuffer,
            int origWidth,
            int origHeight,
            double scale,
            int padX,
            int padY,
            int preprocessTimeMs
    ) {
    }

    private record Candidate(
            String defectCode,
            String defectName,
            String displayColor,
            double confidence,
            int x1,
            int y1,
            int x2,
            int y2
    ) {
    }
}
