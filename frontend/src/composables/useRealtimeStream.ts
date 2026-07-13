/**
 * Shared realtime inspection pipeline — singleton.
 *
 * Both DashboardPage and RealtimePage import the same instance, so there is
 * exactly ONE webcam stream, ONE WebSocket, and ONE frame-send loop.
 *
 * Usage:
 *   import { useRealtimeStream } from "@/composables/useRealtimeStream";
 *   const stream = useRealtimeStream();
 *   // All pages get the SAME refs — no duplication.
 */

import { ref } from "vue";
import {
  fetchRealtimeStatus,
  fetchRealtimeEvents,
  fetchRealtimeEvidence,
  clearRealtimeHistory,
  startRealtime,
  stopRealtime,
  type RealtimeStatus,
  type RealtimeEvent,
  type RealtimeEvidence,
} from "@/api/realtime";
import type { DetectionItem } from "@/api/records";

// ---- frame-result payload shape ----
export interface FrameResultPayload {
  type: string;
  renderMode: string;
  runtimeStatus: string;
  providerStrategy: string;
  previewFps: number;
  inferenceFps: number;
  latencyMs: number;
  resultJudge: string;
  confidenceScore: number | null;
  defectType: string | null;
  resultSummary: string;
  imageWidth: number;
  imageHeight: number;
  detections: DetectionItem[];
}

export interface RealtimePerformanceSample {
  label: string;
  fps: number;
  latency: number;
  time: string;
}

const SAMPLE_INTERVAL_MS = 160;
const FRAME_POLL_MS = 220;
const API_POLL_MS = 4000;

// --------------- module-level singleton state ---------------
const running = ref(false);
const loading = ref(false);

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

const frameSrc = ref(`/api/inspection/realtime/frame?t=${Date.now()}`);
const selectedCameraDeviceId = ref(localStorage.getItem("ruikang-camera-device-id") || "");

// Internal — capture elements (hidden canvas for frame capture; video may be external)
let captureVideo: HTMLVideoElement | null = null;
let captureCanvas: HTMLCanvasElement | null = null;
let externalPreviewVideo: HTMLVideoElement | null = null;

let mediaStream: MediaStream | null = null;
let socket: WebSocket | null = null;
let sendTimer: number | undefined;
let framePollTimer: number | undefined;
let apiPollTimer: number | undefined;
let sendingFrame = false;
let startedByUs = false;

// ---- helpers ----
function pushPerformanceSample(fps?: number, latency?: number) {
  const normalizedFps = Number(fps ?? 0);
  const normalizedLatency = Number(latency ?? 0);
  if (!Number.isFinite(normalizedFps) || normalizedFps <= 0) return;
  const now = new Date();
  performanceSamples.value = [
    ...performanceSamples.value,
    {
      label: now.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }),
      fps: Math.round(normalizedFps),
      latency: Math.round(Number.isFinite(normalizedLatency) ? normalizedLatency : 0),
      time: now.toISOString(),
    },
  ].slice(-5);
}

function pushSessionAlert(item: RealtimeEvent) {
  const key = item.recordNo || `${item.eventTime}-${item.title}`;
  sessionAlerts.value = [
    item,
    ...sessionAlerts.value.filter((existing) => (existing.recordNo || `${existing.eventTime}-${existing.title}`) !== key),
  ].slice(0, 10);
}

function ensureCaptureElements(externalVideo?: HTMLVideoElement | null) {
  if (!captureVideo) {
    const v = document.createElement("video");
    v.setAttribute("autoplay", "");
    v.setAttribute("muted", "");
    v.setAttribute("playsinline", "");
    v.style.display = "none";
    document.body.appendChild(v);
    captureVideo = v;
  }
  if (externalVideo) {
    externalPreviewVideo = externalVideo;
  }
  if (!captureCanvas) {
    const c = document.createElement("canvas");
    c.style.display = "none";
    document.body.appendChild(c);
    captureCanvas = c;
  }
}

function destroyCaptureElements() {
  if (captureVideo) { captureVideo.remove(); }
  if (captureCanvas) { captureCanvas.remove(); }
  if (externalPreviewVideo) externalPreviewVideo.srcObject = null;
  captureVideo = null;
  captureCanvas = null;
  externalPreviewVideo = null;
}

// ---- webcam ----
async function ensureWebcam(externalVideo?: HTMLVideoElement | null) {
  if (!mediaStream) {
    const deviceId = selectedCameraDeviceId.value;
    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: deviceId
          ? { deviceId: { exact: deviceId }, width: { ideal: 1280 }, height: { ideal: 720 } }
          : { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: "environment" },
        audio: false,
      });
    } catch (error) {
      if (!deviceId) {
        throw error;
      }
      localStorage.removeItem("ruikang-camera-device-id");
      selectedCameraDeviceId.value = "";
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: "environment" },
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
  if (mediaStream) { mediaStream.getTracks().forEach((t) => t.stop()); mediaStream = null; }
  if (captureVideo) captureVideo.srcObject = null;
  if (externalPreviewVideo) externalPreviewVideo.srcObject = null;
}

// ---- WebSocket ----
function connectSocket() {
  if (socket && socket.readyState === WebSocket.OPEN) return;
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  socket = new WebSocket(`${protocol}://${window.location.host}/ws/realtime/status`);
  socket.binaryType = "arraybuffer";
  socket.onmessage = (ev) => {
    try {
      const raw = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data);
      const payload = JSON.parse(raw);
      if (payload.type === "frame-result") {
        const p = payload as FrameResultPayload;
        if (status.value) {
          status.value = {
            ...status.value,
            runtimeStatus: p.runtimeStatus ?? status.value.runtimeStatus,
            providerStrategy: p.providerStrategy ?? status.value.providerStrategy,
            previewFps: p.previewFps ?? status.value.previewFps,
            inferenceFps: p.inferenceFps ?? status.value.inferenceFps,
            latencyMs: p.latencyMs ?? status.value.latencyMs,
          };
        }
        latestResultJudge.value = p.resultJudge;
        latestResultSummary.value = p.resultSummary;
        latestDefectType.value = p.defectType;
        latestConfidence.value = p.confidenceScore;
        latestDetections.value = p.detections ?? [];
        latestImageWidth.value = p.imageWidth;
        latestImageHeight.value = p.imageHeight;
        pushPerformanceSample(p.inferenceFps, p.latencyMs);
      } else if (payload.type === "status" && status.value) {
        status.value = {
          ...status.value,
          runtimeStatus: payload.runtimeStatus ?? status.value.runtimeStatus,
          previewFps: payload.previewFps ?? status.value.previewFps,
          inferenceFps: payload.inferenceFps ?? status.value.inferenceFps,
          latencyMs: payload.latencyMs ?? status.value.latencyMs,
          providerStrategy: payload.providerStrategy ?? status.value.providerStrategy,
        };
        pushPerformanceSample(payload.inferenceFps, payload.latencyMs);
      } else if (payload.type && payload.title) {
        const item: RealtimeEvent = {
          type: payload.type,
          title: payload.title,
          message: payload.message ?? "",
          tone: payload.tone ?? "info",
          taskNo: payload.taskNo ?? null,
          recordNo: payload.recordNo ?? null,
          eventTime: payload.eventTime ?? new Date().toISOString(),
        };
        events.value = [item, ...events.value].slice(0, 40);
        if (payload.type === "defect-event") {
          pushSessionAlert(item);
          loadSideData().catch(() => {});
        }
      }
    } catch { /* ignore malformed */ }
  };
  socket.onclose = () => { sendingFrame = false; };
}

// ---- frame capture ----
async function sendFrame() {
  const video = captureVideo;
  const canvas = captureCanvas;
  if (!video || !canvas || !socket || socket.readyState !== WebSocket.OPEN || sendingFrame) return;
  if (video.readyState < 2 || !video.videoWidth || !video.videoHeight) return;

  sendingFrame = true;
  const targetW = Math.min(video.videoWidth, 960);
  const scale = targetW / video.videoWidth;
  const targetH = Math.max(1, Math.round(video.videoHeight * scale));
  canvas.width = targetW;
  canvas.height = targetH;

  const ctx = canvas.getContext("2d");
  if (!ctx) { sendingFrame = false; return; }
  ctx.drawImage(video, 0, 0, targetW, targetH);

  try {
    const dataUrl = canvas.toDataURL("image/jpeg", 0.7);
    socket.send(JSON.stringify({ type: "frame", frameTime: new Date().toISOString(), frameData: dataUrl }));
  } catch { /* ignore */ }
  sendingFrame = false;
}

// ---- server-processed frame polling ----
function refreshFrameUrl() {
  frameSrc.value = `/api/inspection/realtime/frame?t=${Date.now()}`;
}

// ---- API polling ----
async function loadSideData() {
  try {
    events.value = await fetchRealtimeEvents();
    evidence.value = await fetchRealtimeEvidence();
  } catch { /* ignore */ }
}

async function loadStatus() {
  try {
    const s = await fetchRealtimeStatus();
    status.value = s;
    if (running.value) {
      pushPerformanceSample(s.inferenceFps, s.latencyMs);
    }
  } catch { /* ignore */ }
}

async function refresh() {
  await Promise.all([
    loadStatus(),
    loadSideData(),
  ]);
}

async function clearHistory() {
  await clearRealtimeHistory();
  events.value = [];
  evidence.value = [];
  sessionAlerts.value = [];
}

async function attachVideo(externalVideo?: HTMLVideoElement | null) {
  if (!running.value || !mediaStream) {
    return;
  }
  ensureCaptureElements(externalVideo);
  await ensureWebcam(externalVideo);
}

async function setCameraDevice(deviceId: string) {
  selectedCameraDeviceId.value = deviceId;
  if (deviceId) {
    localStorage.setItem("ruikang-camera-device-id", deviceId);
  } else {
    localStorage.removeItem("ruikang-camera-device-id");
  }
  if (running.value) {
    stopWebcam();
    await ensureWebcam(externalPreviewVideo);
  }
}

function startTimers() {
  if (!sendTimer) {
    sendTimer = window.setInterval(() => { sendFrame().catch(() => {}); }, SAMPLE_INTERVAL_MS);
  }
  refreshFrameUrl();
  if (!framePollTimer) {
    framePollTimer = window.setInterval(refreshFrameUrl, FRAME_POLL_MS);
  }
  if (!apiPollTimer) {
    apiPollTimer = window.setInterval(() => {
      loadStatus().catch(() => {});
      loadSideData().catch(() => {});
    }, API_POLL_MS);
  }
}

async function attachRealtimePipeline(externalVideo?: HTMLVideoElement | null) {
  ensureCaptureElements(externalVideo);
  await ensureWebcam(externalVideo);
  connectSocket();
  startTimers();
  running.value = true;
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
    return "已保存的摄像头不可用，已尝试切回默认摄像头，请重试";
  }
  return error.message || "摄像头启动失败";
}

// ---- start / stop ----
async function start(externalVideo?: HTMLVideoElement | null) {
  if (running.value) {
    await attachVideo(externalVideo);
    return;
  }
  loading.value = true;
  try {
    sessionAlerts.value = [];
    const s = await startRealtime();
    status.value = s;

    await attachRealtimePipeline(externalVideo);
    startedByUs = true;
  } catch (e) {
    // If backend is already running, just hook into existing session
    try {
      const s = await fetchRealtimeStatus();
      status.value = s;
      if (s.runtimeStatus === "RUNNING") {
        sessionAlerts.value = [];
        await attachRealtimePipeline(externalVideo);
        startedByUs = false;
        return;
      }
    } catch { /* surface the original failure below */ }

    stopWebcam();
    destroyCaptureElements();
    try { await stopRealtime(); } catch { /* ignore rollback failure */ }
    throw new Error(normalizeCameraError(e));
  } finally {
    loading.value = false;
  }
}

async function stop() {
  if (sendTimer) { window.clearInterval(sendTimer); sendTimer = undefined; }
  if (framePollTimer) { window.clearInterval(framePollTimer); framePollTimer = undefined; }
  if (apiPollTimer) { window.clearInterval(apiPollTimer); apiPollTimer = undefined; }

  socket?.close();
  socket = null;
  stopWebcam();

  try { await stopRealtime(); } catch { /* ignore */ }
  startedByUs = false;

  running.value = false;
  sessionAlerts.value = [];
  latestDetections.value = [];
  latestResultJudge.value = "IDLE";
  latestResultSummary.value = "等待启动...";
  latestDefectType.value = null;
  latestConfidence.value = null;
  latestImageWidth.value = 0;
  latestImageHeight.value = 0;
  performanceSamples.value = [];
  destroyCaptureElements();
}

// ---- composable entry ----
export function useRealtimeStream() {
  // Always return the same singleton refs — all pages share one pipeline.
  return {
    // state
    running,
    loading,
    status,
    events,
    evidence,
    sessionAlerts,
    // latest frame result
    latestResultJudge,
    latestResultSummary,
    latestDefectType,
    latestConfidence,
    latestDetections,
    latestImageWidth,
    latestImageHeight,
    performanceSamples,
    // frame display URL
    frameSrc,
    selectedCameraDeviceId,
    // actions
    refresh,
    clearHistory,
    attachVideo,
    setCameraDevice,
    start,
    stop,
  };
}
