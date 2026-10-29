<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchDashboardOverview, type DashboardOverview } from "@/api/dashboard";
import { useRealtimeStream } from "@/composables/useRealtimeStream";

const router = useRouter();
const lastRefreshTime = ref("--:--:--");

function updateRefreshTime() {
  lastRefreshTime.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

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
  // Show latest result even when not currently running (persists from last session)
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
    recordNo: evidence.recordNo,
  };
});

const defectSampleItems = computed(() => {
  return [...stream.evidence.value]
    .filter((item) => item.fileType === "result_image" || item.fileType === "evidence_image")
    .sort((a, b) => Date.parse(b.createTime) - Date.parse(a.createTime))
    .slice(0, 2)
    .map((item) => ({
      key: `${item.recordNo}-${item.relativePath}`,
      image: `/api/files/content?relativePath=${encodeURIComponent(item.relativePath)}`,
      caption: item.fileType === "result_image" ? "异常结果图" : "异常证据图",
      recordNo: item.recordNo,
    }));
});

const trendItems = computed(() => {
  const targetFps = Math.max(1, stream.status.value?.targetInferenceFps ?? 60);
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
    updateRefreshTime();
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
  <section class="db-page">
    <!-- 标题栏 -->
    <div class="db-toolbar">
      <div class="db-toolbar-left">
        <span class="db-toolbar-title">检测数据概览</span>
        <span class="db-toolbar-time">更新于 {{ lastRefreshTime }}</span>
      </div>
      <div class="db-toolbar-right">
        <button class="db-btn-outline" @click="router.push('/image')">
          <span>⊞</span> 图片质检
        </button>
        <button class="db-btn-primary" @click="router.push('/realtime')">
          <span>▶</span> 实时监控
        </button>
        <button class="db-btn-icon" @click="loadDashboard" title="刷新">↻</button>
      </div>
    </div>
    <!-- 顶部指标栏 -->
    <div class="db-metrics">
      <div v-for="m in overview.metrics" :key="m.label" class="db-metric-card">
        <div class="db-metric-icon" :class="m.tone">
          <span v-if="m.tone === 'success'">✓</span>
          <span v-else-if="m.tone === 'warning'">△</span>
          <span v-else-if="m.tone === 'danger'">✕</span>
          <span v-else>◈</span>
        </div>
        <div class="db-metric-body">
          <div class="db-metric-label">{{ m.label }}</div>
          <div class="db-metric-value">{{ m.value }}</div>
        </div>
      </div>
      <div v-if="!overview.metrics.length" v-for="i in 4" :key="i" class="db-metric-card db-metric-placeholder">
        <div class="db-metric-icon neutral"><span>◈</span></div>
        <div class="db-metric-body">
          <div class="db-metric-label">--</div>
          <div class="db-metric-value">--</div>
        </div>
      </div>
    </div>

    <!-- 中间双视频区 -->
    <div class="db-video-row">
      <!-- 左：实时摄像头 -->
      <article class="card db-video-card">
        <div class="db-video-head">
          <span class="db-video-title">实时质检画面</span>
          <span :class="['db-status-tag', stream.running.value ? 'running' : 'idle']">
            {{ stream.running.value ? '监控中' : '未启动' }}
          </span>
        </div>
        <div class="db-video-stage">
          <template v-if="stream.running.value">
            <video ref="dashboardVideoRef" autoplay muted playsinline class="db-video-el"></video>
          </template>
          <div v-else class="db-video-empty">
            <span>实时监控未启动</span>
          </div>
        </div>
        <div class="db-video-footer">
          <span>{{ stream.status.value?.cameraName ?? 'Webcam 0' }}</span>
          <a class="db-video-link" @click.prevent="router.push('/realtime')">摄像头设置</a>
        </div>
      </article>

      <!-- 右：最新异常结果图 -->
      <article class="card db-video-card">
        <div class="db-video-head">
          <span class="db-video-title">最新异常结果</span>
          <a class="db-video-link" @click.prevent="router.push('/records')">查看记录</a>
        </div>
        <div class="db-video-stage">
          <img v-if="latestRealtimeImage" :src="latestRealtimeImage.image" alt="最新异常结果图" class="db-result-img" />
          <div v-else class="db-video-empty"><span>暂无异常结果</span></div>
        </div>
        <div class="db-video-footer">
          <span>{{ latestRealtimeImage ? latestRealtimeImage.caption : '--' }}</span>
          <span class="db-footer-tag">最新异常结果图</span>
        </div>
      </article>
    </div>

    <!-- 底部四列小卡片 -->
    <div class="db-bottom-row">
      <!-- 检测性能 -->
      <article class="card db-bottom-card">
        <div class="db-bottom-head">
          <span>检测性能</span>
          <span class="db-bottom-tag">最近 {{ trendItems.length || 8 }} 次采样</span>
        </div>
        <div class="db-perf-stats">
          <div class="db-perf-item">
            <span>推理帧率</span>
            <strong>{{ latestTrend.fps }} <em>帧/秒</em></strong>
          </div>
          <div class="db-perf-item">
            <span>处理延迟</span>
            <strong>{{ latestTrend.latency }} <em>毫秒</em></strong>
          </div>
        </div>
        <div v-if="trendItems.length" class="db-mini-chart">
          <div v-for="item in trendItems" :key="item.time" class="db-mini-bar-col">
            <div class="db-mini-bar-wrap">
              <div :class="['db-mini-bar', item.tone]" :style="{ height: item.height }"></div>
            </div>
            <span>{{ item.fps }}</span>
          </div>
        </div>
        <div v-else class="db-perf-empty">启动监控后记录帧率</div>
      </article>

      <!-- 实时告警 -->
      <article class="card db-bottom-card db-alert-card">
        <div class="db-bottom-head">
          <span>实时告警</span>
          <span class="db-bottom-tag">最近 10 条</span>
        </div>
        <div v-if="alertItems.length" class="db-alert-list">
          <div v-for="(item, idx) in alertItems.slice(0, 5)" :key="idx" class="db-alert-row">
            <span class="db-alert-icon">△</span>
            <div class="db-alert-body">
              <strong>{{ item.title }}</strong>
              <span>{{ item.message }}</span>
              <time>{{ item.time }}</time>
            </div>
          </div>
        </div>
        <div v-else class="db-perf-empty">暂无告警事件</div>
      </article>

      <!-- 设备状态 -->
      <article class="card db-bottom-card">
        <div class="db-bottom-head">
          <span>设备状态</span>
          <a class="db-video-link" @click.prevent="router.push('/config/camera')">配置</a>
        </div>
        <div class="db-device-list">
          <div class="db-device-row">
            <span>运行状态</span>
            <strong :class="stream.running.value ? 'db-ok' : 'db-muted'">
              {{ stream.running.value ? '运行中' : '已停止' }}
            </strong>
          </div>
          <div class="db-device-row">
            <span>当前摄像头</span>
            <strong>{{ stream.status.value?.cameraName ?? 'Webcam 0' }}</strong>
          </div>
          <div class="db-device-row">
            <span>推理帧率</span>
            <strong>{{ latestTrend.fps }} 帧/秒</strong>
          </div>
          <div class="db-device-row">
            <span>处理延迟</span>
            <strong>{{ latestTrend.latency }} 毫秒</strong>
          </div>
        </div>
      </article>

      <!-- 缺陷样例 -->
      <article class="card db-bottom-card">
        <div class="db-bottom-head">
          <span>缺陷样例</span>
          <span class="db-bottom-tag">示例图片</span>
        </div>
        <div v-if="defectSampleItems.length" class="db-sample-grid">
          <figure v-for="item in defectSampleItems" :key="item.key" class="db-sample-tile">
            <img :src="item.image" :alt="item.caption" />
            <figcaption>{{ item.caption }} {{ item.recordNo ? item.recordNo.slice(-4) : '' }}</figcaption>
          </figure>
        </div>
        <div v-else class="db-perf-empty">检测到异常后显示</div>
      </article>
    </div>
  </section>
</template>
