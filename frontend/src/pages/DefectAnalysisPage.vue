<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import {
  clearQualityReports,
  fetchQualityReport,
  fetchQualityReports,
  generateOverallQualityReport,
  generateQualityReport,
  type QualityReport,
  type QualityReportListItem,
} from "@/api/aigc-report";
import { fetchDashboardOverview, type DashboardOverview } from "@/api/dashboard";
import { fetchDefectTypes, type DefectTypeItem } from "@/api/catalog";
import { fetchRecords, type RecordListItem } from "@/api/records";

const records = ref<RecordListItem[]>([]);
const qualityReports = ref<QualityReportListItem[]>([]);
const defectTypes = ref<DefectTypeItem[]>([]);
const dashboardOverview = ref<DashboardOverview | null>(null);
const selectedRecordNo = ref("");
const selectedReport = ref<QualityReport | null>(null);
const analysisMode = ref<"single" | "overall">("single");
const loading = ref(false);
const reportLoading = ref(false);
const historyLoading = ref(false);

const defectRecords = computed(() => {
  const failRecords = records.value.filter((record) => record.resultJudge === "FAIL");
  return failRecords.length ? failRecords : records.value;
});

const recordOptions = computed(() =>
  defectRecords.value.slice(0, 40).map((record) => ({
    label: `${record.recordNo} · ${record.resultSummary}`,
    value: record.recordNo,
  })),
);

const selectedRecord = computed(() => records.value.find((record) => record.recordNo === selectedRecordNo.value) ?? null);

const defectNameMap = computed(() => {
  const map = new Map<string, string>();
  defectTypes.value.forEach((item) => map.set(item.defectCode, item.defectName));
  return map;
});

const defectRank = computed(() =>
  (dashboardOverview.value?.defectRanks ?? [])
    .filter((item) => item.count > 0)
    .map((item) => ({
      code: item.code,
      name: defectNameMap.value.get(item.code) ?? item.code,
      count: item.count,
    }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 7),
);

const maxDefectCount = computed(() => Math.max(1, ...defectRank.value.map((item) => item.count)));

const PIE_COLORS = ["#1677ff", "#52c41a", "#ff4d4f", "#fa8c16", "#13c2c2", "#722ed1", "#eb2f96"];

const pieSlices = computed(() => {
  const total = defectRank.value.reduce((s, i) => s + i.count, 0);
  if (!total) return [];
  const cx = 90, cy = 90, r = 80;
  let angle = -Math.PI / 2;
  return defectRank.value.map((item, idx) => {
    const slice = (item.count / total) * 2 * Math.PI;
    const x1 = cx + r * Math.cos(angle);
    const y1 = cy + r * Math.sin(angle);
    angle += slice;
    const x2 = cx + r * Math.cos(angle);
    const y2 = cy + r * Math.sin(angle);
    const large = slice > Math.PI ? 1 : 0;
    const color = defectTypes.value.find(d => d.defectCode === item.code)?.displayColor ?? PIE_COLORS[idx % PIE_COLORS.length];
    return {
      d: `M ${cx} ${cy} L ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2} Z`,
      color,
      pct: Math.round((item.count / total) * 100),
      name: item.name,
      count: item.count,
    };
  });
});

const overview = computed(() => {
  const failCount = records.value.filter((record) => record.resultJudge === "FAIL").length;
  const falseAlarm = records.value.filter((record) => record.reviewStatus === "FALSE_ALARM").length;
  return {
    total: records.value.length,
    failCount,
    falseAlarm,
    reports: qualityReports.value.length,
  };
});

const generateButtonText = computed(() =>
  analysisMode.value === "single" ? "生成单条分析报告" : "生成整体分析报告",
);

async function loadData() {
  loading.value = true;
  try {
    const [recordData, reportData, dashboardData, defectTypeData] = await Promise.all([
      fetchRecords(),
      fetchQualityReports(),
      fetchDashboardOverview(),
      fetchDefectTypes(),
    ]);
    records.value = recordData;
    qualityReports.value = reportData;
    dashboardOverview.value = dashboardData;
    defectTypes.value = defectTypeData;
    if (!selectedRecordNo.value && defectRecords.value.length > 0) {
      selectedRecordNo.value = defectRecords.value[0].recordNo;
    }
    if (!selectedReport.value && reportData.length > 0) {
      selectedReport.value = await fetchQualityReport(reportData[0].reportNo);
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : "缺陷分析数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function handleGenerateReport() {
  if (analysisMode.value === "single" && !selectedRecordNo.value) {
    message.warning("请先选择一条检测记录");
    return;
  }
  if (analysisMode.value === "overall" && records.value.length === 0) {
    message.warning("暂无质检记录，无法生成整体分析报告");
    return;
  }
  reportLoading.value = true;
  try {
    selectedReport.value = analysisMode.value === "single"
      ? await generateQualityReport(selectedRecordNo.value)
      : await generateOverallQualityReport();
    qualityReports.value = await fetchQualityReports();
    message.success("AIGC质检分析报告已生成");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "报告生成失败");
  } finally {
    reportLoading.value = false;
  }
}

async function chooseReport(reportNo: string) {
  historyLoading.value = true;
  try {
    selectedReport.value = await fetchQualityReport(reportNo);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "报告加载失败");
  } finally {
    historyLoading.value = false;
  }
}

async function handleClearReports() {
  historyLoading.value = true;
  try {
    await clearQualityReports();
    qualityReports.value = [];
    selectedReport.value = null;
    message.success("历史报告已清空");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "清空历史报告失败");
  } finally {
    historyLoading.value = false;
  }
}

function downloadSelectedReport() {
  if (!selectedReport.value) return;
  const report = selectedReport.value;
  const content = [
    report.reportTitle,
    "",
    `报告编号：${report.reportNo}`,
    `分析对象：${report.recordNo}`,
    `风险等级：${riskText(report.riskLevel)}`,
    `生成模型：${report.aiProvider} / ${report.aiModel}`,
    `生成时间：${report.createTime}`,
    "",
    "一、缺陷综合摘要",
    report.defectSummary,
    "",
    "二、原因分析",
    report.causeAnalysis,
    "",
    "三、风险评估",
    report.riskAssessment,
    "",
    "四、处理建议",
    report.handlingSuggestion,
    "",
    "五、质检结论",
    report.qualityConclusion,
    "",
  ].join("\n");
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  const safeTitle = report.reportTitle.replace(/[\\/:*?"<>|]/g, "_").slice(0, 60);
  anchor.href = url;
  anchor.download = `${safeTitle || report.reportNo}.txt`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function riskText(riskLevel: string) {
  const map: Record<string, string> = {
    LOW: "低风险",
    MEDIUM: "中风险",
    HIGH: "高风险",
    CRITICAL: "严重风险",
  };
  return map[riskLevel] ?? riskLevel;
}

function riskClass(riskLevel: string) {
  if (riskLevel === "CRITICAL" || riskLevel === "HIGH") return "danger";
  if (riskLevel === "MEDIUM") return "warning";
  return "success";
}

onMounted(loadData);
</script>

<template>
  <section class="defect-analysis-page">
    <div class="analysis-layout">
      <article class="card analysis-control-panel">
        <div class="card-head">
          <div>
            <span>AI DEFECT INTELLIGENCE</span>
            <h3>缺陷分析</h3>
          </div>
          <em>DeepSeek</em>
        </div>

        <div class="analysis-metric-grid">
          <div class="summary-item"><span>质检记录</span><strong>{{ overview.total }}</strong></div>
          <div class="summary-item"><span>异常记录</span><strong>{{ overview.failCount }}</strong></div>
          <div class="summary-item"><span>误报标记</span><strong>{{ overview.falseAlarm }}</strong></div>
          <div class="summary-item"><span>AIGC报告</span><strong>{{ overview.reports }}</strong></div>
        </div>

        <div class="report-generate-box">
          <a-radio-group v-model:value="analysisMode" button-style="solid" class="analysis-mode-switch">
            <a-radio-button value="single">单条记录分析</a-radio-button>
            <a-radio-button value="overall">整体质检分析</a-radio-button>
          </a-radio-group>

          <div v-if="analysisMode === 'single'" class="report-select-row">
            <a-select
              v-model:value="selectedRecordNo"
              class="report-select"
              :options="recordOptions"
              :loading="loading"
              placeholder="选择最近检测到的缺陷记录"
            />
            <a-button type="primary" :loading="reportLoading" :disabled="!selectedRecordNo" @click="handleGenerateReport">
              {{ generateButtonText }}
            </a-button>
          </div>

          <div v-else class="report-select-row overall-report-row">
            <div class="overall-target-card">
              <span>分析范围</span>
              <strong>当前全部质检记录</strong>
              <small>{{ overview.total }} 条记录 / {{ overview.failCount }} 条异常 / {{ defectRank.length }} 类缺陷</small>
            </div>
            <a-button type="primary" :loading="reportLoading" :disabled="records.length === 0" @click="handleGenerateReport">
              {{ generateButtonText }}
            </a-button>
          </div>

          <p>
            系统会结合缺陷类别、置信度、检测来源、空间分布和历史质量态势，生成原因分析、风险评估、处理建议与质检结论。
          </p>
        </div>

        <div v-if="analysisMode === 'single' && selectedRecord" class="selected-record-card">
          <span>当前分析对象</span>
          <strong>{{ selectedRecord.recordNo }}</strong>
          <p>{{ selectedRecord.resultJudge }} / {{ selectedRecord.resultSummary }}</p>
        </div>
        <div v-else-if="analysisMode === 'overall'" class="selected-record-card">
          <span>当前分析对象</span>
          <strong>整体质检记录</strong>
          <p>用于判断近期布面质量趋势、主要缺陷风险和生产处理优先级。</p>
        </div>
        <div v-else class="selected-record-card">
          <span>当前分析对象</span>
          <strong>暂无可分析记录</strong>
          <p>执行图片质检或实时质检后，可在这里选择记录生成报告。</p>
        </div>

        <div class="report-history-panel">
          <div class="panel-title-line compact">
            <div>
              <span>历史报告</span>
              <strong>自动保存的AIGC分析记录</strong>
            </div>
            <div class="head-actions">
              <em>{{ qualityReports.length }} 份</em>
              <a-button
                class="clear-action"
                size="small"
                type="primary"
                :disabled="qualityReports.length === 0"
                :loading="historyLoading"
                @click="handleClearReports"
              >
                清除
              </a-button>
            </div>
          </div>
          <div v-if="qualityReports.length" class="report-history-list">
            <button
              v-for="report in qualityReports"
              :key="report.reportNo"
              :class="['report-history-item', { active: report.reportNo === selectedReport?.reportNo }]"
              type="button"
              @click="chooseReport(report.reportNo)"
            >
              <strong>{{ report.reportTitle }}</strong>
              <span>{{ report.recordNo }} · {{ riskText(report.riskLevel) }} · {{ report.createTime }}</span>
            </button>
          </div>
          <div v-else class="report-empty-state small">暂无历史报告。</div>
        </div>
      </article>

      <article class="card analysis-report-panel">
        <div v-if="selectedReport" class="report-view">
          <div class="report-view-head">
            <div>
              <span>{{ selectedReport.reportNo }} / {{ selectedReport.recordNo }}</span>
              <h4>{{ selectedReport.reportTitle }}</h4>
            </div>
            <div class="report-view-actions">
              <a-button class="clear-action" size="small" type="primary" @click="downloadSelectedReport">下载报告</a-button>
              <b :class="['risk-badge', riskClass(selectedReport.riskLevel)]">{{ riskText(selectedReport.riskLevel) }}</b>
            </div>
          </div>

          <div class="report-section-grid">
            <section class="report-section full">
              <span>缺陷综合摘要</span>
              <p>{{ selectedReport.defectSummary }}</p>
            </section>
            <section class="report-section">
              <span>原因分析</span>
              <p>{{ selectedReport.causeAnalysis }}</p>
            </section>
            <section class="report-section">
              <span>风险评估</span>
              <p>{{ selectedReport.riskAssessment }}</p>
            </section>
            <section class="report-section">
              <span>处理建议</span>
              <p>{{ selectedReport.handlingSuggestion }}</p>
            </section>
            <section class="report-section">
              <span>质检结论</span>
              <p>{{ selectedReport.qualityConclusion }}</p>
            </section>
          </div>

          <div class="report-meta-row">
            <span>{{ selectedReport.aiProvider }}</span>
            <span>{{ selectedReport.aiModel }}</span>
            <span>{{ selectedReport.createTime }}</span>
          </div>
        </div>
        <div v-else class="report-empty-state">选择分析模式并生成报告后，这里会展示AIGC质检决策建议。</div>
      </article>

      <article class="card analysis-rank-panel">
        <div class="card-head">
          <div>
            <span>DEFECT DISTRIBUTION</span>
            <h3>缺陷热度</h3>
          </div>
          <em>{{ defectRank.length }} 类</em>
        </div>
        <div v-if="defectRank.length" class="defect-rank-with-pie">
          <div class="defect-heat-list">
            <div v-for="item in defectRank" :key="item.code" class="defect-heat-item">
              <div class="defect-heat-meta">
                <strong>{{ item.name }}</strong>
                <span>{{ item.code }}</span>
              </div>
              <div class="defect-heat-track">
                <i :style="{ width: `${Math.max(8, Math.round((item.count / maxDefectCount) * 100))}%` }"></i>
              </div>
              <b>{{ item.count }}</b>
            </div>
          </div>
          <div class="defect-pie-wrap">
            <svg viewBox="0 0 180 180" class="defect-pie-svg">
              <path
                v-for="(slice, idx) in pieSlices"
                :key="idx"
                :d="slice.d"
                :fill="slice.color"
                opacity="0.9"
              />
            </svg>
            <div class="defect-pie-legend">
              <div v-for="(slice, idx) in pieSlices" :key="idx" class="pie-legend-item">
                <span class="pie-legend-dot" :style="{ background: slice.color }"></span>
                <span class="pie-legend-name">{{ slice.name }}</span>
                <span class="pie-legend-count">{{ slice.count }}</span>
                <span class="pie-legend-pct">{{ slice.pct }}%</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="report-empty-state small">暂无异常记录。</div>
      </article>
    </div>
  </section>
</template>
