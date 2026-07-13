package com.ruikang.qc.dto.dashboard;

import java.util.List;

public record DashboardOverviewResponse(
        List<MetricCard> metrics,
        MonitorCard monitor,
        List<EventItem> recentEvents,
        List<RankItem> defectRanks,
        TrendCard trend,
        List<HealthItem> healthItems,
        List<EvidenceItem> evidenceItems
) {

    public record MetricCard(String label, String value, String hint, String trendLabel, String tone) {
    }

    public record MonitorCard(
            String title,
            String cameraName,
            String runtimeStatus,
            int inferenceFps,
            int latencyMs,
            List<DetectionBox> boxes,
            String footer
    ) {
    }

    public record DetectionBox(String label, String tone, double top, double left, double width, double height) {
    }

    public record EventItem(String time, String title, String description) {
    }

    public record RankItem(String code, int count) {
    }

    public record TrendCard(List<String> labels, List<Integer> fpsSeries, List<Integer> latencySeries) {
    }

    public record HealthItem(String label, String value) {
    }

    public record EvidenceItem(String title, String subtitle, String tone) {
    }
}
