import http, { unwrap } from "./http";

export interface QualityReport {
  reportNo: string;
  recordNo: string;
  reportTitle: string;
  defectSummary: string;
  causeAnalysis: string;
  riskAssessment: string;
  handlingSuggestion: string;
  qualityConclusion: string;
  riskLevel: string;
  aiProvider: string;
  aiModel: string;
  createTime: string;
}

export interface QualityReportListItem {
  reportNo: string;
  recordNo: string;
  reportTitle: string;
  riskLevel: string;
  aiProvider: string;
  aiModel: string;
  createTime: string;
}

export function fetchQualityReports(): Promise<QualityReportListItem[]> {
  return unwrap<QualityReportListItem[]>(http.get("/aigc/quality-reports"));
}

export function fetchQualityReport(reportNo: string): Promise<QualityReport> {
  return unwrap<QualityReport>(http.get(`/aigc/quality-reports/${reportNo}`));
}

export function generateQualityReport(recordNo: string): Promise<QualityReport> {
  return unwrap<QualityReport>(http.post("/aigc/quality-reports/generate", { recordNo }, { timeout: 90000 }));
}

export function generateOverallQualityReport(): Promise<QualityReport> {
  return unwrap<QualityReport>(http.post("/aigc/quality-reports/generate-overall", {}, { timeout: 90000 }));
}

export function clearQualityReports(): Promise<void> {
  return unwrap<void>(http.delete("/aigc/quality-reports"));
}
