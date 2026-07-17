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

const primaryMenu: MenuItem[] = [
  { title: "首页看板", path: "/dashboard", icon: "◉" },
  { title: "实时监控", path: "/realtime", icon: "▣" },
  { title: "图片质检", path: "/image", icon: "▧" },
  { title: "质检记录", path: "/records", icon: "◎" },
  { title: "缺陷分析", path: "/defects", icon: "◇" },
];

const configMenu: MenuItem[] = [
  { title: "缺陷类型", path: "/config/defect", icon: "▦" },
  { title: "模型管理", path: "/config/model", icon: "◈" },
  { title: "摄像头配置", path: "/config/camera", icon: "⚙" },
];

const clock = ref("--:--:--");
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
  targetInferenceFps: 10,
  evidenceImageCount: 3,
});

let clockTimer: number | undefined;
let runtimeTimer: number | undefined;

const currentTitle = computed(() => String(route.meta.title ?? "首页看板"));
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
          <span class="menu-icon">{{ item.icon }}</span>
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
          <span class="menu-icon">{{ item.icon }}</span>
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
        <h2 class="page-title sample-page-title">{{ currentTitle }}</h2>

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
