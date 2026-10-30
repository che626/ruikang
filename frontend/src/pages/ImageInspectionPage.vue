<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { DeleteOutlined, InboxOutlined, PlayCircleOutlined, PictureOutlined } from "@ant-design/icons-vue";
import InspectionResultViewer from "@/components/InspectionResultViewer.vue";
import type { UploadProps } from "ant-design-vue";
import {
  submitClientImageResults,
  type ClientImageInspectionRecordPayload,
  type ImageInspectionRecord,
  type ImageInspectionUploadResult,
} from "@/api/image-inspection";
import { browserInferenceClient } from "@/inference/browserInferenceClient";
import { renderDetectionImage } from "@/inference/rendering";

type SelectedImage = {
  uid: string;
  name: string;
  file: File;
  url: string;
};

// Keep each multipart request comfortably below the server request-size limit.
const SAVE_BATCH_SIZE = 4;

const selectedImages = ref<SelectedImage[]>([]);
const loading = ref(false);
const inspectionProgress = ref("");
const lastResult = ref<ImageInspectionUploadResult | null>(null);
const selectedRecordNo = ref("");
const resultViewMode = ref<"focus" | "full">("focus");

const currentRecord = computed(() =>
  lastResult.value?.records.find((item) => item.recordNo === selectedRecordNo.value) ?? lastResult.value?.records[0] ?? null,
);

watch(() => currentRecord.value?.recordNo, () => { resultViewMode.value = "focus"; });

const beforeUpload: UploadProps["beforeUpload"] = (file) => {
  const rawFile = file as unknown as File;
  const uid = "uid" in file ? String(file.uid) : `${rawFile.name}-${Date.now()}`;
  selectedImages.value = [
    ...selectedImages.value,
    {
      uid,
      name: file.name,
      file: rawFile,
      url: URL.createObjectURL(rawFile),
    },
  ];
  return false;
};

function removeSelected(uid: string) {
  const target = selectedImages.value.find((item) => item.uid === uid);
  if (target) URL.revokeObjectURL(target.url);
  selectedImages.value = selectedImages.value.filter((item) => item.uid !== uid);
}

function clearSelected() {
  selectedImages.value.forEach((item) => URL.revokeObjectURL(item.url));
  selectedImages.value = [];
}

async function inferImageWithRecovery(file: File, confidenceThreshold: number, iouThreshold: number) {
  let lastError: unknown;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const bitmap = await createImageBitmap(file);
    try {
      return await browserInferenceClient.infer(bitmap, confidenceThreshold, iouThreshold);
    } catch (error) {
      lastError = error;
      bitmap.close();
      const message = error instanceof Error ? error.message : "";
      if (attempt === 0 && (message.includes("响应超时") || message.includes("Worker"))) {
        inspectionProgress.value = "正在恢复推理引擎";
        continue;
      }
      throw error;
    }
  }
  throw lastError;
}

async function runInspection() {
  if (!selectedImages.value.length) {
    message.warning("请先选择至少一张图片");
    return;
  }

  loading.value = true;
  lastResult.value = null;
  selectedRecordNo.value = "";
  try {
    inspectionProgress.value = "正在加载模型";
    const { manifest } = await browserInferenceClient.ensureReady(true);
    const completedRecords: ImageInspectionRecord[] = [];
    const completedTaskNos: string[] = [];
    let batchRecords: ClientImageInspectionRecordPayload[] = [];
    let batchSourceFiles: File[] = [];
    let batchResultFiles: File[] = [];

    for (let index = 0; index < selectedImages.value.length; index += 1) {
      const item = selectedImages.value[index];
      inspectionProgress.value = `正在检测 ${index + 1}/${selectedImages.value.length}`;
      const displayBitmap = await createImageBitmap(item.file);
      try {
        const inferenceResult = await inferImageWithRecovery(
          item.file,
          manifest.confidenceThreshold,
          manifest.iouThreshold,
        );
        const resultBlob = await renderDetectionImage(
          displayBitmap,
          inferenceResult.imageWidth,
          inferenceResult.imageHeight,
          inferenceResult.detections,
        );
        const baseName = item.name.replace(/\.[^.]+$/, "") || `image-${index + 1}`;
        batchResultFiles.push(
          new File([resultBlob], `${baseName}-result.jpg`, { type: resultBlob.type }),
        );
        batchSourceFiles.push(item.file);
        batchRecords.push({
          originalFileName: item.name,
          imageWidth: inferenceResult.imageWidth,
          imageHeight: inferenceResult.imageHeight,
          preprocessTimeMs: inferenceResult.preprocessTimeMs,
          inferenceTimeMs: inferenceResult.inferenceTimeMs,
          postprocessTimeMs: inferenceResult.postprocessTimeMs,
          providerStrategy: inferenceResult.providerStrategy,
          detections: inferenceResult.detections,
        });
      } finally {
        displayBitmap.close();
      }

      const shouldSaveBatch = batchRecords.length >= SAVE_BATCH_SIZE
        || index === selectedImages.value.length - 1;
      if (!shouldSaveBatch) continue;

      inspectionProgress.value = `正在保存 ${index + 1}/${selectedImages.value.length}`;
      const batchResult = await submitClientImageResults(
        manifest.sha256,
        batchRecords,
        batchSourceFiles,
        batchResultFiles,
        `批量图片质检，第 ${completedTaskNos.length + 1} 批`,
      );
      completedTaskNos.push(batchResult.taskNo);
      completedRecords.push(...batchResult.records);

      lastResult.value = {
        taskNo: completedTaskNos.length === 1
          ? completedTaskNos[0]
          : `${completedTaskNos[0]} 等 ${completedTaskNos.length} 批`,
        taskStatus: "FINISHED",
        fileCount: completedRecords.length,
        records: [...completedRecords],
      };
      if (!selectedRecordNo.value) {
        selectedRecordNo.value = completedRecords[0]?.recordNo ?? "";
      }

      batchRecords = [];
      batchSourceFiles = [];
      batchResultFiles = [];
    }

    message.success(`图片质检完成，共处理 ${completedRecords.length} 张图片`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "图片质检失败");
  } finally {
    inspectionProgress.value = "";
    loading.value = false;
  }
}

function filePreviewUrl(relativePath: string | null) {
  return relativePath ? `/api/files/content?relativePath=${encodeURIComponent(relativePath)}` : "";
}

function confidenceText(record: ImageInspectionRecord) {
  return record.confidenceScore == null ? "-" : record.confidenceScore.toFixed(3);
}

function reviewText(status: string, resultJudge = "FAIL") {
  if (resultJudge === "PASS") return "正常";
  return status === "FALSE_ALARM" ? "误报" : "真实缺陷";
}

function scrollActiveResultInsideList() {
  const container = document.querySelector<HTMLElement>(".inspection-result-scroll");
  const activeItem = container?.querySelector<HTMLElement>(".record-button.active");
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

function switchResultByKeyboard(direction: -1 | 1) {
  const records = lastResult.value?.records ?? [];
  if (!records.length) return;

  const currentIndex = Math.max(
    0,
    records.findIndex((record) => record.recordNo === selectedRecordNo.value),
  );
  const nextIndex = Math.min(records.length - 1, Math.max(0, currentIndex + direction));
  if (nextIndex === currentIndex) return;

  selectedRecordNo.value = records[nextIndex].recordNo;
  void nextTick(scrollActiveResultInsideList);
}

function handleResultKeyboardNavigation(event: KeyboardEvent) {
  if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
  const target = event.target as HTMLElement | null;
  if (
    target?.matches("input, textarea, select, [contenteditable='true']")
    || target?.closest(".ant-select, .ant-input, .ant-modal")
  ) {
    return;
  }
  if (!lastResult.value?.records.length) return;

  event.preventDefault();
  switchResultByKeyboard(event.key === "ArrowUp" ? -1 : 1);
}

onMounted(() => window.addEventListener("keydown", handleResultKeyboardNavigation));

onBeforeUnmount(() => {
  window.removeEventListener("keydown", handleResultKeyboardNavigation);
  clearSelected();
});
</script>

<template>
  <section class="workbench-grid image-inspection-grid">
    <div class="inspection-left-column">
    <article class="card panel-column image-upload-panel">
      <div class="card-head">
        <h3>图片上传</h3>
        <span class="header-count">已选 {{ selectedImages.length }} 张</span>
      </div>

      <a-upload-dragger class="inspection-uploader" :disabled="loading" :multiple="true" :show-upload-list="false" :before-upload="beforeUpload">
        <p class="ant-upload-drag-icon"><InboxOutlined /></p>
        <p class="ant-upload-text">点击或拖拽图片到此处上传</p>
        <p class="ant-upload-hint">JPG / PNG，可一次选择多张，系统自动分批处理</p>
      </a-upload-dragger>

      <div class="upload-preview-box fixed-upload-preview">
        <div class="upload-preview-head">
          <span>待检图片</span>
          <strong>{{ selectedImages.length }} 张</strong>
        </div>
        <div v-if="selectedImages.length" class="upload-preview-grid upload-preview-scroll">
          <figure v-for="item in selectedImages" :key="item.uid" class="uploaded-image-card">
            <img :src="item.url" :alt="item.name" />
            <figcaption>
              <span>{{ item.name }}</span>
              <button type="button" :disabled="loading" @click="removeSelected(item.uid)">移除</button>
            </figcaption>
          </figure>
        </div>
        <div v-else class="upload-preview-scroll-empty">选择图片后在此查看预览</div>
      </div>

      <div class="button-row">
        <a-button type="primary" :loading="loading" :disabled="selectedImages.length === 0" @click="runInspection">
          <template #icon><PlayCircleOutlined /></template>
          {{ loading ? inspectionProgress : "开始质检" }}
        </a-button>
        <a-button :disabled="loading || selectedImages.length === 0" @click="clearSelected">
          <template #icon><DeleteOutlined /></template>
          清空
        </a-button>
      </div>
    </article>

    <article class="card panel-column inspection-records-panel">
      <div class="card-head">
        <h3>检测记录</h3>
        <span class="header-count">共 {{ lastResult?.records.length ?? 0 }} 条</span>
      </div>

      <div v-if="lastResult?.records.length" class="record-stack inspection-result-scroll">
        <button
          v-for="record in lastResult.records"
          :key="record.recordNo"
          :class="['record-button', { active: record.recordNo === currentRecord?.recordNo }]"
          @click="selectedRecordNo = record.recordNo"
        >
          <strong>{{ record.recordNo }}</strong>
          <span>{{ record.resultJudge }} / {{ record.resultSummary }}</span>
        </button>
      </div>
      <div v-else class="empty-card compact-empty inspection-records-empty">暂无检测记录</div>
    </article>
    </div>

    <article class="card inspection-result-panel">
      <div class="inspection-result-main">
        <div class="card-head">
          <h3>检测结果</h3>
          <div class="inspection-result-actions">
          <a-radio-group v-if="!loading && currentRecord?.detections.length" v-model:value="resultViewMode" size="small" button-style="solid">
            <a-radio-button value="focus">聚焦缺陷</a-radio-button>
            <a-radio-button value="full">完整图片</a-radio-button>
          </a-radio-group>
          <a-tag v-if="loading" color="processing">正在处理 {{ selectedImages.length }} 张图片</a-tag>
          <a-tag v-else-if="currentRecord" :color="currentRecord.resultJudge === 'PASS' ? 'success' : 'error'">
            {{ currentRecord.resultJudge === 'PASS' ? "合格" : "异常" }}
          </a-tag>
          </div>
        </div>

        <div v-if="currentRecord?.sourceImagePath || currentRecord?.resultImagePath" class="image-result-single inspection-image-stage">
          <InspectionResultViewer
            :key="currentRecord.recordNo"
            :source-image-url="filePreviewUrl(currentRecord.sourceImagePath)"
            :result-image-url="filePreviewUrl(currentRecord.resultImagePath)"
            :detections="currentRecord.detections"
            :mode="resultViewMode"
          />
        </div>
        <div v-else class="empty-card inspection-empty">
          <PictureOutlined />
          <span>{{ loading ? "正在检测，完成后显示结果" : "暂无检测结果" }}</span>
        </div>
      </div>

      <div class="inspection-result-details">
        <div v-if="lastResult" class="summary-strip inspection-task-strip">
          <div>
            <span>任务编号</span>
            <strong>{{ lastResult.taskNo }}</strong>
          </div>
          <div>
            <span>状态</span>
            <strong>{{ lastResult.taskStatus }}</strong>
          </div>
          <div>
            <span>文件数</span>
            <strong>{{ lastResult.fileCount }}</strong>
          </div>
        </div>
        <template v-if="currentRecord">
          <div class="detail-grid">
            <div class="summary-item">
              <span>缺陷数量</span>
              <strong>{{ currentRecord.defectCount }}</strong>
            </div>
            <div class="summary-item">
              <span>最高置信度</span>
              <strong>{{ confidenceText(currentRecord) }}</strong>
            </div>
            <div class="summary-item">
              <span>复核状态</span>
              <strong>{{ reviewText(currentRecord.reviewStatus, currentRecord.resultJudge) }}</strong>
            </div>
            <div class="summary-item">
              <span>推理耗时</span>
              <strong>{{ currentRecord.inferenceTimeMs ?? 0 }} ms</strong>
            </div>
          </div>

          <div class="detection-table">
            <a-table
              :pagination="false"
              size="small"
              :data-source="currentRecord.detections"
              :row-key="(row: ImageInspectionRecord['detections'][number]) => `${row.defectCode}-${row.x1}-${row.y1}`"
            >
              <a-table-column title="类别" data-index="defectName" key="defectName" />
              <a-table-column title="置信度" key="confidence">
                <template #default="{ record }">
                  {{ record.confidence.toFixed(3) }}
                </template>
              </a-table-column>
              <a-table-column title="框坐标" key="box">
                <template #default="{ record }">
                  {{ record.x1 }},{{ record.y1 }},{{ record.x2 }},{{ record.y2 }}
                </template>
              </a-table-column>
            </a-table>
          </div>
        </template>
        <div v-else class="empty-card compact-empty inspection-detail-empty">暂无检测缺陷</div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.image-inspection-grid {
  height: 100%;
  min-height: 0;
  grid-template-columns: 340px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
}

.inspection-left-column {
  display: grid;
  grid-template-rows: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.image-upload-panel,
.inspection-records-panel {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.inspection-result-panel {
  display: grid;
  grid-template-rows: minmax(0, 1fr) 200px;
  padding: 0;
  overflow: hidden;
  min-height: 0;
}

.inspection-result-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 0;
  padding: 18px 20px;
}

.inspection-result-main > .card-head {
  height: 28px;
  flex: none;
  margin-bottom: 0;
}

.inspection-result-actions { display: flex; align-items: center; gap: 10px; }

.inspection-result-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  padding: 10px 18px 12px;
  border-top: 1px solid #ebeef5;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.inspection-task-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  flex: none;
}

.inspection-task-strip > div {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
}

.inspection-task-strip strong {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-count {
  color: #909399;
  font-size: 13px;
}

:deep(.ant-upload-drag) {
  flex: none;
  padding: 6px 0;
  border-radius: 4px;
  background: #fafbfc;
}

:deep(.ant-upload-drag-icon) {
  margin-bottom: 8px !important;
}

:deep(.ant-upload-drag-icon .anticon) {
  color: #409eff;
  font-size: 38px;
}

:deep(.ant-upload-text) {
  color: #606266 !important;
  font-size: 14px !important;
}

:deep(.ant-upload-hint) {
  font-size: 12px !important;
  color: #909399 !important;
}

.upload-preview-box {
  flex: 1;
  min-width: 0;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr);
  margin-top: 0;
  padding: 0;
  border: 0;
  background: #fff;
  overflow: hidden;
}

.upload-preview-head {
  padding-bottom: 0;
  color: #606266;
  font-size: 13px;
}

.upload-preview-head strong {
  color: #909399;
  font-weight: 400;
}

.upload-preview-empty {
  background: #fafbfc;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
}

.fixed-upload-preview {
  height: auto;
}

.upload-preview-scroll {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: max-content;
  gap: 8px;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.upload-preview-scroll-empty {
  display: grid;
  min-height: 0;
  place-items: center;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  color: #a8abb2;
  background: #fafbfc;
  font-size: 13px;
}

.uploaded-image-card {
  display: grid;
  grid-template-rows: 100px 32px;
  min-height: 0;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fff;
}

.uploaded-image-card img {
  width: 100%;
  height: 100px;
  aspect-ratio: auto;
  object-fit: contain;
  background: #f5f7fa;
}

.uploaded-image-card figcaption {
  min-width: 0;
  height: 32px;
  padding: 5px 7px;
  font-size: 12px;
}

.uploaded-image-card figcaption button {
  color: #f56c6c;
}

.uploaded-image-card figcaption button:disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}

.inspection-image-stage {
  display: block;
  position: relative;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  height: auto;
  min-height: 0;
  flex: 1;
}


.inspection-empty {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  background: #fafbfc;
}

.inspection-empty .anticon {
  color: #c0c4cc;
  font-size: 48px;
}

.inspection-detail-empty {
  flex: 1;
  min-height: 0;
}

.inspection-result-scroll {
  flex: 1;
  min-height: 0;
  max-height: none;
  grid-template-columns: minmax(0, 1fr);
  align-content: start;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.inspection-records-empty {
  flex: 1;
  min-height: 0;
}

.record-button {
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  color: #303133;
  background: #fff;
}

.record-button.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.record-button strong {
  font-size: 13px;
  font-weight: 500;
}

.record-button span {
  color: #909399;
  font-size: 12px;
}

.detail-grid {
  flex: none;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.detection-table {
  flex: none;
}

.detail-grid .summary-item {
  padding: 8px 0;
  border: 0;
  background: #fff;
}

.detail-grid .summary-item strong {
  font-size: 14px;
  font-weight: 500;
}

@media (max-width: 1060px) {
  .image-inspection-grid {
    height: auto;
    min-height: calc(100dvh - 118px);
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }

  .inspection-left-column {
    grid-template-rows: minmax(360px, 420px) 300px;
  }

  .inspection-result-panel {
    height: 620px;
  }

  .detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .inspection-left-column {
    grid-template-rows: 400px 280px;
  }

  .inspection-result-panel {
    height: 600px;
  }
}

@media (max-width: 480px) {
  .inspection-left-column {
    grid-template-rows: 390px 260px;
  }

  .inspection-result-panel {
    height: 560px;
  }

  .inspection-result-details {
    padding-right: 12px;
    padding-left: 12px;
  }

  .inspection-result-main { padding: 14px 12px; }
  .inspection-result-actions { gap: 6px; }
  .inspection-result-actions :deep(.ant-radio-button-wrapper) { padding: 0 6px; font-size: 12px; }
  .inspection-result-actions :deep(.ant-tag) { margin-inline-end: 0; }
}
</style>
