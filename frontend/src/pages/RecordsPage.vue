<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { DeleteOutlined, EyeOutlined, ReloadOutlined, SearchOutlined } from "@ant-design/icons-vue";
import {
  clearRecords,
  fetchRecordDetail,
  fetchRecords,
  reviewRecord,
  type RecordDetail,
  type RecordListItem,
  type ReviewRecordPayload,
} from "@/api/records";
import {
  EXPERT_SAMPLE_SIZE,
  SCORE_PER_CORRECT_SAMPLE,
  calculateExpertAccuracy,
  calculateExpertScore,
  isExpertCorrect,
  isExpertReviewed,
  isExpertWrong,
  resolveExpertReviewStatus,
} from "@/utils/expertEvaluation";

type ReviewStatus = ReviewRecordPayload["reviewStatus"];
type ReviewFilter = "ALL" | "PENDING" | ReviewStatus;
type InspectionTypeFilter = "image" | "realtime";
type WorkspaceMode = "review" | "expert";

interface ReviewOption {
  value: ReviewStatus;
  label: string;
  description: string;
}

const reviewOptions: ReviewOption[] = [
  { value: "PASS", label: "通过", description: "人工确认样品合格" },
  { value: "REJECTED", label: "不通过", description: "人工确认样品存在缺陷" },
  { value: "MISSED", label: "漏报", description: "模型未检出实际缺陷" },
  { value: "FALSE_ALARM", label: "误报", description: "模型检出但样品正常" },
];

const filterOptions = [
  { label: "全部", value: "ALL" },
  { label: "待复核", value: "PENDING" },
  { label: "通过", value: "PASS" },
  { label: "不通过", value: "REJECTED" },
  { label: "漏报", value: "MISSED" },
  { label: "误报", value: "FALSE_ALARM" },
];

const workspaceModeOptions = [
  { label: "日常复核", value: "review" },
  { label: "专家评测", value: "expert" },
];

const expertSampleSize = EXPERT_SAMPLE_SIZE;
const scorePerCorrectSample = SCORE_PER_CORRECT_SAMPLE;

const records = ref<RecordListItem[]>([]);
const currentDetail = ref<RecordDetail | null>(null);
const workspaceMode = ref<WorkspaceMode>("review");
const inspectionTypeFilter = ref<InspectionTypeFilter>("image");
const reviewFilter = ref<ReviewFilter>("ALL");
const selectedReview = ref<ReviewStatus | null>(null);
const reviewRemark = ref("");
const loadingRecords = ref(false);
const loadingDetail = ref(false);
const submittingReview = ref(false);
const clearingRecords = ref(false);
const searchKeyword = ref("");
const resultFilter = ref("ALL");
const drawerOpen = ref(false);
const showSearch = ref(true);
const currentPage = ref(1);
const pageSize = 10;

const displayedRecords = computed(() => {
  return records.value.filter((item) => {
    const keyMatch = !searchKeyword.value || item.recordNo.toLowerCase().includes(searchKeyword.value.toLowerCase());
    const resultMatch = resultFilter.value === "ALL" || (resultFilter.value === "FAIL" && item.resultJudge !== "PASS") || (resultFilter.value === "PASS" && item.resultJudge === "PASS");
    const reviewMatch = reviewFilter.value === "ALL" || item.reviewStatus === reviewFilter.value;
    return keyMatch && resultMatch && reviewMatch;
  });
});

const pagedRecords = computed(() => displayedRecords.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize));
watch([searchKeyword, resultFilter, reviewFilter], () => { currentPage.value = 1; });
function formatTime(value: string) { return value ? value.replace("T", " ").slice(0, 19) : "—"; }

function doSearch() {
  // displayedRecords computed auto-updates
}

function doReset() {
  searchKeyword.value = "";
  resultFilter.value = "ALL";
  reviewFilter.value = "ALL";
}

async function openDetail(recordNo: string) {
  drawerOpen.value = true;
  await chooseRecord(recordNo);
}


const inspectionTypeOptions = computed(() => [
  {
    label: `图片质检 ${records.value.filter((item) => item.inspectType === "image").length}`,
    value: "image",
  },
  {
    label: `实时质检 ${records.value.filter((item) => item.inspectType === "realtime").length}`,
    value: "realtime",
  },
]);

const recordsByInspectionType = computed(() =>
  records.value.filter((item) => item.inspectType === inspectionTypeFilter.value),
);

const expertRecords = computed(() => recordsByInspectionType.value.slice(0, expertSampleSize));

const currentExpertTypeLabel = computed(() =>
  inspectionTypeFilter.value === "realtime" ? "实时质检" : "图片质检",
);

const filteredRecords = computed(() => {
  if (workspaceMode.value === "expert") return expertRecords.value;
  if (reviewFilter.value === "ALL") return recordsByInspectionType.value;
  return recordsByInspectionType.value.filter((item) => item.reviewStatus === reviewFilter.value);
});

const currentReviewText = computed(() =>
  currentDetail.value ? reviewText(currentDetail.value.reviewStatus) : "待复核",
);

const reviewedCount = computed(
  () => records.value.filter((item) => item.reviewStatus !== "PENDING").length,
);

const expertReviewedCount = computed(
  () => expertRecords.value.filter((item) => isExpertReviewed(item.reviewStatus)).length,
);

const expertCorrectCount = computed(
  () => expertRecords.value.filter((item) => isExpertCorrect(item.reviewStatus)).length,
);

const expertWrongCount = computed(
  () => expertRecords.value.filter((item) => isExpertWrong(item.reviewStatus)).length,
);

const expertAccuracy = computed(() =>
  calculateExpertAccuracy(expertCorrectCount.value, expertReviewedCount.value),
);

const expertScore = computed(() => calculateExpertScore(expertCorrectCount.value));
const expertEvaluationComplete = computed(
  () => expertRecords.value.length === expertSampleSize && expertReviewedCount.value === expertSampleSize,
);

function reviewText(status: string) {
  const labels: Record<string, string> = {
    PENDING: "待复核",
    CONFIRMED: "已确认",
    PASS: "通过",
    REJECTED: "不通过",
    MISSED: "漏报",
    FALSE_ALARM: "误报",
  };
  return labels[status] ?? status;
}

function machineJudgeText(resultJudge: string) {
  return resultJudge === "PASS" ? "机器判定：合格" : "机器判定：缺陷";
}

function inspectTypeText(inspectType: string) {
  return inspectType === "realtime" ? "实时质检" : "图片质检";
}

function expertDecisionText(status: string) {
  if (isExpertCorrect(status)) return "正确";
  if (isExpertWrong(status)) return "错误";
  return "未判定";
}

function expertDecisionTone(status: string) {
  if (isExpertCorrect(status)) return "is-pass";
  if (isExpertWrong(status)) return "is-rejected";
  return "is-pending";
}

function reviewTone(status: string) {
  if (status === "PASS") return "is-pass";
  if (status === "REJECTED") return "is-rejected";
  if (status === "MISSED") return "is-missed";
  if (status === "FALSE_ALARM") return "is-false-alarm";
  return "is-pending";
}

function syncReviewForm(detail: RecordDetail) {
  const status = reviewOptions.some((option) => option.value === detail.reviewStatus)
    ? (detail.reviewStatus as ReviewStatus)
    : null;
  selectedReview.value = status;
  reviewRemark.value = detail.reviewRemark ?? "";
}

async function loadRecords(targetRecordNo?: string) {
  loadingRecords.value = true;
  try {
    records.value = await fetchRecords();
    const targetRecord = targetRecordNo
      ? records.value.find((item) => item.recordNo === targetRecordNo)
      : undefined;

    if (targetRecord) {
      inspectionTypeFilter.value = targetRecord.inspectType === "realtime" ? "realtime" : "image";
    } else if (!recordsByInspectionType.value.length) {
      inspectionTypeFilter.value = records.value.some((item) => item.inspectType === "image")
        ? "image"
        : "realtime";
    }

    const chosenRecordNo = targetRecord?.recordNo
      ?? recordsByInspectionType.value.find((item) => item.reviewStatus === "PENDING")?.recordNo
      ?? recordsByInspectionType.value[0]?.recordNo;

    if (chosenRecordNo) {
      await chooseRecord(chosenRecordNo);
    } else {
      currentDetail.value = null;
      selectedReview.value = null;
      reviewRemark.value = "";
    }
  } finally {
    loadingRecords.value = false;
  }
}

async function changeInspectionType(value: InspectionTypeFilter) {
  inspectionTypeFilter.value = value;
  reviewFilter.value = "ALL";
  const candidateRecords = workspaceMode.value === "expert"
    ? expertRecords.value
    : recordsByInspectionType.value;
  const firstRecord = candidateRecords.find((item) => !isExpertReviewed(item.reviewStatus))
    ?? candidateRecords[0];

  if (firstRecord) {
    await chooseRecord(firstRecord.recordNo);
  } else {
    currentDetail.value = null;
    selectedReview.value = null;
    reviewRemark.value = "";
  }
}

async function changeWorkspaceMode(value: WorkspaceMode) {
  workspaceMode.value = value;
  reviewFilter.value = "ALL";

  if (value === "expert") {
    inspectionTypeFilter.value = "realtime";
    const nextRecord = expertRecords.value.find((item) => !isExpertReviewed(item.reviewStatus))
      ?? expertRecords.value[0];
    if (nextRecord) {
      await chooseRecord(nextRecord.recordNo);
    } else {
      currentDetail.value = null;
    }
    return;
  }

  if (!recordsByInspectionType.value.length) {
    inspectionTypeFilter.value = records.value.some((item) => item.inspectType === "image")
      ? "image"
      : "realtime";
  }
  const nextRecord = recordsByInspectionType.value.find((item) => item.reviewStatus === "PENDING")
    ?? recordsByInspectionType.value[0];
  if (nextRecord) {
    await chooseRecord(nextRecord.recordNo);
  }
}

async function chooseRecord(recordNo: string) {
  loadingDetail.value = true;
  try {
    const detail = await fetchRecordDetail(recordNo);
    currentDetail.value = detail;
    syncReviewForm(detail);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "加载记录详情失败");
  } finally {
    loadingDetail.value = false;
  }
}

function scrollActiveRecordInsideGallery() {
  const container = document.querySelector<HTMLElement>(".record-gallery");
  const activeItem = container?.querySelector<HTMLElement>(".record-thumb.active");
  if (!container || !activeItem) return;

  const itemTop = activeItem.offsetTop;
  const itemBottom = itemTop + activeItem.offsetHeight;
  const visibleTop = container.scrollTop;
  const visibleBottom = visibleTop + container.clientHeight;

  if (itemTop < visibleTop) {
    container.scrollTo({ top: itemTop, behavior: "smooth" });
  } else if (itemBottom > visibleBottom) {
    container.scrollTo({
      top: itemBottom - container.clientHeight,
      behavior: "smooth",
    });
  }
}

function findNextUnreviewedExpertRecord(currentRecordNo: string) {
  const items = expertRecords.value;
  if (items.length < 2) return undefined;
  const currentIndex = items.findIndex((item) => item.recordNo === currentRecordNo);
  const startIndex = currentIndex >= 0 ? currentIndex : -1;

  for (let offset = 1; offset <= items.length; offset += 1) {
    const candidate = items[(startIndex + offset + items.length) % items.length];
    if (candidate.recordNo !== currentRecordNo && !isExpertReviewed(candidate.reviewStatus)) {
      return candidate;
    }
  }
  return undefined;
}

async function switchRecordByKeyboard(direction: -1 | 1) {
  if (!filteredRecords.value.length || loadingDetail.value) return;

  const currentIndex = Math.max(
    0,
    filteredRecords.value.findIndex((item) => item.recordNo === currentDetail.value?.recordNo),
  );
  const nextIndex = Math.min(
    filteredRecords.value.length - 1,
    Math.max(0, currentIndex + direction),
  );
  if (nextIndex === currentIndex) return;

  await chooseRecord(filteredRecords.value[nextIndex].recordNo);
  await nextTick();
  scrollActiveRecordInsideGallery();
}

function handleRecordKeyboardNavigation(event: KeyboardEvent) {
  if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
  const target = event.target as HTMLElement | null;
  if (
    target?.matches("input, textarea, select, [contenteditable='true']")
    || target?.closest(".ant-select, .ant-input, .ant-modal")
  ) {
    return;
  }
  if (!filteredRecords.value.length) return;

  event.preventDefault();
  void switchRecordByKeyboard(event.key === "ArrowUp" ? -1 : 1);
}

async function submitReview() {
  if (!currentDetail.value || !selectedReview.value) {
    message.warning("请先选择人工判定结果");
    return;
  }

  submittingReview.value = true;
  try {
    const selectedOption = reviewOptions.find((option) => option.value === selectedReview.value);
    const detail = await reviewRecord(currentDetail.value.recordNo, {
      reviewStatus: selectedReview.value,
      reviewRemark: reviewRemark.value.trim() || selectedOption?.description,
    });
    currentDetail.value = detail;
    syncReviewForm(detail);
    await refreshRecordList(detail.recordNo);
    message.success(`人工判定已保存为“${selectedOption?.label}”`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "保存人工判定失败");
  } finally {
    submittingReview.value = false;
  }
}

async function submitExpertReview(correct: boolean) {
  if (!currentDetail.value) return;

  const currentRecordNo = currentDetail.value.recordNo;
  const machinePassed = currentDetail.value.resultJudge === "PASS";
  const reviewStatus: ReviewStatus = resolveExpertReviewStatus(
    currentDetail.value.resultJudge,
    correct,
  );

  submittingReview.value = true;
  try {
    const detail = await reviewRecord(currentRecordNo, {
      reviewStatus,
      reviewRemark: correct
        ? "专家评测：模型判定正确"
        : `专家评测：模型判定错误（${machinePassed ? "漏报" : "误报"}）`,
    });
    currentDetail.value = detail;
    syncReviewForm(detail);
    await refreshRecordList(detail.recordNo);

    const nextPending = findNextUnreviewedExpertRecord(currentRecordNo);
    if (nextPending) {
      await chooseRecord(nextPending.recordNo);
      await nextTick();
      scrollActiveRecordInsideGallery();
    }

    message.success(
      nextPending
        ? `已判定为“${correct ? "正确" : "错误"}”，已自动进入下一张`
        : `评测完成，最终得分 ${expertScore.value.toFixed(2)} / 35`,
    );
  } catch (error) {
    message.error(error instanceof Error ? error.message : "保存专家评测结果失败");
  } finally {
    submittingReview.value = false;
  }
}

async function refreshRecordList(selectedRecordNo: string) {
  records.value = await fetchRecords();
  if (
    reviewFilter.value !== "ALL"
    && !filteredRecords.value.some((item) => item.recordNo === selectedRecordNo)
  ) {
    reviewFilter.value = "ALL";
  }
}

async function clearAllRecords() {
  clearingRecords.value = true;
  try {
    await clearRecords();
    records.value = [];
    currentDetail.value = null;
    selectedReview.value = null;
    reviewRemark.value = "";
    message.success("质检记录已清空");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "清空记录失败");
  } finally {
    clearingRecords.value = false;
  }
}

function filePreviewUrl(relativePath: string | null) {
  return relativePath ? `/api/files/content?relativePath=${encodeURIComponent(relativePath)}` : "";
}

function formatInspectTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

onMounted(() => {
  window.addEventListener("keydown", handleRecordKeyboardNavigation);
  loadRecords().catch((error) => {
    message.error(error instanceof Error ? error.message : "加载记录失败");
  });
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", handleRecordKeyboardNavigation);
});
</script>

<template>
  <section class="records-page">
    <div v-if="showSearch" class="records-search">
      <a-form layout="inline" @finish="doSearch">
        <a-form-item label="记录编号" name="keyword">
          <a-input v-model:value="searchKeyword" allow-clear placeholder="请输入编号或检测摘要" class="keyword-input" />
        </a-form-item>
        <a-form-item label="检测结果" name="resultJudge">
          <a-select v-model:value="resultFilter" placeholder="全部" class="filter-select" :options="[{ label: '全部', value: 'ALL' }, { label: '合格', value: 'PASS' }, { label: '不合格', value: 'FAIL' }]" />
        </a-form-item>
        <a-form-item label="复核状态" name="reviewStatus">
          <a-select v-model:value="reviewFilter" placeholder="全部" class="filter-select" :options="filterOptions" />
        </a-form-item>
        <a-form-item class="query-actions">
          <a-button type="primary" html-type="submit"><SearchOutlined />搜索</a-button>
          <a-button @click="doReset"><ReloadOutlined />重置</a-button>
        </a-form-item>
      </a-form>
    </div>

    <article class="records-list">
      <div class="records-toolbar">
        <h3>质检记录</h3>
        <div class="toolbar-actions">
          <a-popconfirm title="确认清空全部质检记录？" ok-text="清空" cancel-text="取消" @confirm="clearAllRecords">
            <a-button danger :disabled="records.length === 0" :loading="clearingRecords"><DeleteOutlined />清空记录</a-button>
          </a-popconfirm>
          <a-tooltip :title="showSearch ? '收起查询' : '展开查询'">
            <a-button shape="circle" :aria-label="showSearch ? '收起查询' : '展开查询'" @click="showSearch = !showSearch"><SearchOutlined /></a-button>
          </a-tooltip>
          <a-tooltip title="刷新列表">
            <a-button shape="circle" aria-label="刷新列表" :loading="loadingRecords" @click="loadRecords()"><ReloadOutlined /></a-button>
          </a-tooltip>
        </div>
      </div>

      <a-table :data-source="pagedRecords" :loading="loadingRecords" :pagination="false" row-key="recordNo" size="middle" :scroll="{ x: 1030 }" :locale="{ emptyText: records.length ? '没有符合查询条件的记录' : '暂无质检记录' }">
        <a-table-column title="序号" key="index" :width="64" align="center">
          <template #default="{ index }">{{ (currentPage - 1) * pageSize + index + 1 }}</template>
        </a-table-column>
        <a-table-column title="记录编号" data-index="recordNo" key="recordNo" :width="215">
          <template #default="{ record }"><a-button type="link" class="record-link" @click="openDetail(record.recordNo)">{{ record.recordNo }}</a-button></template>
        </a-table-column>
        <a-table-column title="检测方式" key="inspectType" :width="110">
          <template #default="{ record }">{{ inspectTypeText(record.inspectType) }}</template>
        </a-table-column>
        <a-table-column title="检测结果" key="resultJudge" :width="100" align="center">
          <template #default="{ record }"><a-tag :color="record.resultJudge === 'PASS' ? 'success' : 'error'">{{ record.resultJudge === "PASS" ? "合格" : record.resultJudge === "FAIL" ? "不合格" : record.resultJudge }}</a-tag></template>
        </a-table-column>
        <a-table-column title="缺陷数量" data-index="defectCount" key="defectCount" :width="100" align="center" />
        <a-table-column title="复核状态" key="reviewStatus" :width="105" align="center">
          <template #default="{ record }"><a-tag :color="record.reviewStatus === 'FALSE_ALARM' ? 'warning' : record.reviewStatus === 'PENDING' ? 'default' : 'processing'">{{ reviewText(record.reviewStatus) }}</a-tag></template>
        </a-table-column>
        <a-table-column title="检测摘要" data-index="resultSummary" key="resultSummary" :ellipsis="true" />
        <a-table-column title="检测时间" key="inspectTime" :width="180">
          <template #default="{ record }">{{ formatTime(record.inspectTime) }}</template>
        </a-table-column>
        <a-table-column title="操作" key="actions" :width="100" align="center" fixed="right">
          <template #default="{ record }"><a-button class="view-action" type="link" size="small" @click="openDetail(record.recordNo)"><EyeOutlined />查看</a-button></template>
        </a-table-column>
      </a-table>

      <div class="records-pagination">
        <a-pagination v-model:current="currentPage" :total="displayedRecords.length" :page-size="pageSize" :show-size-changer="false" show-quick-jumper :show-total="(total: number) => `共 ${total} 条记录`" />
      </div>
    </article>

    <a-drawer v-model:open="drawerOpen" title="质检记录详情" :width="760" class="record-detail-drawer">
      <a-spin :spinning="loadingDetail">
        <template v-if="currentDetail">
          <a-descriptions bordered size="small" :column="2" class="record-descriptions">
            <a-descriptions-item label="记录编号" :span="2">{{ currentDetail.recordNo }}</a-descriptions-item>
            <a-descriptions-item label="检测方式">{{ inspectTypeText(currentDetail.inspectType) }}</a-descriptions-item>
            <a-descriptions-item label="检测时间">{{ formatTime(currentDetail.inspectTime) }}</a-descriptions-item>
            <a-descriptions-item label="检测结果"><a-tag :color="currentDetail.resultJudge === 'PASS' ? 'success' : 'error'">{{ currentDetail.resultJudge === "PASS" ? "合格" : "不合格" }}</a-tag></a-descriptions-item>
            <a-descriptions-item label="复核状态">{{ reviewText(currentDetail.reviewStatus) }}</a-descriptions-item>
            <a-descriptions-item label="缺陷数量">{{ currentDetail.defectCount }}</a-descriptions-item>
            <a-descriptions-item label="置信度">{{ currentDetail.confidenceScore === null ? "—" : currentDetail.confidenceScore.toFixed(3) }}</a-descriptions-item>
            <a-descriptions-item v-if="currentDetail.reviewRemark" label="复核备注" :span="2">{{ currentDetail.reviewRemark }}</a-descriptions-item>
          </a-descriptions>

          <h4 class="detail-section-title">检测图像</h4>
          <div class="record-image-compare">
            <div class="record-preview">
              <div class="preview-label">原图</div>
              <img v-if="currentDetail.sourceImagePath" :src="filePreviewUrl(currentDetail.sourceImagePath)" alt="质检原图" />
              <div v-else class="preview-empty">暂无原图</div>
            </div>
            <div class="record-preview">
              <div class="preview-label">结果图</div>
              <img v-if="currentDetail.resultImagePath" :src="filePreviewUrl(currentDetail.resultImagePath)" alt="质检结果图" />
              <div v-else class="preview-empty">暂无结果图</div>
            </div>
          </div>

          <h4 class="detail-section-title">缺陷明细</h4>
          <a-table :pagination="false" size="small" :data-source="currentDetail.detections" :row-key="(row: RecordDetail['detections'][number]) => `${row.defectCode}-${row.x1}-${row.y1}`" :locale="{ emptyText: '无缺陷明细' }">
            <a-table-column title="缺陷类别" data-index="defectName" key="defectName" />
            <a-table-column title="置信度" key="confidence"><template #default="{ record }">{{ record.confidence.toFixed(3) }}</template></a-table-column>
            <a-table-column title="面积" data-index="area" key="area" />
          </a-table>

          <div class="records-review-section" style="margin-top:16px">
            <div class="records-review-copy">
              <span>人工判定</span>
              <strong>选择该图片的最终复核结论</strong>
            </div>
            <div class="review-option-grid">
              <button
                v-for="option in reviewOptions"
                :key="option.value"
                type="button"
                :class="['review-option', 'review-' + option.value.toLowerCase().replace('_', '-'), { active: selectedReview === option.value }]"
                @click="selectedReview = option.value"
              >
                <strong>{{ option.label }}</strong>
                <span>{{ option.description }}</span>
              </button>
            </div>
            <div class="review-submit-row" style="margin-top:10px">
              <a-input v-model:value="reviewRemark" :maxlength="255" placeholder="复核备注（可选）" @press-enter="submitReview" />
              <a-button type="primary" :disabled="!selectedReview" :loading="submittingReview" @click="submitReview">保存人工判定</a-button>
            </div>
          </div>

          <div class="record-review-actions"><a-button @click="drawerOpen = false">关闭</a-button></div>
        </template>
        <a-empty v-else-if="!loadingDetail" description="暂无记录详情" />
      </a-spin>
    </a-drawer>
  </section>
</template>

<style scoped>
.records-page { display: flex; flex-direction: column; gap: 12px; color: #606266; font-size: 14px; }
.records-search, .records-list { background: #fff; border: 1px solid #e6ebf5; border-radius: 4px; }
.records-search { padding: 20px 20px 6px; }
.records-search :deep(.ant-form-item) { margin-bottom: 14px; margin-right: 24px; }
.records-search :deep(.ant-form-item-label > label) { color: #606266; font-size: 14px; }
.keyword-input { width: 220px; }
.filter-select { width: 135px; }
.query-actions :deep(.ant-form-item-control-input-content) { display: flex; gap: 10px; }
.records-list { padding: 0 20px; }
.records-toolbar { display: flex; align-items: center; justify-content: space-between; min-height: 66px; gap: 16px; }
.records-toolbar h3 { margin: 0; font-size: 15px; font-weight: 600; color: #303133; }
.toolbar-actions { display: flex; align-items: center; gap: 10px; }
.toolbar-actions :deep(.ant-btn-circle) { min-width: 32px; width: 32px; height: 32px; color: #606266; }
.records-list :deep(.ant-table-thead > tr > th) { background: #f8f8f9; color: #606266; font-weight: 600; font-size: 14px; padding: 12px 10px; }
.records-list :deep(.ant-table-tbody > tr > td) { padding: 13px 10px; color: #606266; font-size: 14px; }
.records-list :deep(.ant-table-tbody > tr:hover > td) { background: #f5f7fa; }
.records-list :deep(.ant-tag) { margin: 0; border-radius: 3px; font-size: 12px; }
.record-link, .view-action { color: #409eff; }
.record-link { cursor: pointer; padding: 0; height: auto; }
.records-pagination { display: flex; justify-content: flex-end; padding: 18px 0; }
.record-descriptions { margin-bottom: 20px; }
.detail-section-title { margin: 22px 0 12px; color: #303133; font-weight: 600; font-size: 14px; }
.record-image-compare { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.record-preview { overflow: hidden; border: 1px solid #ebeef5; border-radius: 4px; background: #fafafa; }
.preview-label { padding: 9px 12px; border-bottom: 1px solid #ebeef5; color: #606266; background: #fff; }
.record-preview img { display: block; width: 100%; height: 225px; object-fit: contain; }
.preview-empty { display: grid; place-items: center; height: 225px; color: #909399; }
.record-review-actions { display: flex; justify-content: flex-end; gap: 10px; padding-top: 24px; }
@media (max-width: 800px) {
  .records-search { padding: 16px 16px 2px; }
  .records-search :deep(.ant-form-item) { margin-right: 16px; }
  .records-list { padding: 0 14px; }
  .records-toolbar { flex-wrap: wrap; padding: 14px 0; }
  .records-pagination { overflow-x: auto; justify-content: flex-start; }
}
.records-review-section { margin-top: 20px; padding-top: 16px; border-top: 1px solid #ebeef5; }
.records-review-copy { display: grid; gap: 5px; margin-bottom: 12px; }
.records-review-copy > span { font-size: 14px; font-weight: 500; color: #303133; }
.records-review-copy > strong { font-size: 12px; font-weight: 400; color: #909399; }
.review-option-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.review-option { display: grid; gap: 4px; text-align: left; padding: 10px 12px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; color: #606266; }
.review-option strong { font-size: 13px; font-weight: 500; }
.review-option span { font-size: 12px; color: #909399; }
.review-option.active { border-color: #409eff; background: #ecf5ff; color: #409eff; }
.review-submit-row { display: flex; gap: 8px; }
</style>
