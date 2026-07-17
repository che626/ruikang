import { createRouter, createWebHistory } from "vue-router";
import AppLayout from "@/layouts/AppLayout.vue";
import { SESSION_MARKER, TOKEN_KEY } from "@/auth/session";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/pages/LoginPage.vue"),
      meta: {
        public: true,
        title: "系统登录",
        label: "AUTH",
      },
    },
    {
      path: "/",
      component: AppLayout,
      children: [
        {
          path: "",
          redirect: "/dashboard",
        },
        {
          path: "/dashboard",
          name: "dashboard",
          component: () => import("@/pages/DashboardPage.vue"),
          meta: {
            title: "首页看板",
            label: "OVERVIEW",
          },
        },
        {
          path: "/realtime",
          name: "realtime",
          component: () => import("@/pages/RealtimePage.vue"),
          meta: {
            title: "实时质检",
            label: "REALTIME"
          },
        },
        {
          path: "/image",
          name: "image",
          component: () => import("@/pages/ImageInspectionPage.vue"),
          meta: {
            title: "图片质检",
            label: "IMAGE"
          },
        },
        {
          path: "/records",
          name: "records",
          component: () => import("@/pages/RecordsPage.vue"),
          meta: {
            title: "质检记录",
            label: "RECORDS"
          },
        },
        {
          path: "/defects",
          name: "defects",
          component: () => import("@/pages/DefectAnalysisPage.vue"),
          meta: {
            title: "缺陷分析",
            label: "DEFECTS"
          },
        },
        {
          path: "/config/:section(defect|model|camera)",
          name: "config",
          component: () => import("@/pages/ConfigPage.vue"),
          meta: {
            title: "系统配置",
            label: "CONFIG"
          },
        },
        {
          path: "/exceptions",
          redirect: "/dashboard",
        },
        {
          path: "/config/param",
          redirect: "/config/camera",
        },
      ],
    },
  ],
});

router.beforeEach((to) => {
  if (to.meta.public) {
    return true;
  }

  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) {
    return {
      path: "/login",
      query: {
        redirect: to.fullPath,
      },
    };
  }
  if (token !== SESSION_MARKER) {
    localStorage.setItem(TOKEN_KEY, SESSION_MARKER);
  }
  return true;
});

export default router;
