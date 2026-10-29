<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchRealtimeStatus, type RealtimeStatus } from "@/api/realtime";

type MenuItem = {
  title: string;
  path: string;
  icon: string;
};

const router = useRouter();
const route = useRoute();

const primaryMenu = [
  { title: "首页看板", path: "/dashboard", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="1" y="1" width="6" height="6" rx="1"/><rect x="9" y="1" width="6" height="6" rx="1"/><rect x="1" y="9" width="6" height="6" rx="1"/><rect x="9" y="9" width="6" height="6" rx="1"/></svg>` },
  { title: "实时监控", path: "/realtime", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="1" y="3" width="14" height="9" rx="1.5"/><path d="M5 12v2M11 12v2M3 14h10"/><circle cx="8" cy="7.5" r="2" fill="none"/></svg>` },
  { title: "图片质检", path: "/image", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="1" y="1" width="14" height="14" rx="1.5"/><path d="M1 11l4-4 3 3 2-2 5 5"/><circle cx="11" cy="5" r="1.5" fill="currentColor" stroke="none"/></svg>` },
  { title: "质检记录", path: "/records", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 1h10a1 1 0 011 1v12a1 1 0 01-1 1H3a1 1 0 01-1-1V2a1 1 0 011-1z"/><path d="M5 5h6M5 8h6M5 11h3"/></svg>` },
  { title: "缺陷分析", path: "/defects", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 12l3-4 3 2 3-6 3 4"/><path d="M1 14h14"/></svg>` },
];

const configMenu = [
  { title: "缺陷类型", path: "/config/defect", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><polygon points="8,2 14,6 14,10 8,14 2,10 2,6"/><circle cx="8" cy="8" r="2"/></svg>` },
  { title: "模型管理", path: "/config/model", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="8" cy="3" r="2"/><circle cx="3" cy="13" r="2"/><circle cx="13" cy="13" r="2"/><path d="M8 5v3M8 8l-3 3M8 8l3 3"/></svg>` },
  { title: "摄像头配置", path: "/config/camera", icon: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="1" y="4" width="10" height="8" rx="1.5"/><path d="M11 7l4-2v6l-4-2"/><circle cx="6" cy="8" r="2"/></svg>` },
];

const configMenuOpen = ref(true);
const clock = ref("--:--:--");
const currentTitle = computed(() => String(route.meta.title ?? "首页看板"));

const breadcrumbs = computed(() => {
  const path = route.path;
  const map: Record<string, string[]> = {
    "/dashboard": ["首页", "首页看板"],
    "/realtime": ["首页", "质检管理", "实时质检"],
    "/image": ["首页", "质检管理", "图片质检"],
    "/records": ["首页", "质检管理", "质检记录"],
    "/defects": ["首页", "质检管理", "缺陷分析"],
    "/config/defect": ["首页", "系统管理", "缺陷类型"],
    "/config/model": ["首页", "系统管理", "模型管理"],
    "/config/camera": ["首页", "系统管理", "摄像头配置"],
  };
  return map[path] ?? ["首页", currentTitle.value];
});

const runtime = ref<RealtimeStatus>({
  cameraName: "Webcam 0",
  runtimeStatus: "STOPPED",
  previewFps: 0,
  inferenceFps: 0,
  latencyMs: 0,
  providerStrategy: "尚未连接",
  modelPath: "models/ruikang.onnx",
  confidenceThreshold: 0.45,
  iouThreshold: 0.42,
  continuousHitFrames: 4,
  targetInferenceFps: 60,
  evidenceImageCount: 3,
});

let clockTimer: number | undefined;
let runtimeTimer: number | undefined;

const systemRunningText = computed(() => {
  if (runtime.value.runtimeStatus === "RUNNING") return "正常";
  if (runtime.value.runtimeStatus === "ERROR") return "异常";
  return "待机";
});
const databaseModeText = computed(() => "MySQL");

function isActive(path: string) {
  return route.path === path || route.path.startsWith(path + "/");
}

function go(path: string) {
  router.push(path);
}

function updateClock() {
  clock.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

async function refreshRuntime() {
  try {
    runtime.value = await fetchRealtimeStatus();
  } catch {
    runtime.value = {
      ...runtime.value,
      runtimeStatus: "ERROR",
      previewFps: 0,
      inferenceFps: 0,
      latencyMs: 0,
    };
  }
}

watch(() => route.fullPath, () => updateClock());

onMounted(() => {
  updateClock();
  refreshRuntime();
  clockTimer = window.setInterval(updateClock, 1000);
  runtimeTimer = window.setInterval(refreshRuntime, 4000);
});

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer);
  if (runtimeTimer) window.clearInterval(runtimeTimer);
});
</script>

<template>
  <div class="bg-grid"></div>

  <div class="app-layout">
    <aside class="sidebar">
      <div class="brand sample-brand">
        <div class="logo sample-logo">R</div>
        <div class="brand-text">
          <h1 class="brand-title">  AI 质检平台</h1>
          <p class="brand-subtitle sample-subtitle">FABRIC INSPECTION</p>
        </div>
      </div>

      <div class="menu-label sample-menu-label">主菜单</div>
      <nav class="menu">
        <button
          v-for="item in primaryMenu"
          :key="item.path"
          :class="{ active: isActive(item.path) }"
          @click="go(item.path)"
        >
          <span class="menu-icon" v-html="item.icon"></span>
          <span class="menu-text">{{ item.title }}</span>
          <span v-if="isActive(item.path)" class="menu-dot"></span>
        </button>
      </nav>
      <div class="menu-label sample-menu-label">系统管理</div>
      <nav class="menu secondary">
        <button
          v-for="item in configMenu"
          :key="item.path"
          :class="{ active: isActive(item.path) }"
          @click="go(item.path)"
        >
          <span class="menu-icon" v-html="item.icon"></span>
          <span class="menu-text">{{ item.title }}</span>
          <span v-if="isActive(item.path)" class="menu-dot"></span>
        </button>
      </nav>
      <div class="run-card sample-status-card">
        <div class="sample-status-title">系统状态</div>
        <div class="status-row">
          <span>系统运行</span>
          <strong :class="{ ok: runtime.runtimeStatus === 'RUNNING' }">{{ systemRunningText }}</strong>
        </div>
        <div class="status-row">
          <span>数据库</span>
          <strong class="ok">{{ databaseModeText }}</strong>
        </div>
      </div>
    </aside>

    <main class="main-shell">
      <header class="topbar sample-topbar">
        <div class="topbar-left-group">
          <nav class="breadcrumb">
            <span v-for="(crumb, idx) in breadcrumbs" :key="idx" class="breadcrumb-item">
              <span v-if="idx > 0" class="breadcrumb-sep">/</span>
              <span :class="idx === breadcrumbs.length - 1 ? 'breadcrumb-current' : 'breadcrumb-link'">{{ crumb }}</span>
            </span>
          </nav>
        </div>

        <div class="topbar-right sample-topbar-right">
          <span class="badge sample-badge ok">● {{ runtime.runtimeStatus === 'RUNNING' ? '在线' : '待机' }}</span>
          <span class="badge sample-badge">{{ clock }}</span>
        </div>
      </header>

      <section class="content-shell">
        <router-view />
      </section>
    </main>
  </div>
</template>
