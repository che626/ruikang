<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
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

const currentRecord = computed(() =>
  lastResult.value?.records.find((item) => item.recordNo === selectedRecordNo.value) ?? lastResult.value?.records[0] ?? null,
);

const beforeUpload: UploadProps["beforeUpload"] = (file) => {
  const rawFile = file as unknown as File;
  const uid = "uid" in file ? String(file.uid) : `${file.name}-${Date.now()}`;
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
    <article class="card panel-column">
      <div class="card-head">
        <div>
          <span>上传入口</span>
          <h3>图片质检任务</h3>
        </div>
        <em>UPLOAD</em>
      </div>

      <a-upload-dragger :multiple="true" :show-upload-list="false" :before-upload="beforeUpload">
        <p class="ant-upload-drag-icon">+</p>
        <p class="ant-upload-text">拖拽图片到这里，或点击选择文件</p>
        <p class="ant-upload-hint">支持 JPG / PNG，可一次选择多张，系统将自动分批处理</p>
      </a-upload-dragger>

      <div class="upload-preview-box fixed-upload-preview">
        <div class="upload-preview-head">
          <span>已上传图片</span>
          <strong>{{ selectedImages.length }} 张</strong>
        </div>
        <div v-if="selectedImages.length" class="upload-preview-grid upload-preview-scroll">
          <figure v-for="item in selectedImages" :key="item.uid" class="uploaded-image-card">
            <img :src="item.url" :alt="item.name" loading="lazy" />
            <figcaption>
              <span>{{ item.name }}</span>
              <button type="button" @click="removeSelected(item.uid)">移除</button>
            </figcaption>
          </figure>
        </div>
        <div v-else class="upload-preview-empty">选择图片后会显示在这里。</div>
      </div>

      <div class="button-row">
        <a-button type="primary" :loading="loading" @click="runInspection">
          {{ loading ? inspectionProgress : "开始质检" }}
        </a-button>
        <a-button @click="clearSelected">清空图片</a-button>
      </div>

      <div v-if="lastResult" class="summary-strip">
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
    </article>

    <article class="card panel-column">
      <div class="card-head">
        <div>
          <span>检测结果</span>
          <h3>结果图</h3>
        </div>
        <em>{{ currentRecord?.resultJudge ?? "READY" }}</em>
      </div>

      <div v-if="currentRecord?.resultImagePath" class="image-result-single inspection-image-stage">
        <img :src="filePreviewUrl(currentRecord.resultImagePath)" alt="结果图" />
      </div>
      <div v-else class="empty-card">上传并运行图片质检后，这里会显示结果图。</div>
    </article>

    <article class="card panel-column">
      <div class="card-head">
        <div>
          <span>记录列表</span>
          <h3>本次结果</h3>
        </div>
        <em>{{ lastResult?.records.length ?? 0 }} 条</em>
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
      <div v-else class="empty-card compact-empty">暂无本次检测结果。</div>
    </article>

    <article class="card panel-column">
      <div class="card-head">
        <div>
          <span>结果摘要</span>
          <h3>检测详情</h3>
        </div>
        <em>DETAIL</em>
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
            :row-key="(row) => `${row.defectCode}-${row.x1}-${row.y1}`"
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
      <div v-else class="empty-card">暂无检测摘要。</div>
    </article>
  </section>
</template>
