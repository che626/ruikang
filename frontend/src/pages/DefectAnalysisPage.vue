<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { DeleteOutlined, DownloadOutlined, FileTextOutlined, ReloadOutlined } from "@ant-design/icons-vue";
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

const pieTotalCount = computed(() => defectRank.value.reduce((sum, item) => sum + item.count, 0));

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
          <h3>缺陷分析</h3>
          <a-button size="small" :loading="loading" @click="loadData">
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
        </div>

        <div class="analysis-metric-grid">
          <div class="summary-item"><span>质检记录</span><strong>{{ overview.total }}</strong></div>
          <div class="summary-item"><span>异常记录</span><strong>{{ overview.failCount }}</strong></div>
          <div class="summary-item"><span>误报标记</span><strong>{{ overview.falseAlarm }}</strong></div>
          <div class="summary-item"><span>分析报告</span><strong>{{ overview.reports }}</strong></div>
        </div>

        <div class="report-generate-box">
          <a-radio-group v-model:value="analysisMode" button-style="solid" class="analysis-mode-switch">
            <a-radio-button value="single">单条记录分析</a-radio-button>
            <a-radio-button value="overall">整体质检分析</a-radio-button>
          </a-radio-group>

          <div class="report-generation-stage">
            <a-select
              v-if="analysisMode === 'single'"
              :value="selectedRecordNo || undefined"
              @update:value="selectedRecordNo = $event || ''"
              class="report-select"
              :options="recordOptions"
              :loading="loading"
              placeholder="请选择检测记录"
            />
            <div v-else class="overall-target-card">
              <span>分析范围</span>
              <strong>当前全部质检记录</strong>
              <small>{{ overview.total }} 条记录 / {{ overview.failCount }} 条异常 / {{ defectRank.length }} 类缺陷</small>
            </div>
            <a-button
              type="primary"
              :loading="reportLoading"
              :disabled="analysisMode === 'single' ? !selectedRecordNo : records.length === 0"
              @click="handleGenerateReport"
            >
              <template #icon><FileTextOutlined /></template>
              {{ generateButtonText }}
            </a-button>
          </div>

        </div>

        <div v-if="analysisMode === 'single' && selectedRecord" class="selected-record-card">
          <span>检测记录</span>
          <strong>{{ selectedRecord.recordNo }}</strong>
          <p>{{ selectedRecord.resultJudge }} / {{ selectedRecord.resultSummary }}</p>
        </div>
        <div v-else-if="analysisMode === 'overall'" class="selected-record-card">
          <span>分析范围</span>
          <strong>整体质检记录</strong>
          <p>{{ overview.total }} 条记录，{{ overview.failCount }} 条异常</p>
        </div>
        <div v-else class="selected-record-card">
          <span>检测记录</span>
          <strong>暂无可分析记录</strong>
        </div>

        <div class="report-history-panel">
          <div class="panel-title-line compact">
            <strong>历史报告</strong>
            <div class="head-actions">
              <em>{{ qualityReports.length }} 份</em>
              <a-button
                size="small"
                danger
                :disabled="qualityReports.length === 0"
                :loading="historyLoading"
                @click="handleClearReports"
              >
                <template #icon><DeleteOutlined /></template>
                清空
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
          <div v-else class="report-empty-state small">暂无历史报告</div>
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
              <a-button size="small" @click="downloadSelectedReport">
                <template #icon><DownloadOutlined /></template>
                下载报告
              </a-button>
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
        <div v-else class="report-empty-state analysis-empty">
          <FileTextOutlined />
          <span>暂无分析报告</span>
        </div>
      </article>

      <article class="card analysis-rank-panel">
        <div class="card-head">
          <h3>缺陷分布</h3>
          <span class="header-count">{{ defectRank.length }} 类</span>
        </div>
        <div v-if="defectRank.length" class="defect-distribution-content">
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
          <div class="defect-pie-panel">
            <div class="defect-pie-chart">
              <svg viewBox="0 0 180 180" role="img" :aria-label="`缺陷分布饼图，共 ${pieTotalCount} 个缺陷`">
                <circle v-if="pieSlices.length === 1" cx="90" cy="90" r="80" :fill="pieSlices[0].color" />
                <path
                  v-for="slice in pieSlices"
                  v-else
                  :key="slice.name"
                  :d="slice.d"
                  :fill="slice.color"
                  stroke="#fff"
                  stroke-width="2"
                />
              </svg>
            </div>
            <div class="defect-pie-legend">
              <div v-for="slice in pieSlices" :key="slice.name" class="defect-pie-legend-item">
                <i :style="{ backgroundColor: slice.color }"></i>
                <span>{{ slice.name }}</span>
                <b>{{ slice.count }} <small>{{ slice.pct }}%</small></b>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="report-empty-state small">暂无异常记录</div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.analysis-layout {
  grid-template-columns: minmax(300px, 360px) minmax(0, 1fr);
  grid-template-rows: auto auto;
  gap: 16px;
  align-items: stretch;
}

.analysis-control-panel,
.analysis-report-panel {
  grid-row: 1;
  height: clamp(760px, calc(100dvh - 210px), 1080px);
  min-height: 760px;
  max-height: 1080px;
  overflow: hidden;
}

.analysis-control-panel {
  grid-column: 1;
}

.analysis-report-panel {
  grid-column: 2;
}

.analysis-rank-panel {
  grid-row: 2;
  grid-column: 1 / -1;
}

.analysis-control-panel,
.analysis-report-panel,
.analysis-rank-panel {
  gap: 16px;
}

.header-count {
  color: #909399;
  font-size: 13px;
}

.analysis-metric-grid {
  gap: 0;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.analysis-metric-grid .summary-item {
  padding: 12px 16px;
  border: 0;
  border-radius: 0;
  background: #fafbfc;
}

.analysis-metric-grid .summary-item:nth-child(odd) {
  border-right: 1px solid #ebeef5;
}

.analysis-metric-grid .summary-item:nth-child(-n + 2) {
  border-bottom: 1px solid #ebeef5;
}

.analysis-metric-grid strong {
  color: #303133;
  font-size: 22px;
  font-weight: 500;
}

.report-generate-box,
.report-history-panel {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: #fff;
}

.report-generate-box {
  flex: 0 0 auto;
  gap: 12px;
}

.report-generation-stage {
  display: grid;
  grid-template-rows: 76px 32px;
  gap: 12px;
  min-width: 0;
}

.report-generation-stage > .report-select {
  align-self: center;
  width: 100%;
}

.report-generation-stage > .ant-btn {
  justify-self: start;
  height: 32px;
}

.selected-record-card,
.overall-target-card {
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafbfc;
}

.selected-record-card {
  flex: 0 0 78px;
  height: 78px;
  min-height: 78px;
  overflow: hidden;
}

.selected-record-card strong,
.overall-target-card strong {
  font-size: 13px;
  font-weight: 500;
}

.selected-record-card strong,
.selected-record-card p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.overall-target-card {
  display: flex;
  height: 76px;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
  padding: 8px 12px;
}

.overall-target-card strong,
.overall-target-card small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-history-panel {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  height: auto;
  min-height: 160px;
  overflow: hidden;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}

.report-history-panel .panel-title-line {
  flex: 0 0 auto;
}

.report-history-list {
  display: grid;
  grid-auto-rows: 76px;
  align-content: start;
  flex: 1 1 auto;
  min-height: 0;
  gap: 8px;
  max-height: none;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.report-history-panel > .report-empty-state {
  flex: 1 1 auto;
  min-height: 0;
}

.report-history-item {
  height: 76px;
  min-height: 76px;
  align-content: center;
  overflow: hidden;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fff;
}

.report-history-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.report-history-item strong {
  font-size: 13px;
  font-weight: 500;
}

.report-history-item span {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.report-view {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  gap: 18px;
  border: 0;
  border-radius: 0;
  background: #fff;
}

.report-view-head {
  flex: 0 0 auto;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
}

.report-view-head h4 {
  margin-top: 8px;
  font-size: 18px;
  font-weight: 500;
}

.report-view-head span {
  color: #909399;
  font-size: 12px;
}

.report-section-grid {
  flex: 1 1 auto;
  min-height: 0;
  align-content: start;
  grid-template-columns: 1fr;
  gap: 18px;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 6px;
  scrollbar-gutter: stable;
}

.report-section {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: #fff;
}

.report-section > span {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.report-section p {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.9;
  color: #606266;
  white-space: pre-line;
}

.report-meta-row {
  flex: 0 0 auto;
  min-height: 42px;
  align-items: center;
  margin-top: auto;
  gap: 14px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}

.report-meta-row span {
  padding: 0;
  border: 0;
  background: #fff;
  font-size: 12px;
  color: #909399;
}

.analysis-empty {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 16px;
  border: 0;
}

.analysis-empty .anticon {
  color: #c0c4cc;
  font-size: 48px;
}

.analysis-empty span {
  color: #909399;
  font-size: 14px;
}

.defect-heat-item {
  padding: 8px 0;
  border: 0;
  border-radius: 0;
  background: #fff;
}

.defect-heat-meta strong {
  font-weight: 500;
}

.defect-heat-track {
  height: 8px;
  background: #ebeef5;
}

.defect-heat-track i {
  background: #409eff;
  box-shadow: none;
}

.defect-heat-item b {
  color: #606266;
  font-weight: 500;
}

.defect-distribution-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 460px;
  align-items: center;
  gap: 28px;
  min-height: 220px;
}

.defect-pie-panel {
  display: grid;
  grid-template-columns: 260px max-content;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-width: 0;
  padding-left: 24px;
  border-left: 1px solid #ebeef5;
}

.defect-pie-chart {
  width: min(100%, 260px);
  max-width: 100%;
  aspect-ratio: 1;
  justify-self: center;
}

.defect-pie-chart svg {
  display: block;
  width: 100%;
  height: 100%;
}

.defect-pie-legend {
  display: grid;
  gap: 10px;
  width: max-content;
  max-width: 100%;
  min-width: 0;
}

.defect-pie-legend-item {
  display: grid;
  grid-template-columns: 9px auto auto;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: #606266;
  font-size: 12px;
}

.defect-pie-legend-item i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.defect-pie-legend-item span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.defect-pie-legend-item b {
  color: #303133;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.defect-pie-legend-item small {
  margin-left: 4px;
  color: #909399;
  font-weight: 400;
}

@media (max-width: 1100px) {
  .analysis-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto auto auto;
  }

  .analysis-control-panel,
  .analysis-report-panel,
  .analysis-rank-panel {
    grid-column: 1;
  }

  .analysis-control-panel {
    grid-row: 1;
  }

  .analysis-control-panel,
  .analysis-report-panel {
    height: 780px;
    min-height: 780px;
    max-height: 780px;
  }

  .analysis-report-panel {
    grid-row: 2;
  }

  .analysis-rank-panel {
    grid-row: 3;
  }

  .report-view-head {
    flex-wrap: wrap;
  }

  .defect-distribution-content {
    grid-template-columns: 1fr;
  }

  .defect-pie-panel {
    grid-template-columns: minmax(220px, 260px) max-content;
    justify-content: center;
    padding: 20px 0 0;
    border-top: 1px solid #ebeef5;
    border-left: 0;
  }
}

@media (max-width: 560px) {
  .defect-pie-panel {
    grid-template-columns: minmax(0, 1fr);
    justify-items: center;
  }

  .defect-pie-chart {
    width: min(210px, 70vw);
  }

  .defect-pie-legend {
    width: 100%;
  }
}
</style>
