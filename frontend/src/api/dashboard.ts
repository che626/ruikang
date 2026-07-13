import http, { unwrap } from "./http";

export interface DashboardOverview {
  metrics: Array<{
    label: string;
    value: string;
    hint: string;
    trendLabel: string;
    tone: string;
  }>;
  monitor: {
    title: string;
    cameraName: string;
    runtimeStatus: string;
    inferenceFps: number;
    latencyMs: number;
    boxes: Array<{
      label: string;
      tone: string;
      top: number;
      left: number;
      width: number;
      height: number;
    }>;
    footer: string;
  };
  recentEvents: Array<{
    time: string;
    title: string;
    description: string;
  }>;
  defectRanks: Array<{
    code: string;
    count: number;
  }>;
  trend: {
    labels: string[];
    fpsSeries: number[];
    latencySeries: number[];
  };
  healthItems: Array<{
    label: string;
    value: string;
  }>;
  evidenceItems: Array<{
    title: string;
    subtitle: string;
    tone: string;
  }>;
}

export function fetchDashboardOverview(): Promise<DashboardOverview> {
  return unwrap<DashboardOverview>(http.get("/dashboard/overview"));
}
