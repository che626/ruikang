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

export async function uploadAndInspectImages(files: File[], remark: string): Promise<ImageInspectionUploadResult> {
  const formData = new FormData();
  files.forEach((file) => formData.append("files", file));
  formData.append("remark", remark);
  return unwrap<ImageInspectionUploadResult>(http.post("/inspection/image/tasks/upload", formData));
}

export function fetchTaskRecords(taskNo: string): Promise<ImageInspectionRecord[]> {
  return unwrap<ImageInspectionRecord[]>(http.get(`/inspection/image/tasks/${taskNo}/records`));
}
