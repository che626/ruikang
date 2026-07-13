import http, { unwrap } from "./http";

export interface DetectionItem {
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
}

export interface RecordListItem {
  recordNo: string;
  inspectType: string;
  resultJudge: string;
  reviewStatus: string;
  defectCount: number;
  confidenceScore: number | null;
  resultSummary: string;
  inspectTime: string;
}

export interface RecordDetail {
  recordNo: string;
  inspectType: string;
  sourceType: string;
  resultJudge: string;
  confidenceScore: number | null;
  defectCount: number;
  reviewStatus: string;
  reviewRemark: string | null;
  inspectTime: string;
  preprocessTimeMs: number | null;
  inferenceTimeMs: number | null;
  postprocessTimeMs: number | null;
  sourceImagePath: string | null;
  resultImagePath: string | null;
  evidenceImagePaths: string[];
  detections: DetectionItem[];
  exceptionNo: string | null;
  exceptionStatus: string | null;
}

export interface ReviewRecordPayload {
  reviewStatus: string;
  reviewRemark?: string;
}

export function fetchRecords(): Promise<RecordListItem[]> {
  return unwrap<RecordListItem[]>(http.get("/records"));
}

export function fetchRecordDetail(recordNo: string): Promise<RecordDetail> {
  return unwrap<RecordDetail>(http.get(`/records/${recordNo}`));
}

export function reviewRecord(recordNo: string, payload: ReviewRecordPayload): Promise<RecordDetail> {
  return unwrap<RecordDetail>(http.post(`/records/${recordNo}/review`, payload));
}

export function clearRecords(): Promise<void> {
  return unwrap<void>(http.delete("/records"));
}
