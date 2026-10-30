<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { DetectionItem } from "@/api/records";
import { fitInspectionViewport, placeInspectionLabels } from "@/utils/inspection-viewport";

const props = defineProps<{
  sourceImageUrl: string;
  resultImageUrl: string;
  detections: DetectionItem[];
  mode: "focus" | "full";
}>();
const stage = ref<HTMLDivElement | null>(null);
const canvas = ref<HTMLCanvasElement | null>(null);
const loading = ref(true);
const failed = ref(false);
let loadedImage: HTMLImageElement | null = null;
let drawAnnotations = true;
let loadVersion = 0;
let resizeObserver: ResizeObserver | null = null;

function draw() {
  const target = canvas.value;
  const image = loadedImage;
  if (!target || !stage.value || !image) return;
  const width = stage.value.clientWidth;
  const height = stage.value.clientHeight;
  if (!width || !height) return;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  target.width = Math.round(width * ratio);
  target.height = Math.round(height * ratio);
  const ctx = target.getContext("2d");
  if (!ctx) return;
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.fillStyle = "#f5f7fa";
  ctx.fillRect(0, 0, width, height);

  const detections = props.detections.map((box) => ({ ...box,
    x1: Math.max(0, box.x1), y1: Math.max(0, box.y1),
    x2: Math.min(image.naturalWidth, box.x2), y2: Math.min(image.naturalHeight, box.y2),
  })).filter((box) =>
    [box.x1, box.y1, box.x2, box.y2].every(Number.isFinite) && box.x2 > box.x1 && box.y2 > box.y1,
  );
  const transform = fitInspectionViewport(image.naturalWidth, image.naturalHeight, width, height, detections, props.mode === "focus");
  ctx.drawImage(image, transform.x, transform.y, image.naturalWidth * transform.scale, image.naturalHeight * transform.scale);
  if (!drawAnnotations) return;

  const boxes = detections.map((box) => ({
    x1: transform.x + box.x1 * transform.scale,
    y1: transform.y + box.y1 * transform.scale,
    x2: transform.x + box.x2 * transform.scale,
    y2: transform.y + box.y2 * transform.scale,
  }));
  ctx.font = '600 13px "Microsoft YaHei", sans-serif';
  const texts = detections.map((item, index) => `${detections.length > 1 ? `${index + 1}. ` : ""}${item.defectName || item.defectCode} ${item.confidence.toFixed(2)}`);
  const labels = placeInspectionLabels(boxes, texts.map((text) => ctx.measureText(text).width + 16), width, height);

  boxes.forEach((box, index) => {
    const color = /^#[0-9a-f]{3,8}$/i.test(detections[index].displayColor) ? detections[index].displayColor : "#e6a23c";
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.strokeRect(box.x1, box.y1, box.x2 - box.x1, box.y2 - box.y1);
  });
  labels.forEach((label, index) => {
    const box = boxes[index];
    const color = /^#[0-9a-f]{3,8}$/i.test(detections[index].displayColor) ? detections[index].displayColor : "#e6a23c";
    ctx.strokeStyle = color;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(label.x + label.width / 2, label.y + label.height / 2);
    ctx.lineTo(Math.max(box.x1, Math.min(label.x + label.width / 2, box.x2)), Math.max(box.y1, Math.min(label.y + label.height / 2, box.y2)));
    ctx.stroke();
    ctx.fillStyle = color;
    ctx.fillRect(label.x, label.y, label.width, label.height);
    ctx.fillStyle = "#17212b";
    ctx.textBaseline = "middle";
    ctx.fillText(texts[index], label.x + 8, label.y + label.height / 2, Math.max(1, label.width - 16));
  });
}

function loadImage() {
  const version = ++loadVersion;
  loading.value = true;
  failed.value = false;
  loadedImage = null;
  const urls = [...new Set([props.sourceImageUrl, props.resultImageUrl].filter(Boolean))];
  function attempt(index: number) {
    if (index >= urls.length) {
      if (version === loadVersion) { loading.value = false; failed.value = true; }
      return;
    }
    const image = new Image();
    image.decoding = "async";
    image.onload = () => {
      if (version !== loadVersion) return;
      loadedImage = image;
      drawAnnotations = urls[index] === props.sourceImageUrl;
      loading.value = false;
      draw();
    };
    image.onerror = () => { if (version === loadVersion) attempt(index + 1); };
    image.src = urls[index];
  }
  attempt(0);
}

watch(() => [props.sourceImageUrl, props.resultImageUrl], loadImage, { immediate: true });
watch(() => [props.mode, props.detections], draw, { deep: true });
onMounted(() => {
  resizeObserver = new ResizeObserver(draw);
  if (stage.value) resizeObserver.observe(stage.value);
  draw();
});
onBeforeUnmount(() => { loadVersion++; resizeObserver?.disconnect(); loadedImage = null; });
</script>

<template>
  <div ref="stage" class="result-viewer">
    <canvas ref="canvas" role="img" :aria-label="`检测结果图，共 ${detections.length} 个缺陷，${mode === 'focus' ? '聚焦缺陷' : '完整图片'}`" />
    <div v-if="loading" class="viewer-state"><a-spin /><span>正在加载结果图</span></div>
    <div v-else-if="failed" class="viewer-state"><span>结果图加载失败</span><a-button size="small" @click="loadImage">重新加载</a-button></div>
  </div>
</template>

<style scoped>
.result-viewer { position: relative; width: 100%; height: 100%; min-height: 0; overflow: hidden; }
canvas { position: absolute; inset: 0; display: block; width: 100%; height: 100%; }
.viewer-state { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 12px; color: #909399; background: #f5f7fa; font-size: 13px; }
</style>
