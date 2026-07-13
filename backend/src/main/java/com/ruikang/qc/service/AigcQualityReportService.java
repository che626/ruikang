package com.ruikang.qc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruikang.qc.config.QcProperties;
import com.ruikang.qc.dto.aigc.QualityReportListItemResponse;
import com.ruikang.qc.dto.aigc.QualityReportResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class AigcQualityReportService {

    private static final DateTimeFormatter REPORT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final QcProperties qcProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AigcQualityReportService(JdbcTemplate jdbcTemplate, QcProperties qcProperties, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.qcProperties = qcProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, qcProperties.getAigc().getTimeoutSeconds())))
                .build();
    }

    public QualityReportResponse generateReport(String recordNo) {
        InspectionContext context = loadInspectionContext(recordNo);
        String prompt = buildPrompt(context);
        GeneratedReport generatedReport = normalizeReport(generateWithDeepSeek(
                "AIGC质检分析报告-" + context.recordNo(),
                context.summaryLine(),
                inferredRiskLevel(context),
                prompt,
                reason -> fallbackReport(context, reason)
        ));
        String reportNo = "RPT-" + LocalDateTime.now().format(REPORT_FORMAT) + randomSuffix();

        jdbcTemplate.update("""
                INSERT INTO qc_quality_report (
                    report_no, record_id, record_no, report_title, defect_summary,
                    cause_analysis, risk_assessment, handling_suggestion, quality_conclusion,
                    risk_level, ai_provider, ai_model, prompt_snapshot, raw_response
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reportNo,
                context.recordId(),
                context.recordNo(),
                generatedReport.reportTitle(),
                generatedReport.defectSummary(),
                generatedReport.causeAnalysis(),
                generatedReport.riskAssessment(),
                generatedReport.handlingSuggestion(),
                generatedReport.qualityConclusion(),
                generatedReport.riskLevel(),
                generatedReport.aiProvider(),
                generatedReport.aiModel(),
                prompt,
                generatedReport.rawResponse()
        );

        return getReport(reportNo);
    }

    public QualityReportResponse generateOverallReport() {
        OverallInspectionContext context = loadOverallInspectionContext();
        String prompt = buildOverallPrompt(context);
        GeneratedReport generatedReport = normalizeReport(generateWithDeepSeek(
                "AIGC整体质检趋势分析报告-" + context.recordNo(),
                context.summaryLine(),
                inferredOverallRiskLevel(context),
                prompt,
                reason -> fallbackOverallReport(context, reason)
        ));
        String reportNo = "RPT-" + LocalDateTime.now().format(REPORT_FORMAT) + randomSuffix();

        jdbcTemplate.update("""
                INSERT INTO qc_quality_report (
                    report_no, record_id, record_no, report_title, defect_summary,
                    cause_analysis, risk_assessment, handling_suggestion, quality_conclusion,
                    risk_level, ai_provider, ai_model, prompt_snapshot, raw_response
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reportNo,
                null,
                context.recordNo(),
                generatedReport.reportTitle(),
                generatedReport.defectSummary(),
                generatedReport.causeAnalysis(),
                generatedReport.riskAssessment(),
                generatedReport.handlingSuggestion(),
                generatedReport.qualityConclusion(),
                generatedReport.riskLevel(),
                generatedReport.aiProvider(),
                generatedReport.aiModel(),
                prompt,
                generatedReport.rawResponse()
        );

        return getReport(reportNo);
    }

    public void clearReports() {
        jdbcTemplate.update("DELETE FROM qc_quality_report");
    }

    public List<QualityReportListItemResponse> listReports() {
        return jdbcTemplate.query("""
                        SELECT report_no, record_no, report_title, risk_level, ai_provider, ai_model, create_time
                        FROM qc_quality_report
                        ORDER BY create_time DESC, id DESC
                        LIMIT 30
                        """,
                (resultSet, rowNum) -> new QualityReportListItemResponse(
                        resultSet.getString("report_no"),
                        resultSet.getString("record_no"),
                        normalizeTerminology(resultSet.getString("report_title")),
                        resultSet.getString("risk_level"),
                        resultSet.getString("ai_provider"),
                        resultSet.getString("ai_model"),
                        resultSet.getObject("create_time", LocalDateTime.class)
                ));
    }

    public QualityReportResponse getReport(String reportNo) {
        return jdbcTemplate.query("""
                        SELECT report_no, record_no, report_title, defect_summary, cause_analysis,
                               risk_assessment, handling_suggestion, quality_conclusion, risk_level,
                               ai_provider, ai_model, create_time
                        FROM qc_quality_report
                        WHERE report_no = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("分析报告不存在: " + reportNo);
                    }
                    return new QualityReportResponse(
                            resultSet.getString("report_no"),
                            resultSet.getString("record_no"),
                            normalizeTerminology(resultSet.getString("report_title")),
                            normalizeTerminology(resultSet.getString("defect_summary")),
                            normalizeTerminology(resultSet.getString("cause_analysis")),
                            normalizeTerminology(resultSet.getString("risk_assessment")),
                            normalizeTerminology(resultSet.getString("handling_suggestion")),
                            normalizeTerminology(resultSet.getString("quality_conclusion")),
                            resultSet.getString("risk_level"),
                            resultSet.getString("ai_provider"),
                            resultSet.getString("ai_model"),
                            resultSet.getObject("create_time", LocalDateTime.class)
                    );
                },
                reportNo);
    }

    private GeneratedReport generateWithDeepSeek(
            String fallbackTitle,
            String defaultSummary,
            String defaultRiskLevel,
            String prompt,
            Function<String, GeneratedReport> fallbackFactory
    ) {
        QcProperties.Aigc aigc = qcProperties.getAigc();
        if (aigc.getApiKey() == null || aigc.getApiKey().isBlank()) {
            return fallbackFactory.apply("DEEPSEEK_API_KEY 未配置");
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", aigc.getModel());
            requestBody.put("temperature", 0.28);
            requestBody.put("max_tokens", 2200);
            requestBody.put("stream", false);
            requestBody.put("response_format", Map.of("type", "json_object"));
            requestBody.put("messages", List.of(
                    Map.of(
                            "role", "system",
                            "content", """
                                    你是一名资深纺织品质量工程师和AIGC质检决策专家。
                                    请基于机器视觉检测结果生成专业质检分析报告，而不是复述数据。
                                    术语必须统一：检测对象一律称为“面料”或“纺织面料”，不要使用“胚布”“坯布”“布匹”等说法。
                                    必须输出严格 JSON，不要输出 Markdown，不要包裹代码块。
                                    JSON 字段必须包含: reportTitle, defectSummary, causeAnalysis, riskAssessment, handlingSuggestion, qualityConclusion, riskLevel。
                                    riskLevel 只能为 LOW、MEDIUM、HIGH、CRITICAL。
                                    """
                    ),
                    Map.of("role", "user", "content", prompt)
            ));

            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBaseUrl(aigc.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(10, aigc.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aigc.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DeepSeek API 返回状态码 " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode generated = objectMapper.readTree(extractJson(content));
            return new GeneratedReport(
                    textOrDefault(generated, "reportTitle", fallbackTitle),
                    textOrDefault(generated, "defectSummary", defaultSummary),
                    textOrDefault(generated, "causeAnalysis", "未返回原因分析。"),
                    textOrDefault(generated, "riskAssessment", "未返回风险评估。"),
                    textOrDefault(generated, "handlingSuggestion", "未返回处理建议。"),
                    textOrDefault(generated, "qualityConclusion", "未返回质检结论。"),
                    normalizeRisk(textOrDefault(generated, "riskLevel", defaultRiskLevel)),
                    aigc.getProvider(),
                    aigc.getModel(),
                    response.body()
            );
        } catch (Exception exception) {
            if (aigc.isFallbackLocal()) {
                return fallbackFactory.apply(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            throw new IllegalStateException("生成AIGC质检报告失败: " + exception.getMessage(), exception);
        }
    }

    private InspectionContext loadInspectionContext(String recordNo) {
        InspectionContext base = jdbcTemplate.query("""
                        SELECT id, record_no, inspect_type, source_type, result_judge, confidence_score,
                               defect_count, result_summary, inference_time_ms, preprocess_time_ms,
                               postprocess_time_ms, inspect_time
                        FROM qc_record
                        WHERE record_no = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("检测记录不存在: " + recordNo);
                    }
                    return new InspectionContext(
                            resultSet.getLong("id"),
                            resultSet.getString("record_no"),
                            resultSet.getString("inspect_type"),
                            resultSet.getString("source_type"),
                            resultSet.getString("result_judge"),
                            resultSet.getObject("confidence_score", Double.class),
                            resultSet.getInt("defect_count"),
                            resultSet.getString("result_summary"),
                            resultSet.getObject("inference_time_ms", Integer.class),
                            resultSet.getObject("preprocess_time_ms", Integer.class),
                            resultSet.getObject("postprocess_time_ms", Integer.class),
                            resultSet.getObject("inspect_time", LocalDateTime.class),
                            List.of()
                    );
                },
                recordNo);

        List<DetectionContext> detections = jdbcTemplate.query("""
                        SELECT d.defect_code, d.defect_name, d.confidence, d.x1, d.y1, d.x2, d.y2,
                               d.box_width, d.box_height, d.area, d.image_width, d.image_height
                        FROM qc_defect_result d
                        WHERE d.record_id = ?
                        ORDER BY d.confidence DESC
                        """,
                (resultSet, rowNum) -> new DetectionContext(
                        resultSet.getString("defect_code"),
                        resultSet.getString("defect_name"),
                        resultSet.getDouble("confidence"),
                        resultSet.getInt("x1"),
                        resultSet.getInt("y1"),
                        resultSet.getInt("x2"),
                        resultSet.getInt("y2"),
                        resultSet.getInt("box_width"),
                        resultSet.getInt("box_height"),
                        resultSet.getInt("area"),
                        resultSet.getInt("image_width"),
                        resultSet.getInt("image_height")
                ),
                base.recordId());
        return base.withDetections(detections);
    }

    private OverallInspectionContext loadOverallInspectionContext() {
        OverallInspectionContext base = jdbcTemplate.query("""
                        SELECT COUNT(*) AS total_records,
                               SUM(CASE WHEN result_judge = 'FAIL' THEN 1 ELSE 0 END) AS fail_records,
                               SUM(CASE WHEN result_judge = 'PASS' THEN 1 ELSE 0 END) AS pass_records,
                               SUM(CASE WHEN review_status = 'FALSE_ALARM' THEN 1 ELSE 0 END) AS false_alarm_records,
                               COALESCE(SUM(defect_count), 0) AS total_defects,
                               AVG(confidence_score) AS avg_confidence,
                               AVG(inference_time_ms) AS avg_inference_ms,
                               MIN(inspect_time) AS first_inspect_time,
                               MAX(inspect_time) AS last_inspect_time
                        FROM qc_record
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("暂无质检记录，无法生成整体分析报告");
                    }
                    int totalRecords = resultSet.getInt("total_records");
                    if (totalRecords == 0) {
                        throw new IllegalArgumentException("暂无质检记录，无法生成整体分析报告");
                    }
                    return new OverallInspectionContext(
                            "ALL-" + LocalDateTime.now().format(REPORT_FORMAT),
                            totalRecords,
                            resultSet.getInt("fail_records"),
                            resultSet.getInt("pass_records"),
                            resultSet.getInt("false_alarm_records"),
                            resultSet.getInt("total_defects"),
                            resultSet.getObject("avg_confidence", Double.class),
                            resultSet.getObject("avg_inference_ms", Double.class),
                            resultSet.getObject("first_inspect_time", LocalDateTime.class),
                            resultSet.getObject("last_inspect_time", LocalDateTime.class),
                            List.of()
                    );
                });

        List<DefectAggregateContext> defectAggregates = jdbcTemplate.query("""
                        SELECT d.defect_code, d.defect_name, COUNT(*) AS hit_count,
                               AVG(d.confidence) AS avg_confidence, MAX(d.confidence) AS max_confidence,
                               COALESCE(SUM(d.area), 0) AS total_area
                        FROM qc_defect_result d
                        JOIN qc_record r ON r.id = d.record_id
                        GROUP BY d.defect_code, d.defect_name
                        ORDER BY hit_count DESC, max_confidence DESC
                        LIMIT 8
                        """,
                (resultSet, rowNum) -> new DefectAggregateContext(
                        resultSet.getString("defect_code"),
                        resultSet.getString("defect_name"),
                        resultSet.getInt("hit_count"),
                        resultSet.getObject("avg_confidence", Double.class),
                        resultSet.getObject("max_confidence", Double.class),
                        resultSet.getInt("total_area")
                ));
        return base.withDefectAggregates(defectAggregates);
    }

    private String buildPrompt(InspectionContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("请为以下面料/纺织品AI质检检测结果生成专业分析报告。报告必须体现从“发现缺陷”到“理解缺陷并提供决策建议”的升级能力。\n");
        builder.append("术语要求：全文统一使用“面料”或“纺织面料”；即使检测摘要里出现旧称呼，也必须改写为“面料”，禁止使用“胚布”“坯布”“布匹”。\n\n");
        builder.append("检测记录：").append(context.recordNo()).append('\n');
        builder.append("检测方式：").append(context.inspectType()).append(" / ").append(context.sourceType()).append('\n');
        builder.append("检测结论：").append(context.resultJudge()).append('\n');
        builder.append("缺陷数量：").append(context.defectCount()).append('\n');
        builder.append("最高置信度：").append(context.confidenceScore() == null ? "-" : String.format(Locale.ROOT, "%.3f", context.confidenceScore())).append('\n');
        builder.append("检测摘要：").append(context.resultSummary()).append('\n');
        builder.append("耗时：预处理 ").append(nullToZero(context.preprocessTimeMs()))
                .append(" ms，推理 ").append(nullToZero(context.inferenceTimeMs()))
                .append(" ms，后处理 ").append(nullToZero(context.postprocessTimeMs())).append(" ms\n");
        builder.append("检测时间：").append(context.inspectTime()).append("\n\n");
        builder.append("缺陷明细：\n");
        if (context.detections().isEmpty()) {
            builder.append("- 未检测到具体缺陷框。\n");
        } else {
            for (DetectionContext detection : context.detections()) {
                builder.append("- ")
                        .append(detection.defectName()).append(" (").append(detection.defectCode()).append(")")
                        .append("，置信度 ").append(String.format(Locale.ROOT, "%.3f", detection.confidence()))
                        .append("，坐标 [").append(detection.x1()).append(',').append(detection.y1())
                        .append(',').append(detection.x2()).append(',').append(detection.y2()).append(']')
                        .append("，框面积 ").append(detection.area())
                        .append("，图像尺寸 ").append(detection.imageWidth()).append('x').append(detection.imageHeight())
                        .append('\n');
            }
        }
        builder.append("""

                请输出 JSON：
                {
                  "reportTitle": "报告标题",
                  "defectSummary": "缺陷综合摘要，体现缺陷类型、可信度、空间特征和检测场景",
                  "causeAnalysis": "原因分析，结合纺织工艺、设备张力、污染源、纱线/织造/后整理等可能因素",
                  "riskAssessment": "风险评估，说明外观、强力、批量扩散、客户验收和后续加工风险",
                  "handlingSuggestion": "处理建议，包含现场处置、返修/隔离、工艺排查、复检策略和追溯建议",
                  "qualityConclusion": "质检结论，明确放行/隔离/返修/报废或人工复核建议",
                  "riskLevel": "LOW/MEDIUM/HIGH/CRITICAL"
                }
                要求：中文、专业、可直接进入比赛演示页面；不要编造不存在的缺陷类别；不能只复述检测结果。
                """);
        return builder.toString();
    }

    private String buildOverallPrompt(OverallInspectionContext context) {
        StringBuilder builder = new StringBuilder();
        double failRate = context.totalRecords() == 0 ? 0D : context.failRecords() * 100.0D / context.totalRecords();
        builder.append("请基于以下纺织面料质检历史记录，生成面向生产管理者的AIGC整体质量分析报告。")
                .append("报告需要体现趋势理解、风险判断和工艺决策建议，而不是简单汇总数字。\n\n");
        builder.append("术语要求：全文统一使用“面料”或“纺织面料”；即使历史记录里出现旧称呼，也必须改写为“面料”，禁止使用“胚布”“坯布”“布匹”。\n\n");
        builder.append("分析范围编号：").append(context.recordNo()).append('\n');
        builder.append("质检记录总数：").append(context.totalRecords()).append('\n');
        builder.append("异常记录数：").append(context.failRecords()).append('\n');
        builder.append("正常记录数：").append(context.passRecords()).append('\n');
        builder.append("误报标记数：").append(context.falseAlarmRecords()).append('\n');
        builder.append("缺陷框总数：").append(context.totalDefects()).append('\n');
        builder.append("异常记录占比：").append(String.format(Locale.ROOT, "%.1f%%", failRate)).append('\n');
        builder.append("平均置信度：")
                .append(context.avgConfidence() == null ? "-" : String.format(Locale.ROOT, "%.3f", context.avgConfidence()))
                .append('\n');
        builder.append("平均推理耗时：")
                .append(context.avgInferenceMs() == null ? "-" : String.format(Locale.ROOT, "%.0f ms", context.avgInferenceMs()))
                .append('\n');
        builder.append("检测时间范围：").append(context.firstInspectTime()).append(" 至 ").append(context.lastInspectTime()).append("\n\n");
        builder.append("缺陷类型分布：\n");
        if (context.defectAggregates().isEmpty()) {
            builder.append("- 暂无缺陷明细。\n");
        } else {
            for (DefectAggregateContext item : context.defectAggregates()) {
                builder.append("- ")
                        .append(item.defectName()).append(" (").append(item.defectCode()).append(")")
                        .append("，命中 ").append(item.hitCount()).append(" 次")
                        .append("，平均置信度 ").append(formatNullableDouble(item.avgConfidence()))
                        .append("，最高置信度 ").append(formatNullableDouble(item.maxConfidence()))
                        .append("，累计框面积 ").append(item.totalArea())
                        .append('\n');
            }
        }
        builder.append("""

                请输出 JSON：
                {
                  "reportTitle": "整体质检分析报告标题",
                  "defectSummary": "整体缺陷态势摘要，需要说明主要缺陷、异常比例、检测稳定性和质量趋势",
                  "causeAnalysis": "原因分析，从织造、张力、设备维护、污染源、原料批次、周转环境等角度推断可能根因",
                  "riskAssessment": "风险评估，说明批量扩散、客户验收、返工返修、产线稳定性和交付风险",
                  "handlingSuggestion": "处理建议，包含优先排查项、抽检策略、隔离策略、工艺参数复盘和下一步闭环动作",
                  "qualityConclusion": "质检结论，给出当前批次/近期生产的综合质量判断和管理建议",
                  "riskLevel": "LOW/MEDIUM/HIGH/CRITICAL"
                }
                要求：中文、专业，可直接用于质量管理与决策；必须基于已提供的检测数据，不要编造不存在的缺陷类别。
                """);
        return builder.toString();
    }

    private GeneratedReport fallbackReport(InspectionContext context, String reason) {
        String riskLevel = inferredRiskLevel(context);
        String primaryDefect = context.detections().isEmpty() ? "未识别缺陷" : context.detections().get(0).defectName();
        String defectSummary = context.summaryLine();
        String causeAnalysis = switch (context.detections().isEmpty() ? "" : context.detections().get(0).defectCode()) {
            case "wrinkle" -> "检测结果显示褶皱特征较突出，常见诱因包括卷布张力波动、导布辊压力不均、前道堆叠压痕或湿热定型不充分。建议结合该记录的高置信度区域回看对应面料段，重点检查张力闭环、卷绕压力和布面平整度。";
            case "hole" -> "破洞通常与纱线断裂、机械刮擦、针板/钢筘异常或异物穿刺有关。该类缺陷具备结构性损伤特征，应优先排查织造通道和输送路径中的尖锐接触点。";
            case "ink_stain" -> "墨渍多与标识墨水、设备油墨、周转器具污染或人员操作接触有关。建议追溯同批次周转环节和近端工位清洁记录，排查是否存在可扩散污染源。";
            case "oil_stain" -> "油渍通常来源于润滑系统泄漏、导辊轴承渗油、设备维护残留或运输污染。需要同步检查设备点检记录和污染位置的重复出现规律。";
            case "broken_yarn" -> "断纱反映纱线连续性受破坏，常与张力异常、综丝或钢筘磨损、断头处理不及时及原纱强力不足有关。建议对相邻连续面料段进行加严复检。";
            case "foreign_matter" -> "异物通常与生产环境清洁、设备表面附着物、原料夹杂或周转过程污染有关。建议核查缺陷位置附近的设备接触面、清洁记录和同批原料状态。";
            default -> "检测结果提示存在布面异常，应结合缺陷位置、置信度和同批次复现情况，排查织造、后整理、搬运及设备接触链路中的异常因素。";
        };
        String riskAssessment = "综合缺陷数量、最高置信度和缺陷类型判断，本记录风险等级为 " + riskLevel
                + "。若该缺陷在同批次中重复出现，可能造成外观降级、客户验收不合格、返修成本上升，并对后续染整、裁剪或成品稳定性产生连带影响。";
        String handlingSuggestion = "建议对记录 " + context.recordNo()
                + " 对应布段进行单独标识与隔离，安排人工复核缺陷边界；同步追溯前后 3-5 米布面和同批次相邻卷号。若复核为真实缺陷，应记录设备参数、班次、原料批次和维护状态，必要时暂停相关工位进行点检。";
        String conclusion = "质检结论：该记录建议判定为需人工复核的异常布段。若复核确认 " + primaryDefect
                + " 与模型检测一致，建议隔离或返修处理；若缺陷面积扩大或连续出现，应升级为批量质量风险并启动工艺排查。";
        return new GeneratedReport(
                "AIGC质检分析报告-" + context.recordNo(),
                defectSummary,
                causeAnalysis,
                riskAssessment,
                handlingSuggestion,
                conclusion,
                riskLevel,
                qcProperties.getAigc().getProvider() + "(LocalFallback)",
                qcProperties.getAigc().getModel(),
                "{\"fallbackReason\":\"" + escapeJson(reason) + "\"}"
        );
    }

    private GeneratedReport fallbackOverallReport(OverallInspectionContext context, String reason) {
        String riskLevel = inferredOverallRiskLevel(context);
        String topDefect = context.defectAggregates().isEmpty()
                ? "暂无集中缺陷"
                : context.defectAggregates().get(0).defectName();
        double failRate = context.totalRecords() == 0 ? 0D : context.failRecords() * 100.0D / context.totalRecords();
        String summary = context.summaryLine();
        String causeAnalysis = "整体记录显示主要问题集中在 " + topDefect
                + "。从纺织生产链路看，应优先排查对应工序的张力稳定性、导辊/钢筘/输送接触面状态、设备维护残留、原料批次一致性以及布面周转污染源。若同类缺陷在短时间内重复出现，说明问题可能已从单点异常升级为工艺或设备状态波动。";
        String riskAssessment = "当前异常记录占比约为 " + String.format(Locale.ROOT, "%.1f%%", failRate)
                + "，综合风险等级为 " + riskLevel
                + "。若未及时隔离和追溯，可能导致批量外观降级、客户验收退货、返修成本增加，并影响后续染整、裁剪或成品交付稳定性。";
        String handlingSuggestion = "建议按缺陷热度优先处理高频缺陷类型，对近端布卷和相邻批次执行加严抽检；对重复出现的缺陷建立工位、设备、班次和原料批次追溯表；对高置信度异常记录进行人工复核，确认后将相关布段隔离、返修或降级处理。";
        String conclusion = "质检结论：当前质检记录具备进行整体质量决策的依据。建议将 " + topDefect
                + " 作为首要治理对象，采用“模型检测-AIGC分析-人工复核-工艺闭环”的流程推进，避免异常从单卷扩散为批量质量风险。";
        return new GeneratedReport(
                "AIGC整体质检趋势分析报告-" + context.recordNo(),
                summary,
                causeAnalysis,
                riskAssessment,
                handlingSuggestion,
                conclusion,
                riskLevel,
                qcProperties.getAigc().getProvider() + "(LocalFallback)",
                qcProperties.getAigc().getModel(),
                "{\"fallbackReason\":\"" + escapeJson(reason) + "\"}"
        );
    }

    private GeneratedReport normalizeReport(GeneratedReport report) {
        return new GeneratedReport(
                normalizeTerminology(report.reportTitle()),
                normalizeTerminology(report.defectSummary()),
                normalizeTerminology(report.causeAnalysis()),
                normalizeTerminology(report.riskAssessment()),
                normalizeTerminology(report.handlingSuggestion()),
                normalizeTerminology(report.qualityConclusion()),
                report.riskLevel(),
                report.aiProvider(),
                report.aiModel(),
                report.rawResponse()
        );
    }

    private String normalizeTerminology(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replace("同一块布", "同一块面料")
                .replace("胚布", "面料")
                .replace("坯布", "面料")
                .replace("布匹", "面料")
                .replace("布段", "面料段")
                .replace("布卷", "面料卷");
    }

    private String inferredRiskLevel(InspectionContext context) {
        if ("PASS".equalsIgnoreCase(context.resultJudge()) || context.defectCount() == 0) {
            return "LOW";
        }
        double confidence = context.confidenceScore() == null ? 0D : context.confidenceScore();
        boolean structural = context.detections().stream()
                .anyMatch(item -> List.of("hole", "broken_yarn").contains(item.defectCode()));
        if (context.defectCount() >= 3 || confidence >= 0.88D && structural) {
            return "CRITICAL";
        }
        if (context.defectCount() >= 2 || confidence >= 0.78D || structural) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private String inferredOverallRiskLevel(OverallInspectionContext context) {
        if (context.totalRecords() == 0 || context.failRecords() == 0 || context.totalDefects() == 0) {
            return "LOW";
        }
        double failRate = context.failRecords() * 1.0D / Math.max(1, context.totalRecords());
        boolean structural = context.defectAggregates().stream()
                .anyMatch(item -> List.of("hole", "broken_yarn").contains(item.defectCode()));
        if (context.failRecords() >= 8 || failRate >= 0.55D || context.totalDefects() >= 20 && structural) {
            return "CRITICAL";
        }
        if (context.failRecords() >= 4 || failRate >= 0.30D || structural) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String normalizeRisk(String value) {
        String risk = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (risk) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> risk;
            default -> "MEDIUM";
        };
    }

    private String extractJson(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```json", "").replaceFirst("^```", "").replaceFirst("```$", "").trim();
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        throw new IllegalStateException("DeepSeek 返回内容不是 JSON");
    }

    private String normalizedBaseUrl(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatNullableDouble(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.3f", value);
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record InspectionContext(
            Long recordId,
            String recordNo,
            String inspectType,
            String sourceType,
            String resultJudge,
            Double confidenceScore,
            int defectCount,
            String resultSummary,
            Integer inferenceTimeMs,
            Integer preprocessTimeMs,
            Integer postprocessTimeMs,
            LocalDateTime inspectTime,
            List<DetectionContext> detections
    ) {
        InspectionContext withDetections(List<DetectionContext> detections) {
            return new InspectionContext(recordId, recordNo, inspectType, sourceType, resultJudge, confidenceScore,
                    defectCount, resultSummary, inferenceTimeMs, preprocessTimeMs, postprocessTimeMs, inspectTime, detections);
        }

        String summaryLine() {
            if (detections.isEmpty()) {
                return resultSummary == null || resultSummary.isBlank() ? "未检测到明确缺陷。" : resultSummary;
            }
            DetectionContext top = detections.get(0);
            return "检测到 " + defectCount + " 个缺陷，主要缺陷为 " + top.defectName()
                    + "，最高置信度 " + String.format(Locale.ROOT, "%.3f", confidenceScore == null ? top.confidence() : confidenceScore)
                    + "，来源为 " + inspectType + "/" + sourceType + "。";
        }
    }

    private record DetectionContext(
            String defectCode,
            String defectName,
            double confidence,
            int x1,
            int y1,
            int x2,
            int y2,
            int width,
            int height,
            int area,
            int imageWidth,
            int imageHeight
    ) {
    }

    private record OverallInspectionContext(
            String recordNo,
            int totalRecords,
            int failRecords,
            int passRecords,
            int falseAlarmRecords,
            int totalDefects,
            Double avgConfidence,
            Double avgInferenceMs,
            LocalDateTime firstInspectTime,
            LocalDateTime lastInspectTime,
            List<DefectAggregateContext> defectAggregates
    ) {
        OverallInspectionContext withDefectAggregates(List<DefectAggregateContext> defectAggregates) {
            return new OverallInspectionContext(recordNo, totalRecords, failRecords, passRecords, falseAlarmRecords,
                    totalDefects, avgConfidence, avgInferenceMs, firstInspectTime, lastInspectTime, defectAggregates);
        }

        String summaryLine() {
            if (totalRecords == 0) {
                return "暂无质检记录。";
            }
            String topDefect = defectAggregates.isEmpty() ? "暂无集中缺陷" : defectAggregates.get(0).defectName();
            double failRate = failRecords * 100.0D / Math.max(1, totalRecords);
            return "共分析 " + totalRecords + " 条质检记录，异常 " + failRecords + " 条，异常占比 "
                    + String.format(Locale.ROOT, "%.1f%%", failRate)
                    + "，累计缺陷 " + totalDefects + " 个，主要缺陷为 " + topDefect + "。";
        }
    }

    private record DefectAggregateContext(
            String defectCode,
            String defectName,
            int hitCount,
            Double avgConfidence,
            Double maxConfidence,
            int totalArea
    ) {
    }

    private record GeneratedReport(
            String reportTitle,
            String defectSummary,
            String causeAnalysis,
            String riskAssessment,
            String handlingSuggestion,
            String qualityConclusion,
            String riskLevel,
            String aiProvider,
            String aiModel,
            String rawResponse
    ) {
    }
}
