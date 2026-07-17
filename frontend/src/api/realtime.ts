import http, { unwrap } from "./http";
import type { DetectionItem } from "./records";

export interface RealtimeStatus {
  cameraName: string;
  runtimeStatus: string;
  previewFps: number;
  inferenceFps: number;
  latencyMs: number;
  providerStrategy: string;
  modelPath: string;
  confidenceThreshold: number;
  iouThreshold: number;
  continuousHitFrames: number;
  targetInferenceFps: number;
  evidenceImageCount: number;
}

export interface RealtimeEvent {
  type: string;
  title: string;
  message: string;
  tone: string;
  taskNo: string | null;
  recordNo: string | null;
  eventTime: string;
}

export interface RealtimeEvidence {
  recordNo: string;
  fileType: string;
  relativePath: string;
  createTime: string;
}

export interface RealtimeConfigPayload {
  confidenceThreshold?: number;
  iouThreshold?: number;
  continuousHitFrames?: number;
  targetInferenceFps?: number;
  evidenceImageCount?: number;
}

export interface RealtimeMetricsPayload {
  totalFrameCount: number;
  defectFrameCount: number;
  totalDefectCount: number;
  previewFps: number;
  inferenceFps: number;
  latencyMs: number;
  providerStrategy: string;
}

export interface RealtimeClientEventPayload {
  modelSha256: string;
  frameIndex: number;
  imageWidth: number;
  imageHeight: number;
  preprocessTimeMs: number;
  inferenceTimeMs: number;
  postprocessTimeMs: number;
  providerStrategy: string;
  detections: DetectionItem[];
}

export interface RealtimeEventAccepted {
  accepted: boolean;
  duplicate: boolean;
  recordNo: string | null;
}

export function fetchRealtimeStatus(): Promise<RealtimeStatus> {
  return unwrap<RealtimeStatus>(http.get("/inspection/realtime/status"));
}

export function startRealtime(): Promise<RealtimeStatus> {
  return unwrap<RealtimeStatus>(http.post("/inspection/realtime/start"));
}

export function stopRealtime(): Promise<RealtimeStatus> {
  return unwrap<RealtimeStatus>(http.post("/inspection/realtime/stop"));
}

export function updateRealtimeConfig(payload: RealtimeConfigPayload): Promise<RealtimeStatus> {
  return unwrap<RealtimeStatus>(http.put("/inspection/realtime/config", payload));
}

export function fetchRealtimeEvents(): Promise<RealtimeEvent[]> {
  return unwrap<RealtimeEvent[]>(http.get("/inspection/realtime/events"));
}

export function fetchRealtimeEvidence(): Promise<RealtimeEvidence[]> {
  return unwrap<RealtimeEvidence[]>(http.get("/inspection/realtime/evidence"));
}

export function clearRealtimeHistory(): Promise<void> {
  return unwrap<void>(http.delete("/inspection/realtime/history"));
}

export function updateRealtimeMetrics(payload: RealtimeMetricsPayload): Promise<RealtimeStatus> {
  return unwrap<RealtimeStatus>(http.put("/inspection/realtime/metrics", payload));
}

export function submitRealtimeClientEvent(
  payload: RealtimeClientEventPayload,
  sourceFiles: File[],
  resultFiles: File[],
): Promise<RealtimeEventAccepted> {
  const formData = new FormData();
  formData.append(
    "payload",
    new Blob([JSON.stringify(payload)], { type: "application/json" }),
    "payload.json",
  );
  sourceFiles.forEach((file) => formData.append("sourceFiles", file));
  resultFiles.forEach((file) => formData.append("resultFiles", file));
  return unwrap<RealtimeEventAccepted>(http.post("/inspection/realtime/client-events", formData));
}
