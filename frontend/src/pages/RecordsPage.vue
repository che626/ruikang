<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { clearRecords, fetchRecordDetail, fetchRecords, reviewRecord, type RecordDetail, type RecordListItem } from "@/api/records";

const pageSize = 7;

const records = ref<RecordListItem[]>([]);
const currentDetail = ref<RecordDetail | null>(null);
const currentPage = ref(1);
const loading = ref(false);

const totalPages = computed(() => Math.max(1, Math.ceil(records.value.length / pageSize)));
const pageOptions = computed(() =>
  Array.from({ length: totalPages.value }, (_, index) => ({
    label: `第 ${index + 1} 页`,
    value: index + 1,
  })),
);
const pagedRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize;
  return records.value.slice(start, start + pageSize);
});

const currentReviewText = computed(() =>
  currentDetail.value ? reviewText(currentDetail.value.reviewStatus, currentDetail.value.resultJudge) : "真实缺陷",
);

function reviewText(status: string, resultJudge = "FAIL") {
  if (resultJudge === "PASS") return "正常";
  return status === "FALSE_ALARM" ? "误报" : "真实缺陷";
}

function pageOfRecord(recordNo: string) {
  const index = records.value.findIndex((item) => item.recordNo === recordNo);
  return index < 0 ? 1 : Math.floor(index / pageSize) + 1;
}

async function loadRecords(targetRecordNo?: string) {
  records.value = await fetchRecords();
  const chosenRecordNo = targetRecordNo ?? records.value.find((item) => item.resultJudge === "FAIL")?.recordNo ?? records.value[0]?.recordNo;
  currentPage.value = chosenRecordNo ? pageOfRecord(chosenRecordNo) : 1;
  currentDetail.value = chosenRecordNo ? await fetchRecordDetail(chosenRecordNo) : null;
}

async function chooseRecord(recordNo: string) {
  currentDetail.value = await fetchRecordDetail(recordNo);
}

async function goToPage(page: number) {
  currentPage.value = Math.min(Math.max(1, page), totalPages.value);
  const firstRecord = pagedRecords.value[0];
  if (firstRecord) {
    await chooseRecord(firstRecord.recordNo);
  }
}

async function markFalseAlarm() {
  if (!currentDetail.value) return;
  loading.value = true;
  try {
    currentDetail.value = await reviewRecord(currentDetail.value.recordNo, {
      reviewStatus: "FALSE_ALARM",
      reviewRemark: "人工标记误报",
    });
    message.success("已标记为误报");
    await loadRecords(currentDetail.value.recordNo);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "标记失败");
  } finally {
    loading.value = false;
  }
}

async function clearAllRecords() {
  loading.value = true;
  try {
    await clearRecords();
    records.value = [];
    currentDetail.value = null;
    currentPage.value = 1;
    message.success("质检记录已清空");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "清空记录失败");
  } finally {
    loading.value = false;
  }
}

function filePreviewUrl(relativePath: string | null) {
  return relativePath ? `/api/files/content?relativePath=${encodeURIComponent(relativePath)}` : "";
}

onMounted(() => {
  loadRecords().catch((error) => {
    message.error(error instanceof Error ? error.message : "加载记录失败");
  });
});
</script>

<template>
  <section class="split-layout">
    <article class="card sidebar-list records-sidebar">
      <div class="card-head">
        <div>
          <span>记录列表</span>
          <h3>质检记录</h3>
        </div>
        <div class="head-actions">
          <em>{{ records.length }} 条</em>
          <a-button
            class="clear-action"
            size="small"
            type="primary"
            :disabled="records.length === 0"
            :loading="loading"
            @click="clearAllRecords"
          >
            清除记录
          </a-button>
        </div>
      </div>

      <div class="record-stack paginated-record-list">
        <button
          v-for="item in pagedRecords"
          :key="item.recordNo"
          :class="['record-button', { active: item.recordNo === currentDetail?.recordNo }]"
          @click="chooseRecord(item.recordNo)"
        >
          <strong>{{ item.recordNo }}</strong>
          <span>{{ item.resultJudge }} / {{ reviewText(item.reviewStatus, item.resultJudge) }}</span>
        </button>
      </div>

      <div v-if="records.length" class="record-pagination">
        <a-button size="small" :disabled="currentPage <= 1" @click="goToPage(currentPage - 1)">上一页</a-button>
        <a-select
          v-model:value="currentPage"
          size="small"
          class="page-select"
          :options="pageOptions"
          @change="goToPage"
        />
        <a-button size="small" :disabled="currentPage >= totalPages" @click="goToPage(currentPage + 1)">下一页</a-button>
      </div>
      <div v-else class="empty-card compact-empty">暂无质检记录。</div>
    </article>

    <article class="card detail-panel">
      <div class="card-head">
        <div>
          <span>记录详情</span>
          <h3>{{ currentDetail?.recordNo ?? "暂无记录" }}</h3>
        </div>
        <em>{{ currentDetail?.resultJudge ?? "EMPTY" }}</em>
      </div>

      <template v-if="currentDetail">
        <div class="image-compare compact">
          <div class="preview-card">
            <span>原图</span>
            <img :src="filePreviewUrl(currentDetail.sourceImagePath)" alt="原图" />
          </div>
          <div class="preview-card">
            <span>结果图</span>
            <img :src="filePreviewUrl(currentDetail.resultImagePath)" alt="结果图" />
          </div>
        </div>

        <div class="detail-grid">
          <div class="summary-item"><span>类型</span><strong>{{ currentDetail.inspectType }}</strong></div>
          <div class="summary-item"><span>结论</span><strong>{{ currentDetail.resultJudge }}</strong></div>
          <div class="summary-item"><span>状态</span><strong>{{ currentReviewText }}</strong></div>
          <div class="summary-item"><span>缺陷数量</span><strong>{{ currentDetail.defectCount }}</strong></div>
        </div>

        <a-table
          :pagination="false"
          size="small"
          :data-source="currentDetail.detections"
          :row-key="(row) => `${row.defectCode}-${row.x1}-${row.y1}`"
        >
          <a-table-column title="类别" data-index="defectName" key="defectName" />
          <a-table-column title="置信度" key="confidence">
            <template #default="{ record }">{{ record.confidence.toFixed(3) }}</template>
          </a-table-column>
          <a-table-column title="面积" data-index="area" key="area" />
        </a-table>

        <div class="review-box">
          <a-button
            class="false-alarm-action"
            type="primary"
            :loading="loading"
            :disabled="currentDetail.resultJudge === 'PASS' || currentDetail.reviewStatus === 'FALSE_ALARM'"
            @click="markFalseAlarm"
          >
            {{
              currentDetail.resultJudge === "PASS"
                ? "无缺陷记录"
                : currentDetail.reviewStatus === "FALSE_ALARM"
                  ? "已标记误报"
                  : "标记误报"
            }}
          </a-button>
        </div>
      </template>

      <div v-else class="empty-card">暂无质检记录，请先执行图片质检或实时质检。</div>
    </article>
  </section>
</template>
