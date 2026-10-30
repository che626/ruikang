<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message } from "ant-design-vue";
import {
  HomeOutlined, VideoCameraOutlined, PictureOutlined, FileSearchOutlined,
  BarChartOutlined, TagsOutlined, DeploymentUnitOutlined, SettingOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, SearchOutlined, ReloadOutlined,
  FullscreenOutlined, FullscreenExitOutlined, DownOutlined, RightOutlined,
  CloseOutlined, LogoutOutlined, UserOutlined,
} from "@ant-design/icons-vue";
import { fetchRealtimeStatus, type RealtimeStatus } from "@/api/realtime";
import { useRealtimeStream } from "@/composables/useRealtimeStream";
import http from "@/api/http";
import { TOKEN_KEY } from "@/auth/session";

type MenuItem = { title: string; path: string; icon: Component };
const router = useRouter();
const route = useRoute();
const stream = useRealtimeStream();

const primaryMenu: MenuItem[] = [
  { title: "首页看板", path: "/dashboard", icon: HomeOutlined },
  { title: "实时质检", path: "/realtime", icon: VideoCameraOutlined },
  { title: "图片质检", path: "/image", icon: PictureOutlined },
  { title: "质检记录", path: "/records", icon: FileSearchOutlined },
  { title: "缺陷分析", path: "/defects", icon: BarChartOutlined },
];
const configMenu: MenuItem[] = [
  { title: "缺陷类型", path: "/config/defect", icon: TagsOutlined },
  { title: "模型管理", path: "/config/model", icon: DeploymentUnitOutlined },
  { title: "摄像头配置", path: "/config/camera", icon: VideoCameraOutlined },
];
const allMenu = [...primaryMenu, ...configMenu];
const collapsed = ref(window.matchMedia("(max-width: 768px)").matches || localStorage.getItem("qc-sidebar-collapsed") === "true");
const systemExpanded = ref(true);
const clock = ref("");
const fullscreen = ref(false);
const pageRevision = ref(0);
const refreshBusy = ref(false);
const searchOpen = ref(false);
const searchText = ref("");
const visitedTabs = ref([{ path: "/dashboard", title: "首页看板" }]);
const tabsNav = ref<HTMLElement | null>(null);
const runtime = ref<RealtimeStatus | null>(null);
let clockTimer: number | undefined;
let runtimeTimer: number | undefined;
let refreshTimer: number | undefined;
const narrowScreen = window.matchMedia("(max-width: 768px)");

const currentItem = computed(() => allMenu.find((item) => item.path === route.path));
const currentTitle = computed(() => currentItem.value?.title ?? String(route.meta.title ?? "首页看板"));
const currentGroup = computed(() => route.path.startsWith("/config/") ? "系统管理" : "质检管理");
const cameraRunning = computed(() => runtime.value?.runtimeStatus === "RUNNING");
const searchResults = computed(() => allMenu.filter((item) => item.title.includes(searchText.value.trim())));

function go(path: string) {
  router.push(path);
  searchOpen.value = false;
  if (narrowScreen.matches) collapsed.value = true;
}
function onScreenChange(event: MediaQueryListEvent) { if (event.matches) collapsed.value = true; }
function toggleSidebar() {
  collapsed.value = !collapsed.value;
  localStorage.setItem("qc-sidebar-collapsed", String(collapsed.value));
}
function closeTab(path: string) {
  const index = visitedTabs.value.findIndex((tab) => tab.path === path);
  visitedTabs.value = visitedTabs.value.filter((tab) => tab.path !== path);
  if (route.path === path) go(visitedTabs.value[Math.max(0, index - 1)]?.path ?? "/dashboard");
}
function closeOtherTabs() {
  visitedTabs.value = visitedTabs.value.filter((tab) => tab.path === '/dashboard' || tab.path === route.path);
}
async function refreshRuntime() {
  try { runtime.value = await fetchRealtimeStatus(); } catch { runtime.value = null; }
}
function refreshPage() {
  if (refreshBusy.value) return;
  pageRevision.value += 1;
  refreshBusy.value = true;
  refreshRuntime();
  refreshTimer = window.setTimeout(() => { refreshBusy.value = false; }, 600);
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch { message.info("当前浏览器暂不支持全屏，请使用浏览器全屏功能"); }
}
function updateFullscreen() { fullscreen.value = Boolean(document.fullscreenElement); }
function updateClock() {
  clock.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
}
async function logout() {
  if (stream.running.value) await stream.stop();
  await http.post("/auth/logout");
  localStorage.removeItem(TOKEN_KEY);
  router.replace("/login");
}
function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    searchText.value = "";
    searchOpen.value = true;
  }
}
watch(() => route.path, async (path) => {
  const item = allMenu.find((menu) => menu.path === path);
  if (item && !visitedTabs.value.some((tab) => tab.path === path)) {
    visitedTabs.value.push({ path, title: item.title });
  }
  if (path.startsWith("/config/")) systemExpanded.value = true;
  await nextTick();
  tabsNav.value?.querySelector(".page-tab.active")?.scrollIntoView({ block: "nearest", inline: "nearest" });
}, { immediate: true });

onMounted(() => {
  updateClock();
  refreshRuntime();
  clockTimer = window.setInterval(updateClock, 1000);
  runtimeTimer = window.setInterval(refreshRuntime, 4000);
  document.addEventListener("fullscreenchange", updateFullscreen);
  document.addEventListener("keydown", onKeydown);
  narrowScreen.addEventListener("change", onScreenChange);
});
onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer);
  if (runtimeTimer) window.clearInterval(runtimeTimer);
  if (refreshTimer) window.clearTimeout(refreshTimer);
  document.removeEventListener("fullscreenchange", updateFullscreen);
  document.removeEventListener("keydown", onKeydown);
  narrowScreen.removeEventListener("change", onScreenChange);
});
</script>

<template>
  <div :class="['app-layout', { 'sidebar-collapsed': collapsed }]">
    <button v-if="!collapsed" class="mobile-sidebar-backdrop" type="button" aria-label="关闭导航菜单" @click="collapsed = true" />
    <aside class="sidebar">
      <router-link class="brand" to="/dashboard" aria-label="面料质检系统首页">
        <svg class="brand-mark" width="30" height="30" viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <path d="M16 2 30 10 16 18 2 10 16 2Z" fill="#409eff" />
          <path d="m2 16 14 8 14-8v6L16 30 2 22v-6Z" fill="#79bbff" />
          <path d="m2 10 14 8v6L2 16v-6Z" fill="#337ecc" />
        </svg>
        <h1 v-if="!collapsed">面料质检系统</h1>
      </router-link>

      <nav class="sidebar-nav" aria-label="主导航">
        <div v-if="!collapsed" class="menu-label">质检管理</div>
        <button v-for="item in primaryMenu" :key="item.path" type="button"
          :class="['menu-item', { active: route.path === item.path }]"
          :title="collapsed ? item.title : undefined" :aria-label="item.title" :aria-current="route.path === item.path ? 'page' : undefined"
          @click="go(item.path)">
          <component :is="item.icon" class="menu-icon" />
          <span v-if="!collapsed">{{ item.title }}</span>
        </button>

        <button type="button" :class="['menu-group', { selected: route.path.startsWith('/config/') }]"
          :title="collapsed ? '系统管理' : undefined" :aria-expanded="collapsed || systemExpanded"
          @click="collapsed ? go('/config/defect') : systemExpanded = !systemExpanded">
          <SettingOutlined class="menu-icon" />
          <span v-if="!collapsed">系统管理</span>
          <DownOutlined v-if="!collapsed && systemExpanded" class="group-arrow" />
          <RightOutlined v-else-if="!collapsed" class="group-arrow" />
        </button>
        <div v-if="collapsed || systemExpanded" class="submenu">
          <button v-for="item in configMenu" :key="item.path" type="button"
            :class="['menu-item', { active: route.path === item.path }]"
            :title="collapsed ? item.title : undefined" :aria-label="item.title" :aria-current="route.path === item.path ? 'page' : undefined"
            @click="go(item.path)">
            <component :is="item.icon" class="menu-icon" />
            <span v-if="!collapsed">{{ item.title }}</span>
          </button>
        </div>
      </nav>

      <div class="sidebar-footer" :title="runtime ? (cameraRunning ? '实时监控中' : '实时监控待机') : '设备状态暂不可用'">
        <span :class="['status-dot', { running: cameraRunning }]" />
        <span v-if="!collapsed">{{ runtime ? (cameraRunning ? '实时监控中' : '实时监控待机') : '设备状态暂不可用' }}</span>
      </div>
    </aside>

    <main class="main-shell">
      <header class="topbar">
        <button class="icon-button collapse-button" type="button" :aria-label="collapsed ? '展开菜单' : '折叠菜单'" @click="toggleSidebar">
          <MenuUnfoldOutlined v-if="collapsed" /><MenuFoldOutlined v-else />
        </button>
        <a-breadcrumb class="header-breadcrumb">
          <a-breadcrumb-item><router-link to="/dashboard"><HomeOutlined /> <span class="breadcrumb-home">首页</span></router-link></a-breadcrumb-item>
          <a-breadcrumb-item v-if="route.path !== '/dashboard'">{{ currentGroup }}</a-breadcrumb-item>
          <a-breadcrumb-item v-if="route.path !== '/dashboard'">{{ currentTitle }}</a-breadcrumb-item>
        </a-breadcrumb>
        <div class="topbar-right">
          <button class="header-search" type="button" aria-label="搜索页面" @click="searchOpen = true; searchText = ''">
            <SearchOutlined /><span>搜索页面</span><kbd>Ctrl K</kbd>
          </button>
          <span class="header-clock">{{ clock }}</span>
          <a-tooltip title="刷新页面">
            <button class="icon-button" type="button" aria-label="刷新页面" @click="refreshPage"><ReloadOutlined :spin="refreshBusy" /></button>
          </a-tooltip>
          <a-tooltip :title="fullscreen ? '退出全屏' : '全屏显示'">
            <button class="icon-button fullscreen-button" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏显示'" @click="toggleFullscreen">
              <FullscreenExitOutlined v-if="fullscreen" /><FullscreenOutlined v-else />
            </button>
          </a-tooltip>
          <a-dropdown :trigger="['click']">
            <button class="account-button" type="button" aria-label="管理员菜单"><span class="account-avatar"><UserOutlined /></span><span class="account-name">管理员</span><DownOutlined class="account-arrow" /></button>
            <template #overlay>
              <a-menu><a-menu-item key="identity" disabled>系统管理员</a-menu-item><a-menu-divider /><a-menu-item key="logout" @click="logout"><LogoutOutlined /> 退出登录</a-menu-item></a-menu>
            </template>
          </a-dropdown>
        </div>
      </header>

      <div class="tabs-bar">
        <nav ref="tabsNav" class="page-tabs" aria-label="已打开页面">
          <div v-for="tab in visitedTabs" :key="tab.path" :class="['page-tab', { active: route.path === tab.path }]">
            <button type="button" :aria-current="route.path === tab.path ? 'page' : undefined" @click="go(tab.path)">
              <HomeOutlined v-if="tab.path === '/dashboard'" /><span v-else class="tab-dot" />{{ tab.title }}
            </button>
            <button v-if="tab.path !== '/dashboard'" class="tab-close" type="button" :aria-label="'关闭' + tab.title + '页签'" @click="closeTab(tab.path)"><CloseOutlined /></button>
          </div>
        </nav>
        <a-dropdown :trigger="['click']">
          <button class="tabs-action icon-button" type="button" aria-label="页签操作"><DownOutlined /></button>
          <template #overlay>
            <a-menu><a-menu-item key="closeOthers" @click="closeOtherTabs">关闭其他页签</a-menu-item><a-menu-item key="closeAll" @click="visitedTabs = [{ path: '/dashboard', title: '首页看板' }]; go('/dashboard')">关闭全部页签</a-menu-item></a-menu>
          </template>
        </a-dropdown>
      </div>

      <section class="content-shell">
        <router-view :key="route.path + '-' + pageRevision" />
      </section>
    </main>

    <a-modal v-model:open="searchOpen" title="页面搜索" :footer="null" :width="480" :destroy-on-close="true">
      <a-input v-model:value="searchText" placeholder="输入页面名称" allow-clear autofocus @press-enter="searchResults[0] && go(searchResults[0].path)"><template #prefix><SearchOutlined /></template></a-input>
      <div class="navigation-search-list">
        <button v-for="item in searchResults" :key="item.path" type="button" @click="go(item.path)"><component :is="item.icon" /><span>{{ item.title }}</span><RightOutlined /></button>
        <a-empty v-if="!searchResults.length" description="没有匹配的页面" />
      </div>
    </a-modal>
  </div>
</template>
