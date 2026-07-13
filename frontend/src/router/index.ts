import { createRouter, createWebHistory } from "vue-router";
import AppLayout from "@/layouts/AppLayout.vue";
import DashboardPage from "@/pages/DashboardPage.vue";
import DefectAnalysisPage from "@/pages/DefectAnalysisPage.vue";
import ImageInspectionPage from "@/pages/ImageInspectionPage.vue";
import LoginPage from "@/pages/LoginPage.vue";
import RealtimePage from "@/pages/RealtimePage.vue";
import RecordsPage from "@/pages/RecordsPage.vue";
import ConfigPage from "@/pages/ConfigPage.vue";
import { TOKEN_KEY } from "@/auth/session";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: LoginPage,
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
          component: DashboardPage,
          meta: {
            title: "首页看板",
            label: "OVERVIEW",
          },
        },
        {
          path: "/realtime",
          name: "realtime",
          component: RealtimePage,
          meta: {
            title: "实时质检",
            label: "REALTIME"
          },
        },
        {
          path: "/image",
          name: "image",
          component: ImageInspectionPage,
          meta: {
            title: "图片质检",
            label: "IMAGE"
          },
        },
        {
          path: "/records",
          name: "records",
          component: RecordsPage,
          meta: {
            title: "质检记录",
            label: "RECORDS"
          },
        },
        {
          path: "/defects",
          name: "defects",
          component: DefectAnalysisPage,
          meta: {
            title: "缺陷分析",
            label: "DEFECTS"
          },
        },
        {
          path: "/config/:section(defect|model|camera)",
          name: "config",
          component: ConfigPage,
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
  return true;
});

export default router;
