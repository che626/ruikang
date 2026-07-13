package com.ruikang.qc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qc")
public class QcProperties {

    private final Storage storage = new Storage();
    private final Inference inference = new Inference();
    private final Admin admin = new Admin();
    private final Aigc aigc = new Aigc();

    public Storage getStorage() {
        return storage;
    }

    public Inference getInference() {
        return inference;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Aigc getAigc() {
        return aigc;
    }

    public static class Storage {
        private String rootPath = "D:/ruikang-data/qc-files";
        private boolean databaseRelativeOnly = true;

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath;
        }

        public boolean isDatabaseRelativeOnly() {
            return databaseRelativeOnly;
        }

        public void setDatabaseRelativeOnly(boolean databaseRelativeOnly) {
            this.databaseRelativeOnly = databaseRelativeOnly;
        }
    }

    public static class Inference {
        private String modelPath = "models/ruikang.onnx";
        private boolean preferGpu = true;
        private boolean fallbackToCpu = true;
        private double confidenceThreshold = 0.45D;
        private double iouThreshold = 0.42D;
        private int continuousHitFrames = 4;
        private int evidenceImageCount = 3;
        private int targetInferenceFps = 25;

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public boolean isPreferGpu() {
            return preferGpu;
        }

        public void setPreferGpu(boolean preferGpu) {
            this.preferGpu = preferGpu;
        }

        public boolean isFallbackToCpu() {
            return fallbackToCpu;
        }

        public void setFallbackToCpu(boolean fallbackToCpu) {
            this.fallbackToCpu = fallbackToCpu;
        }

        public double getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }

        public double getIouThreshold() {
            return iouThreshold;
        }

        public void setIouThreshold(double iouThreshold) {
            this.iouThreshold = iouThreshold;
        }

        public int getContinuousHitFrames() {
            return continuousHitFrames;
        }

        public void setContinuousHitFrames(int continuousHitFrames) {
            this.continuousHitFrames = continuousHitFrames;
        }

        public int getEvidenceImageCount() {
            return evidenceImageCount;
        }

        public void setEvidenceImageCount(int evidenceImageCount) {
            this.evidenceImageCount = evidenceImageCount;
        }

        public int getTargetInferenceFps() {
            return targetInferenceFps;
        }

        public void setTargetInferenceFps(int targetInferenceFps) {
            this.targetInferenceFps = targetInferenceFps;
        }
    }

    public static class Admin {
        private String username = "admin";
        private String password = "admin123";
        private String displayName = "系统管理员";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    public static class Aigc {
        private String provider = "DeepSeek";
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-v4-pro";
        private int timeoutSeconds = 75;
        private boolean fallbackLocal = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isFallbackLocal() {
            return fallbackLocal;
        }

        public void setFallbackLocal(boolean fallbackLocal) {
            this.fallbackLocal = fallbackLocal;
        }
    }
}
