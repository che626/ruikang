import http, { unwrap } from "./http";

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
