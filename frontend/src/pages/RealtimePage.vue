<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { DeleteOutlined, PlayCircleOutlined, SettingOutlined, StopOutlined, VideoCameraOutlined } from "@ant-design/icons-vue";
import { updateRealtimeConfig, type RealtimeConfigPayload } from "@/api/realtime";
import { useRealtimeStream } from "@/composables/useRealtimeStream";

const stream = useRealtimeStream();

const formState = reactive<Required<RealtimeConfigPayload>>({
  confidenceThreshold: 0.45,
  iouThreshold: 0.42,
  continuousHitFrames: 4,
  targetInferenceFps: 60,
  evidenceImageCount: 3,
});

const settingsOpen = ref(false);
const videoRef = ref<HTMLVideoElement | null>(null);
const overlayCanvasRef = ref<HTMLCanvasElement | null>(null);
let syncingConfig = false;
let configReady = false;
let configTimer: number | undefined;

function syncForm() {
  const s = stream.status.value;
  if (!s) return;
  syncingConfig = true;
  formState.confidenceThreshold = s.confidenceThreshold;
  formState.iouThreshold = s.iouThreshold;
  formState.continuousHitFrames = s.continuousHitFrames;
  formState.targetInferenceFps = s.targetInferenceFps;
  formState.evidenceImageCount = s.evidenceImageCount;
  nextTick(() => {
    syncingConfig = false;
  });
}

function evidencePreviewUrl(p: string) {
  return `/api/files/content?relativePath=${encodeURIComponent(p)}`;
}

function resizeOverlayCanvas() {
  const video = videoRef.value;
  const canvas = overlayCanvasRef.value;
  if (!video || !canvas) return;
  const rect = video.getBoundingClientRect();
  canvas.width = Math.max(1, Math.round(rect.width));
  canvas.height = Math.max(1, Math.round(rect.height));
  drawLocalBoxes();
}

function drawLocalBoxes() {
  const canvas = overlayCanvasRef.value;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  const dets = stream.latestDetections.value;
  const imageWidth = stream.latestImageWidth.value;
  const imageHeight = stream.latestImageHeight.value;
  if (!dets.length || !imageWidth || !imageHeight) return;

  const sx = canvas.width / imageWidth;
  const sy = canvas.height / imageHeight;
  ctx.lineWidth = 2;
  ctx.font = "14px Microsoft YaHei";
  ctx.textBaseline = "top";

  dets.forEach((detection) => {
    const x = detection.x1 * sx;
    const y = detection.y1 * sy;
    const w = detection.width * sx;
    const h = detection.height * sy;
    const color = detection.displayColor || "#00d4ff";
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.strokeRect(x, y, w, h);

    const label = `${detection.defectName} ${detection.confidence.toFixed(3)}`;
    const labelWidth = ctx.measureText(label).width + 14;
    const labelY = Math.max(0, y - 22);
    ctx.fillRect(x, labelY, labelWidth, 22);
    ctx.fillStyle = "#ffffff";
    ctx.fillText(label, x + 7, labelY + 4);
  });
}

async function applyRealtimeConfig() {
  const newStatus = await updateRealtimeConfig({
    confidenceThreshold: Number(formState.confidenceThreshold.toFixed(2)),
    iouThreshold: Number(formState.iouThreshold.toFixed(2)),
    continuousHitFrames: Number(formState.continuousHitFrames),
    targetInferenceFps: Number(formState.targetInferenceFps),
    evidenceImageCount: Number(formState.evidenceImageCount),
  });
  stream.status.value = newStatus;
}

function scheduleConfigUpdate() {
  if (!configReady || syncingConfig) return;
  if (configTimer) window.clearTimeout(configTimer);
  configTimer = window.setTimeout(() => {
    applyRealtimeConfig().catch((error) => {
      message.error(error instanceof Error ? error.message : "参数更新失败");
    });
  }, 300);
}

async function handleStart() {
  try {
    await applyRealtimeConfig();
    await stream.start(videoRef.value);
    syncForm();
    await nextTick();
    resizeOverlayCanvas();
    message.success("实时质检已启动");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "启动失败");
  }
}

async function handleStop() {
  try {
    await stream.stop();
    syncForm();
    resizeOverlayCanvas();
    message.success("实时质检已停止");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "停止失败");
  }
}

async function handleClearHistory() {
  try {
    await stream.clearHistory();
    message.success("实时事件和证据图已清空");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "清空失败");
  }
}

watch(
  () => [
    formState.confidenceThreshold,
    formState.iouThreshold,
    formState.continuousHitFrames,
    formState.targetInferenceFps,
    formState.evidenceImageCount,
  ],
  scheduleConfigUpdate,
);

watch(
  () => [stream.latestDetections.value, stream.latestImageWidth.value, stream.latestImageHeight.value],
  () => nextTick(resizeOverlayCanvas),
  { deep: true },
);

onMounted(async () => {
  await stream.refresh();
  syncForm();
  configReady = true;
  if (stream.running.value) {
    await stream.attachVideo(videoRef.value);
    await nextTick();
    resizeOverlayCanvas();
  }
  window.addEventListener("resize", resizeOverlayCanvas);
});

onBeforeUnmount(() => {
  if (configTimer) window.clearTimeout(configTimer);
  window.removeEventListener("resize", resizeOverlayCanvas);
});
</script>

<template>
  <section class="realtime-grid">
    <article class="card panel-column">
      <div class="card-head">
        <h3>检测参数</h3>
        <div class="head-actions">
          <a-tag :color="stream.running.value ? 'success' : undefined">{{ stream.running.value ? "运行中" : "未启动" }}</a-tag>
          <a-button size="small" @click="settingsOpen = true">
            <template #icon><SettingOutlined /></template>
            设置
          </a-button>
        </div>
      </div>

      <div class="setting-summary-grid">
        <div class="setting-summary-item"><span>置信度</span><strong>{{ formState.confidenceThreshold.toFixed(2) }}</strong></div>
        <div class="setting-summary-item"><span>IOU 阈值</span><strong>{{ formState.iouThreshold.toFixed(2) }}</strong></div>
        <div class="setting-summary-item"><span>连续命中</span><strong>{{ formState.continuousHitFrames }} 帧</strong></div>
        <div class="setting-summary-item"><span>目标帧率</span><strong>{{ formState.targetInferenceFps }} FPS</strong></div>
        <div class="setting-summary-item"><span>证据图</span><strong>{{ formState.evidenceImageCount }} 张</strong></div>
      </div>

      <div class="button-row">
        <a-button type="primary" :loading="stream.loading.value" :disabled="stream.running.value" @click="handleStart">
          <template #icon><PlayCircleOutlined /></template>
          {{ stream.loading.value ? stream.modelLoadingStage.value : "启动检测" }}
        </a-button>
        <a-button
          danger
          :loading="stream.loading.value"
          :disabled="!stream.running.value"
          @click="handleStop"
        >
          <template #icon><StopOutlined /></template>
          停止检测
        </a-button>
      </div>
    </article>

    <article class="card panel-column">
      <div class="card-head">
        <h3>视频监控</h3>
        <a-tag :color="stream.running.value ? 'success' : undefined">{{ stream.running.value ? '正在检测' : '等待启动' }}</a-tag>
      </div>

      <div class="realtime-preview local">
        <video
          ref="videoRef"
          :class="{ hidden: !stream.running.value }"
          autoplay
          muted
          playsinline
          @loadedmetadata="resizeOverlayCanvas"
        ></video>
        <canvas ref="overlayCanvasRef" class="overlay-canvas"></canvas>
        <div v-if="!stream.running.value" class="preview-placeholder">
          <VideoCameraOutlined />
          <span>摄像头未启动</span>
        </div>
      </div>

      <div class="detail-grid">
        <div class="summary-item"><span>摄像头</span><strong>{{ stream.status.value?.cameraName ?? "-" }}</strong></div>
        <div class="summary-item"><span>预览帧率</span><strong>{{ stream.running.value ? (stream.status.value?.previewFps ?? 0) + ' FPS' : '—' }}</strong></div>
        <div class="summary-item"><span>推理帧率</span><strong>{{ stream.running.value ? (stream.status.value?.inferenceFps ?? 0) + ' FPS' : '—' }}</strong></div>
        <div class="summary-item"><span>延迟</span><strong>{{ stream.running.value ? (stream.status.value?.latencyMs ?? 0) + ' ms' : '—' }}</strong></div>
      </div>

      <div class="detail-grid">
        <div class="summary-item"><span>当前结论</span><strong>{{ stream.latestResultJudge.value === 'PASS' ? '合格' : ['FAIL', 'DEFECT'].includes(stream.latestResultJudge.value) ? '不合格' : '待检测' }}</strong></div>
        <div class="summary-item"><span>缺陷类型</span><strong>{{ stream.latestDefectType.value ?? "-" }}</strong></div>
        <div class="summary-item">
          <span>置信度</span>
          <strong>{{ stream.latestConfidence.value == null ? "-" : stream.latestConfidence.value.toFixed(3) }}</strong>
        </div>
        <div class="summary-item"><span>检测说明</span><strong>{{ stream.latestResultSummary.value }}</strong></div>
      </div>
    </article>

    <article class="card panel-column realtime-history-card">
      <div class="card-head">
        <h3>实时事件</h3>
        <a-button size="small" danger @click="handleClearHistory">
          <template #icon><DeleteOutlined /></template>
          清空
        </a-button>
      </div>
      <div v-if="stream.events.value.length" class="event-column realtime-scroll-area">
        <div v-for="ev in stream.events.value" :key="`${ev.eventTime}-${ev.title}`" class="event-item">
          <strong>{{ ev.title }}</strong>
          <span>{{ ev.message }}</span>
          <small>{{ ev.eventTime }}</small>
        </div>
      </div>
      <div v-else class="empty-card compact-empty">暂无实时事件</div>
    </article>

    <article class="card panel-column realtime-history-card">
      <div class="card-head">
        <h3>异常证据</h3>
        <a-button size="small" danger @click="handleClearHistory">
          <template #icon><DeleteOutlined /></template>
          清空
        </a-button>
      </div>
      <div v-if="stream.evidence.value.length" class="evidence-strip realtime-scroll-area evidence-scroll-area">
        <div v-for="item in stream.evidence.value" :key="`${item.recordNo}-${item.relativePath}`" class="evidence-card">
          <img :src="evidencePreviewUrl(item.relativePath)" alt="证据图" />
          <span>{{ item.recordNo }}</span>
          <strong>{{ item.fileType }}</strong>
        </div>
      </div>
      <div v-else class="empty-card compact-empty">暂无异常证据</div>
    </article>

    <a-modal
      v-model:open="settingsOpen"
      title="检测参数设置"
      width="560px"
      :footer="null"
      :mask-closable="false"
      @cancel="settingsOpen = false"
    >
      <div class="settings-modal-body">
        <div class="slider-stack">
          <div class="slider-field">
            <div class="slider-label"><span>置信度阈值</span><strong>{{ formState.confidenceThreshold.toFixed(2) }}</strong></div>
            <a-slider v-model:value="formState.confidenceThreshold" :step="0.01" :min="0.01" :max="0.99" />
          </div>
          <div class="slider-field">
            <div class="slider-label"><span>IOU 阈值</span><strong>{{ formState.iouThreshold.toFixed(2) }}</strong></div>
            <a-slider v-model:value="formState.iouThreshold" :step="0.01" :min="0.01" :max="0.99" />
          </div>
          <div class="slider-field">
            <div class="slider-label"><span>连续命中帧数</span><strong>{{ formState.continuousHitFrames }}</strong></div>
            <a-slider v-model:value="formState.continuousHitFrames" :step="1" :min="1" :max="20" />
          </div>
          <div class="slider-field">
            <div class="slider-label"><span>目标推理帧率</span><strong>{{ formState.targetInferenceFps }} FPS</strong></div>
            <a-slider v-model:value="formState.targetInferenceFps" :step="1" :min="1" :max="60" />
          </div>
          <div class="slider-field">
            <div class="slider-label"><span>证据图数量</span><strong>{{ formState.evidenceImageCount }}</strong></div>
            <a-slider v-model:value="formState.evidenceImageCount" :step="1" :min="1" :max="5" />
          </div>
        </div>
        <div class="settings-modal-actions">
          <a-button @click="settingsOpen = false">取消</a-button>
          <a-button type="primary" @click="settingsOpen = false">保存设置</a-button>
        </div>
      </div>
    </a-modal>
  </section>
</template>

<style scoped>
.realtime-grid {
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
}

.setting-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.setting-summary-item {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafbfc;
}

.setting-summary-item span {
  display: block;
  margin-bottom: 3px;
  color: #909399;
  font-size: 12px;
}

.setting-summary-item strong {
  color: #303133;
  font-size: 14px;
  font-weight: 500;
}

.slider-stack {
  display: grid;
  gap: 16px;
}

.slider-field {
  gap: 2px;
}

.slider-label {
  font-size: 13px;
}

.slider-label strong {
  min-width: 46px;
  text-align: right;
  font-weight: 500;
  color: #606266;
}

.settings-modal-body {
  padding-top: 8px;
}

.settings-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}

.button-row {
  display: flex;
  gap: 10px;
  padding-top: 16px;
  margin-top: 4px;
  border-top: 1px solid #ebeef5;
}

.realtime-preview.local {
  min-height: 340px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.realtime-preview.local video,
.realtime-preview.local .overlay-canvas {
  min-height: 340px;
}

.realtime-preview.local video:not(.hidden) {
  background: #202329;
}

.preview-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: #909399;
}

.preview-placeholder .anticon {
  font-size: 48px;
  color: #c0c4cc;
}

.detail-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.detail-grid .summary-item {
  border: 0;
  padding: 10px 0;
  background: #fff;
}

.detail-grid .summary-item strong {
  font-size: 13px;
  font-weight: 500;
}

.event-item {
  padding: 10px 0;
  border: 0;
  border-bottom: 1px solid #ebeef5;
  border-radius: 0;
  background: #fff;
}

.event-item strong {
  font-size: 13px;
  font-weight: 500;
}

.event-item span {
  font-size: 13px;
  line-height: 1.6;
}

.event-item small {
  margin-top: 4px;
  font-size: 12px;
}

.evidence-scroll-area .evidence-card {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
}

.evidence-scroll-area .evidence-card img {
  background: #f5f7fa;
  border-radius: 3px;
}

@media (max-width: 960px) {
  .realtime-grid {
    grid-template-columns: 1fr;
  }

  .detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
