import type { BrowserInferenceManifest, BrowserModelClass } from "@/api/inference";

export type InferenceDetection = {
  defectCode: string;
  defectName: string;
  displayColor: string;
  confidence: number;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  width: number;
  height: number;
  area: number;
};

export type LetterboxTransform = {
  originalWidth: number;
  originalHeight: number;
  modelWidth: number;
  modelHeight: number;
  scale: number;
  padX: number;
  padY: number;
};

export type InferenceReady = {
  providerStrategy: string;
  modelSha256: string;
  initializationTimeMs: number;
  warmupTimeMs: number;
};

export type BrowserInferenceResult = {
  imageWidth: number;
  imageHeight: number;
  preprocessTimeMs: number;
  inferenceTimeMs: number;
  postprocessTimeMs: number;
  providerStrategy: string;
  detections: InferenceDetection[];
};

export type WorkerRequest =
  | { type: "init"; requestId: string; manifest: BrowserInferenceManifest }
  | { type: "infer"; requestId: string; bitmap: ImageBitmap; confidenceThreshold: number; iouThreshold: number }
  | { type: "dispose"; requestId: string };

export type WorkerResponse =
  | { type: "ready"; requestId: string; payload: InferenceReady }
  | { type: "result"; requestId: string; payload: BrowserInferenceResult }
  | { type: "disposed"; requestId: string }
  | { type: "error"; requestId: string; message: string };

export type { BrowserInferenceManifest, BrowserModelClass };
