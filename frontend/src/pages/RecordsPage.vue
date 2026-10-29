<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
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

const displayedRecords = computed(() => {
  return records.value.filter((item) => {
    const keyMatch = !searchKeyword.value || item.recordNo.toLowerCase().includes(searchKeyword.value.toLowerCase());
    const resultMatch = resultFilter.value === "ALL" || (resultFilter.value === "FAIL" && item.resultJudge !== "PASS") || (resultFilter.value === "PASS" && item.resultJudge === "PASS");
    const reviewMatch = reviewFilter.value === "ALL" || item.reviewStatus === reviewFilter.value;
    return keyMatch && resultMatch && reviewMatch;
  });
});

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
  <section class="rp-page">
    <div class="card rp-filter-bar">
      <div class="rp-filter-row">
        <div class="rp-filter-item">
          <label>记录编号</label>
          <a-input v-model:value="searchKeyword" placeholder="请输入编号或检测摘要" allow-clear />
        </div>
        <div class="rp-filter-item">
          <label>检测结果</label>
          <a-select v-model:value="resultFilter" style="width:120px">
            <a-select-option value="ALL">全部</a-select-option>
            <a-select-option value="FAIL">不合格</a-select-option>
            <a-select-option value="PASS">合格</a-select-option>
          </a-select>
        </div>
        <div class="rp-filter-item">
          <label>复核状态</label>
          <a-select v-model:value="reviewFilter" style="width:120px">
            <a-select-option value="ALL">全部</a-select-option>
            <a-select-option value="PENDING">待复核</a-select-option>
            <a-select-option value="PASS">通过</a-select-option>
            <a-select-option value="REJECTED">不通过</a-select-option>
            <a-select-option value="MISSED">漏报</a-select-option>
            <a-select-option value="FALSE_ALARM">误报</a-select-option>
          </a-select>
        </div>
        <div class="rp-filter-actions">
          <a-button type="primary" @click="doSearch">搜索</a-button>
          <a-button @click="doReset">重置</a-button>
        </div>
      </div>
    </div>

    <div class="card rp-table-card">
      <div class="rp-table-head">
        <span class="rp-table-title">质检记录</span>
        <div class="rp-table-actions">
          <a-popconfirm title="确定清空全部质检记录吗？" ok-text="清空" cancel-text="取消" :disabled="records.length === 0" @confirm="clearAllRecords">
            <a-button danger size="small" :disabled="records.length === 0" :loading="clearingRecords">清空记录</a-button>
          </a-popconfirm>
          <a-button size="small" @click="loadRecords()">刷新</a-button>
        </div>
      </div>
      <a-spin :spinning="loadingRecords">
        <a-table
          :data-source="displayedRecords"
          row-key="recordNo"
          :pagination="{ pageSize: 10, showTotal: (total) => '共 ' + total + ' 条记录', showQuickJumper: true }"
          size="middle"
          :locale="{ emptyText: '暂无质检记录' }"
        >
          <a-table-column title="序号" key="index" width="60px">
            <template #default="{ index }">{{ index + 1 }}</template>
          </a-table-column>
          <a-table-column title="记录编号" key="recordNo">
            <template #default="{ record }">
              <a class="rp-record-link" @click="openDetail(record.recordNo)">{{ record.recordNo }}</a>
            </template>
          </a-table-column>
          <a-table-column title="检测方式" data-index="inspectType" key="inspectType" width="100px" />
          <a-table-column title="检测结果" key="resultJudge" width="90px">
            <template #default="{ record }">
              <span :class="['rp-badge', record.resultJudge === 'PASS' ? 'rp-badge-pass' : 'rp-badge-fail']">
                {{ record.resultJudge === 'PASS' ? '合格' : '不合格' }}
              </span>
            </template>
          </a-table-column>
          <a-table-column title="缺陷数量" data-index="defectCount" key="defectCount" width="80px" />
          <a-table-column title="复核状态" key="reviewStatus" width="90px">
            <template #default="{ record }">
              <span :class="['rp-review-tag', reviewTone(record.reviewStatus)]">{{ reviewText(record.reviewStatus) }}</span>
            </template>
          </a-table-column>
          <a-table-column title="检测摘要" key="summary">
            <template #default="{ record }">
              <span class="rp-summary">{{ record.summary ?? '--' }}</span>
            </template>
          </a-table-column>
          <a-table-column title="检测时间" key="inspectTime" width="160px">
            <template #default="{ record }">{{ formatInspectTime(record.inspectTime) }}</template>
          </a-table-column>
          <a-table-column title="操作" key="action" width="80px">
            <template #default="{ record }">
              <a class="rp-view-link" @click="openDetail(record.recordNo)">查看</a>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </div>

    <a-drawer v-model:open="drawerOpen" title="记录详情" placement="right" width="600" :destroy-on-close="true">
      <a-spin :spinning="loadingDetail">
        <template v-if="currentDetail">
          <div class="rp-detail-badges">
            <em :class="['machine-badge', currentDetail.resultJudge === 'PASS' ? 'is-pass' : 'is-fail']">
              {{ currentDetail.resultJudge === 'PASS' ? '机器合格' : '机器缺陷' }}
            </em>
            <em :class="['review-badge', reviewTone(currentDetail.reviewStatus)]">
              人工：{{ currentReviewText }}
            </em>
          </div>
          <div class="records-image-compare" style="margin:16px 0">
            <figure class="records-preview">
              <figcaption>原图</figcaption>
              <div class="records-preview-stage">
                <img v-if="currentDetail.sourceImagePath" :src="filePreviewUrl(currentDetail.sourceImagePath)" alt="质检原图" />
                <span v-else>未保存原图</span>
              </div>
            </figure>
            <figure class="records-preview">
              <figcaption>结果图</figcaption>
              <div class="records-preview-stage">
                <img v-if="currentDetail.resultImagePath" :src="filePreviewUrl(currentDetail.resultImagePath)" alt="质检结果图" />
                <span v-else>未保存结果图</span>
              </div>
            </figure>
          </div>
          <div class="records-summary">
            <div><span>质检类型</span><strong>{{ currentDetail.inspectType }}</strong></div>
            <div><span>模型结论</span><strong>{{ currentDetail.resultJudge }}</strong></div>
            <div><span>缺陷数量</span><strong>{{ currentDetail.defectCount }}</strong></div>
            <div><span>最高置信度</span><strong>{{ currentDetail.confidenceScore?.toFixed(3) ?? '-' }}</strong></div>
          </div>
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
          <a-table
            class="records-detection-table"
            :pagination="false"
            size="small"
            :data-source="currentDetail.detections"
            :row-key="(row) => row.defectCode + '-' + row.x1 + '-' + row.y1"
            :locale="{ emptyText: '该记录没有检测框数据' }"
          >
            <a-table-column title="类别" data-index="defectName" key="defectName" />
            <a-table-column title="置信度" key="confidence">
              <template #default="{ record: r }">{{ r.confidence.toFixed(3) }}</template>
            </a-table-column>
            <a-table-column title="面积" data-index="area" key="area" />
          </a-table>
        </template>
        <div v-else class="empty-card" style="min-height:200px">加载中...</div>
      </a-spin>
    </a-drawer>
  </section>
</template>