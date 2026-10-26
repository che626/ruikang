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
  <section class="records-workspace">
    <article class="card records-gallery-panel">
      <div class="card-head records-gallery-head">
        <div>
          <span>批量浏览</span>
          <h3>质检记录</h3>
        </div>
        <div class="records-count">
          <strong>{{ filteredRecords.length }}</strong>
          <span v-if="workspaceMode === 'expert'">/ {{ expertSampleSize }} 张</span>
          <span v-else>/ {{ records.length }} 条</span>
        </div>
      </div>

      <div class="records-toolbar">
        <a-segmented
          :value="workspaceMode"
          class="workspace-mode-switch"
          :options="workspaceModeOptions"
          @change="changeWorkspaceMode"
        />
        <a-segmented
          :value="inspectionTypeFilter"
          class="inspection-type-switch"
          :options="inspectionTypeOptions"
          @change="changeInspectionType"
        />
        <a-segmented
          v-if="workspaceMode === 'review'"
          v-model:value="reviewFilter"
          class="records-filter"
          :options="filterOptions"
          size="small"
        />
        <div v-if="workspaceMode === 'expert'" class="expert-scoreboard">
          <div>
            <span>{{ currentExpertTypeLabel }}样本</span>
            <strong>{{ expertRecords.length }} / {{ expertSampleSize }}</strong>
          </div>
          <div>
            <span>已判定</span>
            <strong>{{ expertReviewedCount }}</strong>
          </div>
          <div class="is-correct">
            <span>正确</span>
            <strong>{{ expertCorrectCount }}</strong>
          </div>
          <div class="is-wrong">
            <span>错误</span>
            <strong>{{ expertWrongCount }}</strong>
          </div>
          <div class="is-score">
            <span>最终得分</span>
            <strong>{{ expertScore.toFixed(2) }} / 35</strong>
          </div>
        </div>
        <div v-if="workspaceMode === 'expert'" class="expert-progress">
          <span :style="{ width: `${Math.min(100, (expertReviewedCount / expertSampleSize) * 100)}%` }"></span>
        </div>
        <div class="records-toolbar-meta">
          <span v-if="workspaceMode === 'expert'">
            {{
              expertEvaluationComplete
                ? `评测完成，正确率 ${expertAccuracy.toFixed(1)}%`
                : `${currentExpertTypeLabel}最新20张记录，每张正确计 ${scorePerCorrectSample.toFixed(2)} 分`
            }}
          </span>
          <span v-else>已复核 {{ reviewedCount }} 条</span>
          <a-popconfirm
            title="确定清空全部质检记录吗？"
            ok-text="清空"
            cancel-text="取消"
            :disabled="records.length === 0"
            @confirm="clearAllRecords"
          >
            <a-button
              class="clear-action"
              size="small"
              danger
              :disabled="records.length === 0"
              :loading="clearingRecords"
            >
              清空记录
            </a-button>
          </a-popconfirm>
        </div>
      </div>

      <a-spin :spinning="loadingRecords">
        <div v-if="filteredRecords.length" class="record-gallery">
          <button
            v-for="item in filteredRecords"
            :key="item.recordNo"
            :class="['record-thumb', { active: item.recordNo === currentDetail?.recordNo }]"
            type="button"
            @click="chooseRecord(item.recordNo)"
          >
            <span class="record-thumb-image">
              <img
                v-if="item.thumbnailImagePath"
                :src="filePreviewUrl(item.thumbnailImagePath)"
                :alt="`${item.recordNo} 质检缩略图`"
                loading="lazy"
              />
              <span v-else class="record-thumb-empty">暂无图片</span>
              <em
                :class="[
                  'review-badge',
                  workspaceMode === 'expert'
                    ? expertDecisionTone(item.reviewStatus)
                    : reviewTone(item.reviewStatus),
                ]"
              >
                {{
                  workspaceMode === "expert"
                    ? expertDecisionText(item.reviewStatus)
                    : reviewText(item.reviewStatus)
                }}
              </em>
            </span>
            <span class="record-thumb-body">
              <strong>{{ item.recordNo }}</strong>
              <span>{{ machineJudgeText(item.resultJudge) }}</span>
              <small>{{ inspectTypeText(item.inspectType) }} · {{ formatInspectTime(item.inspectTime) }}</small>
            </span>
          </button>
        </div>
        <div v-else class="empty-card record-gallery-empty">
          {{
            workspaceMode === "expert"
              ? `暂无${currentExpertTypeLabel}记录，请先完成该类样本采集`
              : records.length
                ? "当前筛选条件下没有记录"
                : "暂无质检记录"
          }}
        </div>
      </a-spin>
    </article>

    <article class="card records-detail-panel">
      <div class="card-head records-detail-head">
        <div>
          <span>记录详情与人工复核</span>
          <h3>{{ currentDetail?.recordNo ?? "请选择一条记录" }}</h3>
        </div>
        <div v-if="currentDetail" class="detail-status-group">
          <em :class="['machine-badge', currentDetail.resultJudge === 'PASS' ? 'is-pass' : 'is-fail']">
            {{ currentDetail.resultJudge === "PASS" ? "机器合格" : "机器缺陷" }}
          </em>
          <em :class="['review-badge', reviewTone(currentDetail.reviewStatus)]">
            人工：{{ currentReviewText }}
          </em>
        </div>
      </div>

      <a-spin :spinning="loadingDetail">
        <template v-if="currentDetail">
          <div class="records-image-compare">
            <figure class="records-preview">
              <figcaption>原图</figcaption>
              <div class="records-preview-stage">
                <img
                  v-if="currentDetail.sourceImagePath"
                  :src="filePreviewUrl(currentDetail.sourceImagePath)"
                  alt="质检原图"
                />
                <span v-else>未保存原图</span>
              </div>
            </figure>
            <figure class="records-preview">
              <figcaption>结果图</figcaption>
              <div class="records-preview-stage">
                <img
                  v-if="currentDetail.resultImagePath"
                  :src="filePreviewUrl(currentDetail.resultImagePath)"
                  alt="质检结果图"
                />
                <span v-else>未保存结果图</span>
              </div>
            </figure>
          </div>

          <div class="records-summary">
            <div><span>质检类型</span><strong>{{ currentDetail.inspectType }}</strong></div>
            <div><span>模型结论</span><strong>{{ currentDetail.resultJudge }}</strong></div>
            <div><span>缺陷数量</span><strong>{{ currentDetail.defectCount }}</strong></div>
            <div>
              <span>最高置信度</span>
              <strong>{{ currentDetail.confidenceScore?.toFixed(3) ?? "-" }}</strong>
            </div>
          </div>

          <div v-if="workspaceMode === 'expert'" class="records-review-section expert-review-section">
            <div class="records-review-copy">
              <span>专家评测</span>
              <strong>判断当前模型推理结果是否正确</strong>
            </div>
            <div class="expert-current-result">
              <span>模型结论：{{ currentDetail.resultJudge === "PASS" ? "合格" : "存在缺陷" }}</span>
              <span>当前专家结论：{{ expertDecisionText(currentDetail.reviewStatus) }}</span>
            </div>
            <div class="expert-action-grid">
              <button
                type="button"
                class="expert-action is-correct"
                :disabled="submittingReview"
                @click="submitExpertReview(true)"
              >
                <strong>判定正确</strong>
                <span>模型结果与人工判断一致</span>
              </button>
              <button
                type="button"
                class="expert-action is-wrong"
                :disabled="submittingReview"
                @click="submitExpertReview(false)"
              >
                <strong>判定错误</strong>
                <span>
                  自动记录为{{ currentDetail.resultJudge === "PASS" ? "漏报" : "误报" }}
                </span>
              </button>
            </div>
          </div>

          <div v-else class="records-review-section">
            <div class="records-review-copy">
              <span>人工判定</span>
              <strong>选择该图片的最终复核结论</strong>
            </div>
            <div class="review-option-grid">
              <button
                v-for="option in reviewOptions"
                :key="option.value"
                type="button"
                :title="option.description"
                :class="[
                  'review-option',
                  `review-${option.value.toLowerCase().replace('_', '-')}`,
                  { active: selectedReview === option.value },
                ]"
                @click="selectedReview = option.value"
              >
                <strong>{{ option.label }}</strong>
                <span>{{ option.description }}</span>
              </button>
            </div>
            <div class="review-submit-row">
              <a-input
                v-model:value="reviewRemark"
                :maxlength="255"
                placeholder="复核备注（可选）"
                @press-enter="submitReview"
              />
              <a-button
                type="primary"
                :disabled="!selectedReview"
                :loading="submittingReview"
                @click="submitReview"
              >
                保存人工判定
              </a-button>
            </div>
          </div>

          <a-table
            class="records-detection-table"
            :pagination="false"
            size="small"
            :data-source="currentDetail.detections"
            :row-key="(row) => `${row.defectCode}-${row.x1}-${row.y1}`"
            :locale="{ emptyText: '该记录没有检测框数据' }"
          >
            <a-table-column title="类别" data-index="defectName" key="defectName" />
            <a-table-column title="置信度" key="confidence">
              <template #default="{ record }">{{ record.confidence.toFixed(3) }}</template>
            </a-table-column>
            <a-table-column title="面积" data-index="area" key="area" />
          </a-table>
        </template>

        <div v-else class="empty-card records-detail-empty">
          从左侧记录墙选择一张图片后，可在这里查看结果并进行人工判定。
        </div>
      </a-spin>
    </article>
  </section>
</template>
