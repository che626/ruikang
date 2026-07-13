<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { fetchDashboardOverview, type DashboardOverview } from "@/api/dashboard";
import { useRealtimeStream } from "@/composables/useRealtimeStream";

const stream = useRealtimeStream();

const overview = ref<DashboardOverview>({
  metrics: [],
  monitor: {
    title: "实时质检画面",
    cameraName: "Camera 01",
    runtimeStatus: "STOPPED",
    inferenceFps: 0,
    latencyMs: 0,
    boxes: [],
    footer: "",
  },
  recentEvents: [],
  defectRanks: [],
  trend: { labels: [], fpsSeries: [], latencySeries: [] },
  healthItems: [],
  evidenceItems: [],
});
const dashboardVideoRef = ref<HTMLVideoElement | null>(null);
let refreshTimer: number | undefined;

const fixedSampleTiles = [
  { image: "/samples/defect-sample-1.jpg", caption: "检查结果样例 1" },
  { image: "/samples/defect-sample-2.jpg", caption: "检查结果样例 2" },
];

const alertItems = computed(() => {
  if (!stream.running.value) return [];
  return stream.sessionAlerts.value
    .map((item) => ({
      title: item.title || "实时质检异常",
      message: item.message || item.recordNo || "检测到异常记录",
      time: formatTime(item.eventTime),
    }));
});

const statusCounts = computed(() => {
  const runtimeStatus = stream.status.value?.runtimeStatus ?? overview.value.monitor.runtimeStatus;
  const online = runtimeStatus === "RUNNING" ? 1 : 0;
  const offline = online ? 0 : 1;
  return {
    online,
    offline,
    alarm: stream.running.value ? alertItems.value.length : 0,
  };
});

const latestRealtimeImage = computed(() => {
  if (!stream.running.value) return null;
  const newestByTime = (items: typeof stream.evidence.value) =>
    [...items].sort((a, b) => Date.parse(b.createTime) - Date.parse(a.createTime))[0] ?? null;
  const evidence =
    newestByTime(stream.evidence.value.filter((item) => item.fileType === "result_image")) ??
    newestByTime(stream.evidence.value);
  if (!evidence) return null;
  return {
    image: `/api/files/content?relativePath=${encodeURIComponent(evidence.relativePath)}`,
    caption: `瑕疵ID ${evidence.recordNo}`,
    tag: evidence.fileType === "result_image" ? "最新异常结果图" : "最新异常证据图",
  };
});

const trendItems = computed(() => {
  const targetFps = Math.max(1, stream.status.value?.targetInferenceFps ?? 30);
  return stream.performanceSamples.value.map((sample) => {
    const latency = sample.latency;
    const cappedFps = Math.min(sample.fps, targetFps);
    const height = Math.max(8, Math.round((cappedFps / targetFps) * 100));
    const tone = latency >= 260 || sample.fps < targetFps * 0.35
      ? "danger"
      : latency >= 160 || sample.fps < targetFps * 0.65
        ? "warning"
        : "success";
    return {
      ...sample,
      tone,
      height: `${Math.min(100, height)}%`,
    };
  });
});

const latestTrend = computed(() => trendItems.value[trendItems.value.length - 1] ?? { fps: 0, latency: 0 });

async function bindDashboardVideo() {
  if (stream.running.value) {
    await nextTick();
    await stream.attachVideo(dashboardVideoRef.value);
  }
}

async function loadDashboard() {
  try {
    const overviewData = await fetchDashboardOverview();
    overview.value = overviewData;
    await stream.refresh();
    await bindDashboardVideo();
  } catch {
    // keep current data on fetch failure
  }
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value || "--";
  return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

watch(() => stream.running.value, () => {
  bindDashboardVideo().catch(() => {});
});

onMounted(async () => {
  await loadDashboard();
  refreshTimer = window.setInterval(loadDashboard, 5000);
});

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer);
});
</script>

<template>
  <section class="dashboard-sample">
    <div class="sample-grid">
      <div class="sample-center">
        <article class="card sample-video-card result-focus-card">
          <div class="card-title-row">
            <span>实时质检图片</span>
            <span class="tag">{{ latestRealtimeImage ? "最新异常" : "等待异常" }}</span>
          </div>

          <figure v-if="latestRealtimeImage" class="latest-result-figure">
            <img :src="latestRealtimeImage.image" alt="最新实时质检异常结果图" />
            <figcaption>
              <strong>{{ latestRealtimeImage.caption }}</strong>
              <span>{{ latestRealtimeImage.tag }}</span>
            </figcaption>
          </figure>
          <div v-else class="result-empty-state">
            <strong>暂无实时异常图片</strong>
            <span>实时监控启动并检测到瑕疵后，这里只显示最新一次异常结果图。</span>
          </div>
        </article>

        <div class="sample-bottom-row">
          <article class="card sample-small-card trend-card">
            <div class="card-title-row">
              <span>趋势分析</span>
              <span class="tag">近 5 次帧率</span>
            </div>
            <div class="trend-summary-row">
              <div><span>最新推理帧率</span><strong>{{ latestTrend.fps }} FPS</strong></div>
              <div><span>最新延迟</span><strong>{{ latestTrend.latency }} ms</strong></div>
            </div>
            <p class="trend-caption">近 5 次检测的实时性变化：帧率越高、延迟越低，画面响应越稳定。</p>
            <div v-if="trendItems.length" class="trend-chart">
              <div v-for="item in trendItems" :key="item.time" class="trend-column">
                <div class="trend-bar-wrap">
                  <div :class="['trend-bar', item.tone]" :style="{ height: item.height }"></div>
                </div>
                <strong>{{ item.fps }}</strong>
                <span>{{ item.label }}</span>
              </div>
            </div>
            <div v-else class="trend-empty">启动实时监控后开始记录帧率变化。</div>
          </article>

          <article class="card sample-small-card">
            <div class="card-title-row">
              <span>实时质检画面</span>
              <span class="tag">{{ stream.running.value ? "LIVE" : "未启动" }}</span>
            </div>
            <div v-if="stream.running.value" class="sample-fabric stage-live camera-live">
              <video ref="dashboardVideoRef" autoplay muted playsinline></video>
            </div>
            <div v-else class="sample-fabric monitor-idle">
              <strong>实时监控未启动</strong>
              <span>启动后这里会显示摄像头实时画面。</span>
            </div>
          </article>

          <article class="card sample-small-card sample-pair-card">
            <div class="card-title-row">缺陷样例</div>
            <div class="sample-tiles fixed-sample-tiles sample-pair-tiles">
              <figure v-for="tile in fixedSampleTiles" :key="tile.image" class="sample-tile fixed-sample-tile">
                <img :src="tile.image" :alt="tile.caption" />
              </figure>
            </div>
          </article>
        </div>
      </div>

      <div class="sample-rightcol">
        <article class="card sample-device-card">
          <div class="card-title-row">设备状态</div>
          <div class="sample-ring-row">
            <div class="sample-ring"><span>{{ statusCounts.online }}</span><small>在线</small></div>
            <div class="sample-ring orange"><span>{{ statusCounts.offline }}</span><small>离线</small></div>
            <div class="sample-ring red"><span>{{ statusCounts.alarm }}</span><small>告警</small></div>
          </div>
        </article>

        <article class="card sample-alarm-card">
          <div class="card-title-row">
            告警中心
            <span class="danger">最近10条记录</span>
          </div>
          <div v-if="alertItems.length" class="dashboard-alert-list">
            <div v-for="(item, index) in alertItems" :key="item.time + item.title + index" class="sample-alarm-row">
              <div>
                <strong>{{ item.title }}</strong>
                <span>{{ item.message }}</span>
              </div>
              <em>{{ item.time }}</em>
            </div>
          </div>
          <div v-else class="dashboard-alert-empty">告警中心暂无异常</div>
        </article>
      </div>
    </div>

    <footer class="sample-footer">
      版本：v2.1.0 | 服务运行状态：{{ stream.running.value ? "实时监控中" : "待机中" }}
    </footer>
  </section>
</template>
