<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { CheckCircleOutlined, FileSearchOutlined, PictureOutlined, ReloadOutlined, SafetyCertificateOutlined, VideoCameraOutlined, WarningOutlined } from "@ant-design/icons-vue";
import { useRouter } from "vue-router";
import { fetchDashboardOverview, type DashboardOverview } from "@/api/dashboard";
import { useRealtimeStream } from "@/composables/useRealtimeStream";

const router = useRouter();
const lastRefreshTime = ref("--:--:--");
const refreshing = ref(false);
const loadError = ref(false);
const PERFORMANCE_SAMPLE_LIMIT = 5;

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

const fixedSampleTiles = computed(() => defectSampleItems.value.length ? defectSampleItems.value : [
  { image: "/samples/defect-sample-1.jpg", caption: "检查结果样例 1" },
  { image: "/samples/defect-sample-2.jpg", caption: "检查结果样例 2" },
]);

const runtimeStatus = computed(() => stream.status.value?.runtimeStatus ?? overview.value.monitor.runtimeStatus);
const cameraName = computed(() => stream.status.value?.cameraName ?? overview.value.monitor.cameraName);
const runtimeLabels: Record<string, string> = { RUNNING: "运行中", STOPPED: "已停止", IDLE: "待机中", ERROR: "运行异常" };
const runtimeLabel = computed(() => runtimeLabels[runtimeStatus.value] ?? "待机中");
const metricCards = computed(() => {
  const definitions = [
    { label: "累计检测数", hint: "图片与实时检测记录", icon: FileSearchOutlined, tone: "blue" },
    { label: "缺陷命中数", hint: "判定存在缺陷的记录", icon: WarningOutlined, tone: "orange" },
    { label: "待处理异常", hint: "待处理及处理中的异常", icon: SafetyCertificateOutlined, tone: "red" },
    { label: "复核确认比例", hint: "已复核记录中的确认比例", icon: CheckCircleOutlined, tone: "green" },
  ];
  return definitions.map((item, index) => ({
    ...item,
    value: index === 3 && overview.value.metrics[index]?.trendLabel === "暂无复核"
      ? "--"
      : overview.value.metrics[index]?.value ?? "--",
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
  refreshing.value = true;
  try {
    const overviewData = await fetchDashboardOverview();
    overview.value = overviewData;
    await stream.refresh();
    await bindDashboardVideo();
    updateRefreshTime();
    loadError.value = false;
  } catch {
    loadError.value = true;
  } finally {
    refreshing.value = false;
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
  <section class="dashboard-page">
    <div class="dashboard-toolbar">
      <div class="dashboard-caption">
        <strong>检测数据概览</strong>
        <span v-if="loadError" class="refresh-error">数据更新失败，请稍后刷新</span>
        <span v-else>{{ lastRefreshTime ? `更新于 ${lastRefreshTime}` : "正在加载数据" }}</span>
      </div>
      <div class="dashboard-actions">
        <RouterLink to="/image" class="dashboard-button"><PictureOutlined /> 图片检测</RouterLink>
        <RouterLink to="/realtime" class="dashboard-button primary"><VideoCameraOutlined /> 实时质检</RouterLink>
        <button class="dashboard-icon-button" :disabled="refreshing" title="刷新数据" aria-label="刷新数据" @click="loadDashboard">
          <ReloadOutlined :spin="refreshing" />
        </button>
      </div>
    </div>

    <div class="dashboard-metrics">
      <article v-for="item in metricCards" :key="item.label" class="dashboard-metric">
        <div :class="['metric-icon', item.tone]"><component :is="item.icon" /></div>
        <div class="metric-content">
          <span :title="item.hint">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.hint }}</small>
        </div>
      </article>
    </div>

    <div class="dashboard-preview-grid">
          <article class="dashboard-panel">
            <div class="dashboard-panel-header">
              <strong>实时质检画面</strong>
              <span :class="['dashboard-status', stream.running.value ? 'success' : 'neutral']">{{ stream.running.value ? "监控中" : "未启动" }}</span>
            </div>
            <div v-if="stream.running.value" class="dashboard-media">
              <video ref="dashboardVideoRef" autoplay muted playsinline></video>
            </div>
            <div v-else class="dashboard-empty preview-empty">
              <VideoCameraOutlined />
              <strong>实时监控未启动</strong>
              <span>启动监控后显示摄像头画面</span>
              <RouterLink to="/realtime">前往实时质检</RouterLink>
            </div>
            <div class="dashboard-panel-footer"><span>{{ cameraName }}</span><RouterLink to="/config/camera">摄像头设置</RouterLink></div>
          </article>

          <article class="dashboard-panel">
            <div class="dashboard-panel-header">
              <strong>最新异常结果</strong>
              <RouterLink to="/records">查看记录</RouterLink>
            </div>
            <figure v-if="latestRealtimeImage" class="dashboard-result">
              <div class="dashboard-media"><img :src="latestRealtimeImage.image" alt="最新实时质检异常结果图" @click="router.push({ path: '/records', query: { recordNo: latestRealtimeImage.recordNo } })" /></div>
              <figcaption class="dashboard-panel-footer"><span>{{ latestRealtimeImage.caption }}</span><span>{{ latestRealtimeImage.tag }}</span></figcaption>
            </figure>
            <template v-else>
              <div class="dashboard-empty preview-empty">
                <PictureOutlined />
                <strong>暂无实时异常图片</strong>
                <span>检测到缺陷后自动显示最新结果图</span>
              </div>
              <div class="dashboard-panel-footer"><span>当前监控会话</span><span>自动更新</span></div>
            </template>
          </article>
        </div>

        <div class="dashboard-secondary-grid">
          <article class="dashboard-panel">
            <div class="dashboard-panel-header"><strong>检测性能</strong><span class="panel-extra">最近 {{ PERFORMANCE_SAMPLE_LIMIT }} 次采样</span></div>
            <div class="dashboard-performance-summary">
              <div><span>推理帧率</span><strong>{{ latestTrend.fps }} <small>帧/秒</small></strong></div>
              <div><span>处理延迟</span><strong>{{ latestTrend.latency }} <small>毫秒</small></strong></div>
            </div>
            <div v-if="trendItems.length" class="dashboard-trend">
              <div v-for="item in trendItems" :key="item.time" class="dashboard-trend-column">
                <div class="dashboard-trend-track"><div :class="['dashboard-trend-bar', item.tone]" :style="{ height: item.height }"></div></div>
                <strong>{{ item.fps }}</strong><span :title="item.label">{{ item.label }}</span>
              </div>
            </div>
            <div v-else class="dashboard-trend-empty">启动监控后显示性能采样</div>
          </article>

          <article class="dashboard-panel dashboard-alert-panel">
            <div class="dashboard-panel-header"><strong>实时告警</strong><span class="panel-extra">最近 10 条</span></div>
            <div v-if="alertItems.length" class="dashboard-alerts">
              <div v-for="(item, index) in alertItems" :key="item.time + item.title + index" class="dashboard-alert-item">
                <WarningOutlined />
                <div><strong>{{ item.title }}</strong><p>{{ item.message }}</p><time>{{ item.time }}</time></div>
              </div>
            </div>
            <div v-else class="dashboard-empty alert-empty"><CheckCircleOutlined /><strong>暂无实时告警</strong><span>{{ stream.running.value ? "当前会话尚未产生异常记录" : "启动监控后查看本次会话告警" }}</span></div>
            <div class="dashboard-panel-footer"><RouterLink to="/records">查看质检记录</RouterLink></div>
          </article>

        <article class="dashboard-panel">
          <div class="dashboard-panel-header"><strong>设备状态</strong><RouterLink to="/config/camera">配置</RouterLink></div>
          <dl class="dashboard-device-details">
            <div><dt>运行状态</dt><dd><span :class="['dashboard-status', runtimeStatus === 'RUNNING' ? 'success' : runtimeStatus === 'ERROR' ? 'danger' : 'neutral']">{{ runtimeLabel }}</span></dd></div>
            <div><dt>当前摄像头</dt><dd :title="cameraName">{{ cameraName }}</dd></div>
            <div><dt>推理帧率</dt><dd>{{ runtimeStatus === 'RUNNING' ? (stream.status.value?.inferenceFps ?? overview.monitor.inferenceFps) : '—' }}<span v-if="runtimeStatus === 'RUNNING'"> 帧/秒</span></dd></div>
            <div><dt>处理延迟</dt><dd>{{ runtimeStatus === 'RUNNING' ? (stream.status.value?.latencyMs ?? overview.monitor.latencyMs) : '—' }}<span v-if="runtimeStatus === 'RUNNING'"> 毫秒</span></dd></div>
            <div><dt>本次会话告警</dt><dd :class="{ 'alert-count': alertItems.length > 0 }">{{ alertItems.length }} 条<span v-if="alertItems.length === 10" class="count-note">（最近）</span></dd></div>
          </dl>
        </article>

        <article class="dashboard-panel">
          <div class="dashboard-panel-header"><strong>缺陷样例</strong><span class="panel-extra">示例图片</span></div>
          <div class="dashboard-samples">
            <figure v-for="tile in fixedSampleTiles" :key="tile.image"><img :src="tile.image" :alt="tile.caption" /><figcaption>{{ tile.caption }}</figcaption></figure>
          </div>
          <div class="dashboard-samples-note">样例仅供参考，以实际检测结果为准。</div>
        </article>
    </div>
  </section>
</template>

<style scoped>
.dashboard-page { color: #303133; }
.dashboard-toolbar, .dashboard-actions, .dashboard-caption, .dashboard-panel-header, .dashboard-panel-footer { display: flex; align-items: center; }
.dashboard-toolbar { justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.dashboard-caption { gap: 14px; min-width: 0; }
.dashboard-caption strong { font-size: 15px; font-weight: 500; }
.dashboard-caption > span, .panel-extra { color: #909399; font-size: 12px; }
.dashboard-caption > .refresh-error { color: #e6a23c; }
.dashboard-actions { gap: 8px; }
.dashboard-button, .dashboard-icon-button { display: inline-flex; justify-content: center; align-items: center; height: 32px; gap: 6px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; color: #606266; font-size: 13px; cursor: pointer; }
.dashboard-button { padding: 0 12px; text-decoration: none; }
.dashboard-icon-button { width: 32px; padding: 0; }
.dashboard-button:hover, .dashboard-icon-button:hover { border-color: #a0cfff; color: #409eff; background: #ecf5ff; }
.dashboard-button.primary { border-color: #409eff; background: #409eff; color: #fff; }
.dashboard-button.primary:hover { border-color: #66b1ff; background: #66b1ff; }
.dashboard-icon-button:disabled { cursor: wait; color: #909399; }
.dashboard-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.dashboard-metric { display: flex; align-items: center; gap: 12px; padding: 12px 16px; background: #fff; border: 1px solid #ebeef5; border-radius: 4px; }
.metric-icon { display: flex; flex-shrink: 0; justify-content: center; align-items: center; width: 36px; height: 36px; border-radius: 4px; font-size: 22px; }
.metric-icon.blue { background: #ecf5ff; color: #409eff; }
.metric-icon.orange { background: #fdf6ec; color: #e6a23c; }
.metric-icon.red { background: #fef0f0; color: #f56c6c; }
.metric-icon.green { background: #f0f9eb; color: #67c23a; }
.metric-content { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.metric-content > span { color: #606266; font-size: 13px; }
.metric-content > strong { font-size: 24px; line-height: 1.25; font-weight: 600; font-variant-numeric: tabular-nums; }
.metric-content > small { display: none; }
.dashboard-preview-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 12px; }
.dashboard-secondary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.dashboard-secondary-grid > .dashboard-panel { min-height: 176px; }
.dashboard-preview-grid .dashboard-panel-header { min-height: 44px; padding: 10px 16px; }
.dashboard-preview-grid .dashboard-panel-footer { min-height: 32px; padding: 6px 14px; }
.dashboard-panel { min-width: 0; overflow: hidden; background: #fff; border: 1px solid #ebeef5; border-radius: 4px; }
.dashboard-panel-header { justify-content: space-between; gap: 10px; min-height: 48px; padding: 12px 16px; border-bottom: 1px solid #ebeef5; }
.dashboard-panel-header > strong { font-size: 14px; font-weight: 500; }
.dashboard-panel a { color: #409eff; text-decoration: none; font-size: 12px; }
.dashboard-panel a:hover { color: #66b1ff; }
.dashboard-status { display: inline-flex; align-items: center; line-height: 22px; padding: 0 7px; border-radius: 3px; font-size: 12px; font-weight: 400; }
.dashboard-status.success { color: #67c23a; background: #f0f9eb; border: 1px solid #e1f3d8; }
.dashboard-status.neutral { color: #909399; background: #f4f4f5; border: 1px solid #e9e9eb; }
.dashboard-status.danger { color: #f56c6c; background: #fef0f0; border: 1px solid #fde2e2; }
.dashboard-media { display: flex; justify-content: center; height: clamp(340px, calc(100dvh - 520px), 620px); background: #f7f8fa; }
.dashboard-media video, .dashboard-media img { width: 100%; height: 100%; object-fit: contain; }
.dashboard-result { margin: 0; }
.dashboard-empty { display: flex; align-items: center; justify-content: center; flex-direction: column; padding: 24px 16px; gap: 9px; text-align: center; }
.dashboard-empty > .anticon { color: #c0c4cc; font-size: 36px; margin-bottom: 2px; }
.dashboard-empty > strong { font-size: 14px; color: #606266; font-weight: 400; }
.dashboard-empty > span { color: #909399; font-size: 12px; line-height: 1.6; }
.preview-empty { height: clamp(340px, calc(100dvh - 520px), 620px); background: #fafbfc; }
.dashboard-panel-footer { justify-content: space-between; gap: 12px; min-height: 40px; padding: 10px 16px; border-top: 1px solid #ebeef5; color: #909399; font-size: 12px; }
.dashboard-panel-footer > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dashboard-device-details { margin: 0; padding: 8px 12px; }
.dashboard-device-details > div { display: flex; justify-content: space-between; align-items: center; min-height: 24px; gap: 8px; }
.dashboard-device-details dt { flex-shrink: 0; color: #909399; font-size: 12px; }
.dashboard-device-details dd { margin: 0; color: #606266; text-align: right; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dashboard-device-details dd.alert-count { color: #f56c6c; }
.count-note { color: #909399; font-size: 12px; }
.dashboard-secondary-grid .dashboard-panel-header { min-height: 40px; padding: 8px 14px; }
.dashboard-performance-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 10px 16px 0; }
.dashboard-performance-summary > div { display: grid; gap: 3px; }
.dashboard-performance-summary span { color: #909399; font-size: 12px; }
.dashboard-performance-summary strong { font-size: 18px; font-weight: 500; }
.dashboard-performance-summary small { font-size: 12px; font-weight: 400; color: #909399; }
.dashboard-trend { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); align-items: end; gap: 6px; padding: 6px 14px 8px; }
.dashboard-trend-column { min-width: 0; text-align: center; }
.dashboard-trend-track { display: flex; align-items: end; justify-content: center; height: 28px; overflow: hidden; border-bottom: 1px solid #ebeef5; margin-bottom: 3px; }
.dashboard-trend-bar { width: 24px; max-width: 80%; border-radius: 2px 2px 0 0; background: #409eff; }
.dashboard-trend-bar.warning { background: #e6a23c; }
.dashboard-trend-bar.danger { background: #f56c6c; }
.dashboard-trend-column > strong { display: block; font-size: 11px; font-weight: 400; color: #606266; }
.dashboard-trend-column > span { display: block; overflow: hidden; color: #909399; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.dashboard-trend-empty { display: flex; align-items: center; justify-content: center; min-height: 70px; color: #909399; font-size: 12px; }
.dashboard-samples { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding: 10px 14px 6px; }
.dashboard-samples figure { min-width: 0; margin: 0; }
.dashboard-samples img { width: 100%; height: 72px; object-fit: contain; background: #f5f7fa; border-radius: 3px; border: 1px solid #ebeef5; }
.dashboard-samples figcaption { margin-top: 3px; color: #606266; font-size: 11px; text-align: center; }
.dashboard-samples-note { padding: 0 14px 10px; font-size: 11px; line-height: 1.6; color: #909399; }
.dashboard-alerts { max-height: 112px; min-height: 112px; overflow-y: auto; padding: 0 12px; }
.dashboard-alert-item { display: flex; gap: 8px; padding: 8px 0; border-bottom: 1px solid #ebeef5; }
.dashboard-alert-item:last-child { border-bottom: 0; }
.dashboard-alert-item > .anticon { margin-top: 3px; color: #e6a23c; font-size: 14px; }
.dashboard-alert-item > div { min-width: 0; }
.dashboard-alert-item strong { display: block; color: #606266; font-size: 13px; font-weight: 400; }
.dashboard-alert-item p { margin: 5px 0; color: #909399; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.dashboard-alert-item time { color: #b0b3b8; font-size: 11px; }
.dashboard-secondary-grid .dashboard-panel-footer { min-height: 28px; padding: 4px 12px; }
.alert-empty { min-height: 112px; padding: 10px 12px; gap: 5px; }
.alert-empty > .anticon { color: #c2e7b0; font-size: 22px; }
.alert-empty > strong { font-size: 13px; }
.alert-empty > span { font-size: 11px; }
@media (max-width: 1200px) { .dashboard-secondary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .dashboard-metric { padding: 12px; gap: 10px; } }
@media (max-width: 1000px) { .dashboard-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .dashboard-media, .preview-empty { height: 300px; } }
@media (max-width: 640px) { .dashboard-toolbar { align-items: start; flex-direction: column; gap: 12px; } .dashboard-caption { flex-wrap: wrap; gap: 8px; } .dashboard-preview-grid, .dashboard-secondary-grid { grid-template-columns: 1fr; } .dashboard-metrics { gap: 10px; } .dashboard-metric { gap: 8px; padding: 10px; } .metric-content > strong { font-size: 22px; } .metric-icon { width: 32px; height: 32px; font-size: 20px; } .dashboard-media, .preview-empty { height: 276px; } }
</style>
