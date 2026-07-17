import http, { unwrap } from "./http";
import type { DetectionItem } from "./records";

export interface ImageInspectionRecord {
  recordNo: string;
  resultJudge: string;
  reviewStatus: string;
  defectCount: number;
  confidenceScore: number | null;
  preprocessTimeMs: number | null;
  inferenceTimeMs: number | null;
  postprocessTimeMs: number | null;
  resultSummary: string;
  sourceImagePath: string | null;
  resultImagePath: string | null;
  detections: DetectionItem[];
}

export interface ImageInspectionUploadResult {
  taskNo: string;
  taskStatus: string;
  fileCount: number;
  records: ImageInspectionRecord[];
}

export interface ClientImageInspectionRecordPayload {
  originalFileName: string;
  imageWidth: number;
  imageHeight: number;
  preprocessTimeMs: number;
  inferenceTimeMs: number;
  postprocessTimeMs: number;
  providerStrategy: string;
  detections: DetectionItem[];
}

export async function submitClientImageResults(
  modelSha256: string,
  records: ClientImageInspectionRecordPayload[],
  sourceFiles: File[],
  resultFiles: File[],
  remark: string,
): Promise<ImageInspectionUploadResult> {
  const formData = new FormData();
  formData.append(
    "payload",
    new Blob([JSON.stringify({ modelSha256, remark, records })], { type: "application/json" }),
    "payload.json",
  );
  sourceFiles.forEach((file) => formData.append("sourceFiles", file));
  resultFiles.forEach((file) => formData.append("resultFiles", file));
  return unwrap<ImageInspectionUploadResult>(http.post("/inspection/image/tasks/client-results", formData));
}
