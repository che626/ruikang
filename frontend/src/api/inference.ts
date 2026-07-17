import http, { unwrap } from "./http";

export interface BrowserModelClass {
  index: number;
  defectCode: string;
  defectName: string;
  displayColor: string;
}

export interface BrowserInferenceManifest {
  modelName: string;
  modelVersion: string;
  modelUrl: string;
  sha256: string;
  fileSize: number;
  inputName: string;
  outputName: string;
  inputWidth: number;
  inputHeight: number;
  classes: BrowserModelClass[];
  confidenceThreshold: number;
  iouThreshold: number;
  preferredExecutionProviders: string[];
}

export function fetchInferenceManifest(): Promise<BrowserInferenceManifest> {
  return unwrap<BrowserInferenceManifest>(http.get("/inference/manifest"));
}
