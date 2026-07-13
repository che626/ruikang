package com.ruikang.qc.service;

import com.ruikang.qc.dto.dashboard.DashboardOverviewResponse;
import com.ruikang.qc.dto.inspection.RealtimeStatusResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardService {

    private static final DateTimeFormatter TIME_LABEL_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final RealtimeInspectionService realtimeInspectionService;

    public DashboardService(JdbcTemplate jdbcTemplate, RealtimeInspectionService realtimeInspectionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.realtimeInspectionService = realtimeInspectionService;
    }

    public DashboardOverviewResponse getOverview() {
        int totalRecords = queryCount("SELECT COUNT(*) FROM qc_record");
        int totalFailRecords = queryCount("SELECT COUNT(*) FROM qc_record WHERE result_judge = 'FAIL'");
        int totalOpenExceptions = queryCount("SELECT COUNT(*) FROM qc_exception WHERE status IN ('OPEN', 'PROCESSING')");
        int totalReviewCount = queryCount("SELECT COUNT(*) FROM qc_record WHERE review_status <> 'PENDING'");
        int confirmedCount = queryCount("SELECT COUNT(*) FROM qc_record WHERE review_status = 'CONFIRMED'");
        String reviewAccuracy = totalReviewCount == 0
                ? "100%"
                : String.format("%.1f%%", confirmedCount * 100.0 / totalReviewCount);

        List<DashboardOverviewResponse.EventItem> recentEvents = jdbcTemplate.query("""
                        SELECT e.exception_no, e.create_time, t.defect_name
                        FROM qc_exception e
                        JOIN qc_defect_result d ON d.record_id = e.record_id
                        JOIN defect_type t ON t.defect_code = d.defect_code AND t.status = 1
                        ORDER BY e.create_time DESC
                        LIMIT 4
                        """,
                (resultSet, rowNum) -> new DashboardOverviewResponse.EventItem(
                        resultSet.getTimestamp("create_time").toLocalDateTime().toLocalTime().withNano(0).toString(),
                        "质检发现 " + resultSet.getString("defect_name") + " 缺陷",
                        "异常单 " + resultSet.getString("exception_no")
                ));

        if (recentEvents.isEmpty()) {
            recentEvents = List.of(
                    new DashboardOverviewResponse.EventItem("--", "暂无异常事件", "实时检测到瑕疵后会在这里生成异常事件")
            );
        }

        List<DashboardOverviewResponse.RankItem> rankItems = jdbcTemplate.query("""
                        SELECT t.defect_code, COUNT(d.id) AS total
                        FROM defect_type t
                        LEFT JOIN qc_defect_result d ON d.defect_code = t.defect_code
                        WHERE t.status = 1
                        GROUP BY t.defect_code, t.sort_no
                        ORDER BY total DESC, t.sort_no ASC
                        LIMIT 5
                        """,
                (resultSet, rowNum) -> new DashboardOverviewResponse.RankItem(
                        resultSet.getString("defect_code"),
                        resultSet.getInt("total")
                ));

        List<DashboardOverviewResponse.EvidenceItem> evidenceItems = jdbcTemplate.query("""
                        SELECT file_name
                        FROM qc_file
                        WHERE file_type IN ('result_image', 'evidence_image')
                        ORDER BY id DESC
                        LIMIT 3
                        """,
                (resultSet, rowNum) -> new DashboardOverviewResponse.EvidenceItem(
                        "结果图",
                        resultSet.getString("file_name"),
                        rowNum == 0 ? "cyan" : "blue"
                ));

        if (evidenceItems.isEmpty()) {
            evidenceItems = List.of(
                    new DashboardOverviewResponse.EvidenceItem("待生成", "检测到异常后自动保存结果图", "neutral")
            );
        }

        RealtimeStatusResponse realtimeStatus = realtimeInspectionService.status();
        List<DashboardOverviewResponse.HealthItem> healthItems = new ArrayList<>();
        healthItems.add(new DashboardOverviewResponse.HealthItem("摄像头连接", realtimeStatus.runtimeStatus()));
        healthItems.add(new DashboardOverviewResponse.HealthItem("模型路径", realtimeStatus.modelPath()));
        healthItems.add(new DashboardOverviewResponse.HealthItem("推理策略", realtimeStatus.providerStrategy()));
        healthItems.add(new DashboardOverviewResponse.HealthItem("磁盘证据图", String.valueOf(queryCount("SELECT COUNT(*) FROM qc_file"))));

        return new DashboardOverviewResponse(
                List.of(
                        new DashboardOverviewResponse.MetricCard("今日检测总数", String.valueOf(totalRecords), "图片与实时记录总数", totalRecords > 0 ? "实时更新" : "暂无数据", "success"),
                        new DashboardOverviewResponse.MetricCard("缺陷命中总数", String.valueOf(totalFailRecords), "FAIL 记录总数", totalFailRecords > 0 ? "检测已生效" : "等待检测", "warning"),
                        new DashboardOverviewResponse.MetricCard("异常待处理", String.valueOf(totalOpenExceptions), "OPEN + PROCESSING", totalOpenExceptions > 0 ? "需要处理" : "当前无异常", "danger"),
                        new DashboardOverviewResponse.MetricCard("人工复核准确率", reviewAccuracy, "已复核记录准确率", totalReviewCount > 0 ? "来自实际复核" : "暂无复核", "success")
                ),
                new DashboardOverviewResponse.MonitorCard(
                        "实时质检画面",
                        realtimeStatus.cameraName(),
                        realtimeStatus.runtimeStatus(),
                        realtimeStatus.inferenceFps(),
                        realtimeStatus.latencyMs(),
                        List.of(),
                        "未启动实时监控时不显示检测画面"
                ),
                recentEvents,
                rankItems,
                buildTrendCard(realtimeStatus),
                healthItems,
                evidenceItems
        );
    }

    private DashboardOverviewResponse.TrendCard buildTrendCard(RealtimeStatusResponse status) {
        if (status.inferenceFps() <= 0 && status.latencyMs() <= 0) {
            return new DashboardOverviewResponse.TrendCard(List.of(), List.of(), List.of());
        }
        return new DashboardOverviewResponse.TrendCard(
                List.of(LocalTime.now().format(TIME_LABEL_FORMAT)),
                List.of(status.inferenceFps()),
                List.of(status.latencyMs())
        );
    }

    private int queryCount(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
