/**
 * Shared browser-side realtime inspection pipeline.
 *
 * DashboardPage and RealtimePage use this module-level singleton so the
 * application owns one camera stream, one inference worker and one loop.
 */

import { ref } from "vue";
import {
  clearRealtimeHistory,
  fetchRealtimeEvents,
  fetchRealtimeEvidence,
  fetchRealtimeStatus,
  startRealtime,
  stopRealtime,
  submitRealtimeClientEvent,
  updateRealtimeMetrics,
  type RealtimeEvent,
  type RealtimeEvidence,
  type RealtimeStatus,
} from "@/api/realtime";
import type { DetectionItem } from "@/api/records";
import { browserInferenceClient } from "@/inference/browserInferenceClient";
import { RealtimeHitTracker } from "@/inference/realtimeHitTracker";
import { captureRealtimeEvidence } from "@/inference/rendering";
import type {
  BrowserInferenceManifest,
  BrowserInferenceResult,
  InferenceReady,
} from "@/inference/types";

export interface RealtimePerformanceSample {
  label: string;
  fps: number;
  latency: number;
  time: string;
}

type EvidenceSnapshot = {
  sourceBlob: Blob;
  resultBlob: Blob;
};

const API_POLL_MS = 4_000;
const METRICS_INTERVAL_MS = 1_000;
const DEFAULT_TARGET_INFERENCE_FPS = 60;

const running = ref(false);
const loading = ref(false);
const modelLoadingStage = ref("未加载");

const status = ref<RealtimeStatus | null>(null);
const events = ref<RealtimeEvent[]>([]);
const evidence = ref<RealtimeEvidence[]>([]);
const sessionAlerts = ref<RealtimeEvent[]>([]);

const latestResultJudge = ref("IDLE");
const latestResultSummary = ref("等待启动...");
const latestDefectType = ref<string | null>(null);
const latestConfidence = ref<number | null>(null);
const latestDetections = ref<DetectionItem[]>([]);
const latestImageWidth = ref(0);
const latestImageHeight = ref(0);
const performanceSamples = ref<RealtimePerformanceSample[]>([]);

const selectedCameraDeviceId = ref(localStorage.getItem("ruikang-camera-device-id") || "");

let captureVideo: HTMLVideoElement | null = null;
let externalPreviewVideo: HTMLVideoElement | null = null;
let mediaStream: MediaStream | null = null;

let activeManifest: BrowserInferenceManifest | null = null;
let activeReadyState: InferenceReady | null = null;
let animationFrameId: number | undefined;
let videoFrameCallbackId: number | undefined;
let metricsTimer: number | undefined;
let apiPollTimer: number | undefined;
let inferenceInFlight = false;
let eventUploadInFlight = false;
let inferenceFailed = false;
let runGeneration = 0;
let lastInferenceStartedAt = 0;

let totalFrameCount = 0;
let defectFrameCount = 0;
let totalDefectCount = 0;
let frameIndex = 0;
let previewFramesInWindow = 0;
let inferenceFramesInWindow = 0;
let latencySumInWindow = 0;
let metricWindowStartedAt = performance.now();
let measuredPreviewFps = 0;
let measuredInferenceFps = 0;
let measuredLatencyMs = 0;

let evidenceSnapshots: EvidenceSnapshot[] = [];
const hitTracker = new RealtimeHitTracker();

function pushPerformanceSample(fps: number, latency: number) {
  if (!Number.isFinite(fps) || fps <= 0) return;
  const now = new Date();
  performanceSamples.value = [
    ...performanceSamples.value,
    {
      label: now.toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      }),
      fps: Math.round(fps),
      latency: Math.round(Number.isFinite(latency) ? latency : 0),
      time: now.toISOString(),
    },
  ].slice(-5);
}

function pushSessionAlert(item: RealtimeEvent) {
  const key = item.recordNo || `${item.eventTime}-${item.title}`;
  sessionAlerts.value = [
    item,
    ...sessionAlerts.value.filter(
      (existing) => (existing.recordNo || `${existing.eventTime}-${existing.title}`) !== key,
    ),
  ].slice(0, 10);
}

function ensureCaptureVideo(externalVideo?: HTMLVideoElement | null) {
  if (!captureVideo) {
    captureVideo = document.createElement("video");
    captureVideo.autoplay = true;
    captureVideo.muted = true;
    captureVideo.playsInline = true;
    captureVideo.style.display = "none";
    document.body.appendChild(captureVideo);
  }
  if (externalVideo) externalPreviewVideo = externalVideo;
}

function destroyCaptureVideo() {
  captureVideo?.remove();
  if (externalPreviewVideo) externalPreviewVideo.srcObject = null;
  captureVideo = null;
  externalPreviewVideo = null;
}

async function ensureWebcam(externalVideo?: HTMLVideoElement | null) {
  if (!mediaStream) {
    const deviceId = selectedCameraDeviceId.value;
    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: deviceId
          ? {
              deviceId: { exact: deviceId },
              width: { ideal: 1280 },
              height: { ideal: 720 },
              frameRate: { ideal: 60 },
            }
          : {
              width: { ideal: 1280 },
              height: { ideal: 720 },
              frameRate: { ideal: 60 },
              facingMode: "environment",
            },
        audio: false,
      });
    } catch (error) {
      if (!deviceId) throw error;
      localStorage.removeItem("ruikang-camera-device-id");
      selectedCameraDeviceId.value = "";
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: {
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 60 },
          facingMode: "environment",
        },
        audio: false,
      });
    }
  }

  if (captureVideo && captureVideo.srcObject !== mediaStream) {
    captureVideo.srcObject = mediaStream;
    await captureVideo.play();
  }
  const previewVideo = externalVideo ?? externalPreviewVideo;
  if (previewVideo && previewVideo.srcObject !== mediaStream) {
    previewVideo.srcObject = mediaStream;
    await previewVideo.play();
    externalPreviewVideo = previewVideo;
  }
}

function stopWebcam() {
  mediaStream?.getTracks().forEach((track) => track.stop());
  mediaStream = null;
  if (captureVideo) captureVideo.srcObject = null;
  if (externalPreviewVideo) externalPreviewVideo.srcObject = null;
}

function updateLatestResult(result: BrowserInferenceResult) {
  const detections = result.detections as DetectionItem[];
  const topDetection = detections.reduce<DetectionItem | null>(
    (current, item) => (!current || item.confidence > current.confidence ? item : current),
    null,
  );

  latestDetections.value = detections;
  latestImageWidth.value = result.imageWidth;
  latestImageHeight.value = result.imageHeight;
  latestConfidence.value = topDetection?.confidence ?? null;
  latestDefectType.value = topDetection?.defectName ?? null;
  latestResultJudge.value = detections.length ? "DEFECT" : "PASS";
  latestResultSummary.value = detections.length
    ? `检测到 ${detections.length} 处缺陷，主要类型：${topDetection?.defectName ?? "未知"}`
    : "当前画面未发现缺陷";

  if (status.value) {
    status.value = {
      ...status.value,
      runtimeStatus: "RUNNING",
      providerStrategy: result.providerStrategy,
      latencyMs: Math.round(
        result.preprocessTimeMs + result.inferenceTimeMs + result.postprocessTimeMs,
      ),
    };
  }
}

async function persistRealtimeEvent(
  result: BrowserInferenceResult,
  snapshots: EvidenceSnapshot[],
  persistedFrameIndex: number,
) {
  if (!activeManifest || !snapshots.length || eventUploadInFlight) return;
  eventUploadInFlight = true;
  try {
    const timestamp = Date.now();
    const sourceFiles = snapshots.map(
      (snapshot, index) =>
        new File([snapshot.sourceBlob], `realtime-${timestamp}-${index + 1}-source.jpg`, {
          type: "image/jpeg",
        }),
    );
    const resultFiles = snapshots.map(
      (snapshot, index) =>
        new File([snapshot.resultBlob], `realtime-${timestamp}-${index + 1}-result.jpg`, {
          type: "image/jpeg",
        }),
    );
    const response = await submitRealtimeClientEvent(
      {
        modelSha256: activeManifest.sha256,
        frameIndex: persistedFrameIndex,
        imageWidth: result.imageWidth,
        imageHeight: result.imageHeight,
        preprocessTimeMs: Math.round(result.preprocessTimeMs),
        inferenceTimeMs: Math.round(result.inferenceTimeMs),
        postprocessTimeMs: Math.round(result.postprocessTimeMs),
        providerStrategy: result.providerStrategy,
        detections: result.detections,
      },
      sourceFiles,
      resultFiles,
    );

    if (response.accepted && !response.duplicate) {
      const topDetection = result.detections.reduce(
        (current, item) => (!current || item.confidence > current.confidence ? item : current),
        result.detections[0],
      );
      const item: RealtimeEvent = {
        type: "defect-event",
        title: "检测到面料缺陷",
        message: `${topDetection?.defectName ?? "未知缺陷"}，共 ${result.detections.length} 处`,
        tone: "danger",
        taskNo: null,
        recordNo: response.recordNo,
        eventTime: new Date().toISOString(),
      };
      events.value = [item, ...events.value].slice(0, 40);
      pushSessionAlert(item);
      await loadSideData();
    }
  } catch (error) {
    hitTracker.reset();
    console.error("实时质检事件保存失败", error);
  } finally {
    eventUploadInFlight = false;
  }
}

async function handleInferenceResult(result: BrowserInferenceResult, generation: number) {
  if (!running.value || generation !== runGeneration) return;

  frameIndex += 1;
  totalFrameCount += 1;
  inferenceFramesInWindow += 1;
  const latency = result.preprocessTimeMs + result.inferenceTimeMs + result.postprocessTimeMs;
  latencySumInWindow += latency;
  updateLatestResult(result);

  const hasDefect = result.detections.length > 0;
  if (!hasDefect) {
    hitTracker.consume(false, status.value?.continuousHitFrames ?? 1);
    evidenceSnapshots = [];
    return;
  }

  defectFrameCount += 1;
  totalDefectCount += result.detections.length;
  const trackerState = hitTracker.consume(
    true,
    status.value?.continuousHitFrames ?? 1,
  );

  const video = captureVideo;
  if (!video) return;
  try {
    const snapshot = await captureRealtimeEvidence(video, result.detections);
    const evidenceLimit = Math.max(1, status.value?.evidenceImageCount ?? 3);
    evidenceSnapshots = [...evidenceSnapshots, snapshot].slice(-evidenceLimit);
    if (trackerState.shouldPersist && !eventUploadInFlight) {
      const snapshotsToPersist = [...evidenceSnapshots];
      void persistRealtimeEvent(result, snapshotsToPersist, frameIndex);
    }
  } catch (error) {
    if (trackerState.shouldPersist) hitTracker.reset();
    console.error("实时质检证据图生成失败", error);
  }
}

async function inferCurrentFrame(generation: number) {
  const video = captureVideo;
  if (
    !video ||
    !running.value ||
    generation !== runGeneration ||
    video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA ||
    !video.videoWidth ||
    !video.videoHeight
  ) {
    return;
  }

  inferenceInFlight = true;
  try {
    const bitmap = await createImageBitmap(video);
    const result = await browserInferenceClient.infer(
      bitmap,
      status.value?.confidenceThreshold ?? activeManifest?.confidenceThreshold ?? 0.25,
      status.value?.iouThreshold ?? activeManifest?.iouThreshold ?? 0.45,
    );
    await handleInferenceResult(result, generation);
  } catch (error) {
    if (generation !== runGeneration) return;
    inferenceFailed = true;
    latestResultJudge.value = "ERROR";
    latestResultSummary.value = error instanceof Error ? error.message : "浏览器模型推理失败";
    if (status.value) status.value = { ...status.value, runtimeStatus: "ERROR" };
    console.error("浏览器实时推理失败", error);
  } finally {
    inferenceInFlight = false;
  }
}

function runInferenceLoop(generation: number) {
  if (!running.value || generation !== runGeneration) return;
  const now = performance.now();
  const targetFps = Math.min(
    60,
    Math.max(1, status.value?.targetInferenceFps ?? DEFAULT_TARGET_INFERENCE_FPS),
  );
  if (
    !inferenceFailed &&
    !inferenceInFlight &&
    now - lastInferenceStartedAt >= 1_000 / targetFps
  ) {
    lastInferenceStartedAt = now;
    void inferCurrentFrame(generation);
  }
  animationFrameId = window.requestAnimationFrame(() => runInferenceLoop(generation));
}

function startPreviewFrameCounter(generation: number) {
  const video = captureVideo;
  if (!video) return;
  const requestVideoFrame = video.requestVideoFrameCallback?.bind(video);
  if (!requestVideoFrame) {
    measuredPreviewFps = mediaStream?.getVideoTracks()[0]?.getSettings().frameRate ?? 0;
    return;
  }
  const countFrame: VideoFrameRequestCallback = () => {
    if (!running.value || generation !== runGeneration || !captureVideo) return;
    previewFramesInWindow += 1;
    videoFrameCallbackId = requestVideoFrame(countFrame);
  };
  videoFrameCallbackId = requestVideoFrame(countFrame);
}

function updateMeasuredMetrics() {
  const now = performance.now();
  const elapsedSeconds = Math.max(0.001, (now - metricWindowStartedAt) / 1_000);
  if (previewFramesInWindow > 0) {
    measuredPreviewFps = previewFramesInWindow / elapsedSeconds;
  }
  measuredInferenceFps = inferenceFramesInWindow / elapsedSeconds;
  measuredLatencyMs = inferenceFramesInWindow
    ? latencySumInWindow / inferenceFramesInWindow
    : measuredLatencyMs;
  previewFramesInWindow = 0;
  inferenceFramesInWindow = 0;
  latencySumInWindow = 0;
  metricWindowStartedAt = now;
}

async function sendMetrics() {
  if (!running.value) return;
  updateMeasuredMetrics();
  const providerStrategy = activeReadyState?.providerStrategy ?? "BROWSER";
  try {
    const remoteStatus = await updateRealtimeMetrics({
      totalFrameCount,
      defectFrameCount,
      totalDefectCount,
      previewFps: Math.round(measuredPreviewFps),
      inferenceFps: Math.round(measuredInferenceFps),
      latencyMs: Math.round(measuredLatencyMs),
      providerStrategy,
    });
    status.value = {
      ...remoteStatus,
      runtimeStatus: inferenceFailed ? "ERROR" : remoteStatus.runtimeStatus,
    };
    pushPerformanceSample(measuredInferenceFps, measuredLatencyMs);
  } catch (error) {
    console.error("实时质检指标同步失败", error);
  }
}

async function loadSideData() {
  try {
    [events.value, evidence.value] = await Promise.all([
      fetchRealtimeEvents(),
      fetchRealtimeEvidence(),
    ]);
  } catch {
    // Keep the latest local state when a periodic refresh fails.
  }
}

async function loadStatus() {
  try {
    status.value = await fetchRealtimeStatus();
  } catch {
    // The page can retain the previous state during a temporary network error.
  }
}

async function refresh() {
  await Promise.all([loadStatus(), loadSideData()]);
}

async function clearHistory() {
  await clearRealtimeHistory();
  events.value = [];
  evidence.value = [];
  sessionAlerts.value = [];
}

async function attachVideo(externalVideo?: HTMLVideoElement | null) {
  if (!running.value || !mediaStream) return;
  ensureCaptureVideo(externalVideo);
  await ensureWebcam(externalVideo);
}

async function setCameraDevice(deviceId: string) {
  selectedCameraDeviceId.value = deviceId;
  if (deviceId) localStorage.setItem("ruikang-camera-device-id", deviceId);
  else localStorage.removeItem("ruikang-camera-device-id");

  if (running.value) {
    stopWebcam();
    ensureCaptureVideo(externalPreviewVideo);
    await ensureWebcam(externalPreviewVideo);
  }
}

function startTimers(generation: number) {
  metricsTimer = window.setInterval(() => void sendMetrics(), METRICS_INTERVAL_MS);
  apiPollTimer = window.setInterval(() => void loadSideData(), API_POLL_MS);
  startPreviewFrameCounter(generation);
  animationFrameId = window.requestAnimationFrame(() => runInferenceLoop(generation));
}

function stopTimers() {
  if (animationFrameId !== undefined) window.cancelAnimationFrame(animationFrameId);
  if (
    videoFrameCallbackId !== undefined &&
    captureVideo?.cancelVideoFrameCallback
  ) {
    captureVideo.cancelVideoFrameCallback(videoFrameCallbackId);
  }
  if (metricsTimer !== undefined) window.clearInterval(metricsTimer);
  if (apiPollTimer !== undefined) window.clearInterval(apiPollTimer);
  animationFrameId = undefined;
  videoFrameCallbackId = undefined;
  metricsTimer = undefined;
  apiPollTimer = undefined;
}

function resetSessionState() {
  totalFrameCount = 0;
  defectFrameCount = 0;
  totalDefectCount = 0;
  frameIndex = 0;
  previewFramesInWindow = 0;
  inferenceFramesInWindow = 0;
  latencySumInWindow = 0;
  metricWindowStartedAt = performance.now();
  measuredPreviewFps = 0;
  measuredInferenceFps = 0;
  measuredLatencyMs = 0;
  lastInferenceStartedAt = 0;
  evidenceSnapshots = [];
  inferenceFailed = false;
  inferenceInFlight = false;
  eventUploadInFlight = false;
  hitTracker.reset();
  performanceSamples.value = [];
  sessionAlerts.value = [];
}

function normalizeCameraError(error: unknown) {
  if (!(error instanceof DOMException)) {
    return error instanceof Error ? error.message : "摄像头启动失败";
  }
  if (error.name === "NotAllowedError" || error.name === "PermissionDeniedError") {
    return "浏览器未授权摄像头，请允许摄像头权限后重试";
  }
  if (error.name === "NotFoundError" || error.name === "DevicesNotFoundError") {
    return "未找到可用摄像头，请检查摄像头连接";
  }
  if (error.name === "NotReadableError" || error.name === "TrackStartError") {
    return "摄像头被其他程序占用，请关闭占用摄像头的软件后重试";
  }
  if (error.name === "OverconstrainedError") {
    return "已保存的摄像头不可用，请重新选择摄像头";
  }
  return error.message || "摄像头启动失败";
}

async function start(externalVideo?: HTMLVideoElement | null) {
  if (running.value) {
    await attachVideo(externalVideo);
    return;
  }

  loading.value = true;
  resetSessionState();
  runGeneration += 1;
  const generation = runGeneration;
  try {
    modelLoadingStage.value = "正在加载浏览器模型";
    const inferenceState = await browserInferenceClient.ensureReady(true);
    activeManifest = inferenceState.manifest;
    activeReadyState = inferenceState.ready;
    modelLoadingStage.value = `模型已就绪（${inferenceState.ready.providerStrategy}）`;

    ensureCaptureVideo(externalVideo);
    await ensureWebcam(externalVideo);
    status.value = await startRealtime();
    status.value = {
      ...status.value,
      providerStrategy: inferenceState.ready.providerStrategy,
      runtimeStatus: "RUNNING",
    };

    latestResultJudge.value = "IDLE";
    latestResultSummary.value = "模型已就绪，等待画面...";
    running.value = true;
    startTimers(generation);
  } catch (error) {
    stopTimers();
    stopWebcam();
    destroyCaptureVideo();
    try {
      await browserInferenceClient.dispose();
    } catch {
      // Worker reset still releases browser-side inference resources.
    }
    activeManifest = null;
    activeReadyState = null;
    try {
      await stopRealtime();
    } catch {
      // Ignore rollback failure and surface the original startup error.
    }
    modelLoadingStage.value = "加载失败";
    throw new Error(normalizeCameraError(error));
  } finally {
    loading.value = false;
  }
}

async function stop() {
  runGeneration += 1;
  stopTimers();
  if (running.value) await sendMetrics();
  running.value = false;
  stopWebcam();
  try {
    status.value = await stopRealtime();
  } catch {
    // Local resources are still released if the stop request fails.
  }
  try {
    await browserInferenceClient.dispose();
  } catch {
    // A timed-out worker is terminated by BrowserInferenceClient.
  }
  activeManifest = null;
  activeReadyState = null;
  modelLoadingStage.value = "未加载";

  latestDetections.value = [];
  latestResultJudge.value = "IDLE";
  latestResultSummary.value = "等待启动...";
  latestDefectType.value = null;
  latestConfidence.value = null;
  latestImageWidth.value = 0;
  latestImageHeight.value = 0;
  sessionAlerts.value = [];
  evidenceSnapshots = [];
  hitTracker.reset();
  destroyCaptureVideo();
}

export function useRealtimeStream() {
  return {
    running,
    loading,
    modelLoadingStage,
    status,
    events,
    evidence,
    sessionAlerts,
    latestResultJudge,
    latestResultSummary,
    latestDefectType,
    latestConfidence,
    latestDetections,
    latestImageWidth,
    latestImageHeight,
    performanceSamples,
    selectedCameraDeviceId,
    refresh,
    clearHistory,
    attachVideo,
    setCameraDevice,
    start,
    stop,
  };
}
