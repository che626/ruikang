<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { message } from "ant-design-vue";
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
  <section class="rt-page">
    <aside class="card rt-param-panel">
      <div class="rt-param-head">
        <span class="rt-param-title">检测参数</span>
        <span :class="['rt-run-badge', stream.running.value ? 'running' : 'idle']">
          {{ stream.running.value ? '运行中' : '未运行' }}
        </span>
        <button class="rt-settings-btn" @click="settingsOpen = true">⚙ 设置</button>
      </div>
      <div class="rt-param-readonly-grid">
        <div class="rt-param-ro-item"><span>置信度</span><strong>{{ formState.confidenceThreshold.toFixed(2) }}</strong></div>
        <div class="rt-param-ro-item"><span>IOU 阈值</span><strong>{{ formState.iouThreshold.toFixed(2) }}</strong></div>
        <div class="rt-param-ro-item"><span>连续命中</span><strong>{{ formState.continuousHitFrames }} 帧</strong></div>
        <div class="rt-param-ro-item"><span>目标帧率</span><strong>{{ formState.targetInferenceFps }} FPS</strong></div>
        <div class="rt-param-ro-item rt-param-ro-full"><span>证据图数</span><strong>{{ formState.evidenceImageCount }} 张</strong></div>
      </div>
      <div class="rt-action-row">
        <a-button type="primary" :loading="stream.loading.value" :disabled="stream.running.value" class="rt-btn-start" @click="handleStart">
          {{ stream.loading.value ? stream.modelLoadingStage.value : '⊙ 启动检测' }}
        </a-button>
        <a-button danger :loading="stream.loading.value" :disabled="!stream.running.value" class="rt-btn-stop" @click="handleStop">
          ⊗ 停止检测
        </a-button>
      </div>
    </aside>

    <div class="rt-main">
      <article class="card rt-video-card">
        <div class="rt-video-head">
          <span class="rt-video-title">视频监控</span>
          <span :class="['rt-live-badge', stream.running.value ? 'active' : '']">
            {{ stream.running.value ? '正在检测' : '未启动' }}
          </span>
        </div>
        <div class="rt-video-stage">
          <video ref="videoRef" :class="{ hidden: !stream.running.value }" autoplay muted playsinline class="rt-video-el" @loadedmetadata="resizeOverlayCanvas"></video>
          <canvas ref="overlayCanvasRef" class="rt-canvas overlay-canvas"></canvas>
          <div v-if="!stream.running.value" class="rt-video-placeholder">
            <span>实时监控未启动</span>
            <small>点击左侧启动后开始检测</small>
          </div>
        </div>
        <div class="rt-stats-bar">
          <div class="rt-stat"><span>摄像头</span><strong>{{ stream.status.value?.cameraName ?? '-' }}</strong></div>
          <div class="rt-stat"><span>预览帧率</span><strong>{{ stream.status.value?.previewFps ?? 0 }} FPS</strong></div>
          <div class="rt-stat"><span>推理帧率</span><strong>{{ stream.status.value?.inferenceFps ?? 0 }} FPS</strong></div>
          <div class="rt-stat"><span>延迟</span><strong>{{ stream.status.value?.latencyMs ?? 0 }} ms</strong></div>
        </div>
      </article>
    </div>

    <a-modal v-model:open="settingsOpen" title="检测参数设置" ok-text="保存设置" cancel-text="取消" :width="520" @ok="settingsOpen = false">
      <div class="rt-modal-sliders">
        <div class="rt-modal-field">
          <div class="rt-modal-label"><span>置信度阈值</span><strong>{{ formState.confidenceThreshold.toFixed(2) }}</strong></div>
          <a-slider v-model:value="formState.confidenceThreshold" :step="0.01" :min="0.01" :max="0.99" />
        </div>
        <div class="rt-modal-field">
          <div class="rt-modal-label"><span>IOU 阈值</span><strong>{{ formState.iouThreshold.toFixed(2) }}</strong></div>
          <a-slider v-model:value="formState.iouThreshold" :step="0.01" :min="0.01" :max="0.99" />
        </div>
        <div class="rt-modal-field">
          <div class="rt-modal-label"><span>连续命中帧数</span><strong>{{ formState.continuousHitFrames }}</strong></div>
          <a-slider v-model:value="formState.continuousHitFrames" :step="1" :min="1" :max="20" />
        </div>
        <div class="rt-modal-field">
          <div class="rt-modal-label"><span>目标推理帧率</span><strong>{{ formState.targetInferenceFps }} FPS</strong></div>
          <a-slider v-model:value="formState.targetInferenceFps" :step="1" :min="1" :max="60" />
        </div>
        <div class="rt-modal-field">
          <div class="rt-modal-label"><span>证据图数量</span><strong>{{ formState.evidenceImageCount }}</strong></div>
          <a-slider v-model:value="formState.evidenceImageCount" :step="1" :min="1" :max="5" />
        </div>
      </div>
    </a-modal>
  </section>
</template>