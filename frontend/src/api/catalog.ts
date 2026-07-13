import http, { unwrap } from "./http";

export interface DefectTypeItem {
  id: number;
  defectCode: string;
  defectName: string;
  defectNameEn: string | null;
  displayColor: string;
  sortNo: number;
  status: number;
}

export interface ModelItem {
  id: number;
  modelName: string;
  modelVersion: string | null;
  modelPath: string;
  inputSize: string;
  confThreshold: number;
  iouThreshold: number;
  preferGpu: boolean;
  fallbackCpu: boolean;
  isActive: number;
}

export interface CameraItem {
  id: number;
  cameraCode: string;
  cameraName: string;
  cameraType: string;
  status: number;
  locationDesc: string | null;
}

export interface ConfigItem {
  id: number;
  configCode: string;
  configName: string;
  configValue: string;
  valueType: string;
  configGroup: string;
  remark: string | null;
}

export function fetchDefectTypes(): Promise<DefectTypeItem[]> {
  return unwrap<DefectTypeItem[]>(http.get("/defect-types"));
}

export function fetchModels(): Promise<ModelItem[]> {
  return unwrap<ModelItem[]>(http.get("/models"));
}

export function fetchCameras(): Promise<CameraItem[]> {
  return unwrap<CameraItem[]>(http.get("/cameras"));
}

export function fetchConfigs(): Promise<ConfigItem[]> {
  return unwrap<ConfigItem[]>(http.get("/configs"));
}

export function updateConfig(configCode: string, configValue: string): Promise<ConfigItem[]> {
  return unwrap<ConfigItem[]>(http.put(`/configs/${configCode}`, { configValue }));
}

export function updateDefectTypeColor(defectCode: string, displayColor: string): Promise<DefectTypeItem[]> {
  return unwrap<DefectTypeItem[]>(http.put(`/defect-types/${defectCode}/color`, { displayColor }));
}

export function setModelActive(modelId: number, active: boolean): Promise<ModelItem[]> {
  return unwrap<ModelItem[]>(http.put(`/models/${modelId}/active`, { active }));
}

export function setCameraActive(cameraCode: string): Promise<CameraItem[]> {
  return unwrap<CameraItem[]>(http.put(`/cameras/${cameraCode}/active`, {}));
}
