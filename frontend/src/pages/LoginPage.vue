<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message } from "ant-design-vue";
import { login } from "@/api/auth";
import { TOKEN_KEY } from "@/auth/session";

const router = useRouter();
const route = useRoute();
const loading = ref(false);

const formState = reactive({
  username: "",
  password: "",
});

async function handleSubmit() {
  loading.value = true;
  try {
    const result = await login(formState);
    localStorage.setItem(TOKEN_KEY, result.token);
    message.success(`欢迎回来，${result.displayName}`);
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
    router.replace(redirect);
  } catch (error) {
    message.error(error instanceof Error ? error.message : "登录失败，请检查后端服务");
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-shell">
    <div class="bg-grid"></div>

    <div class="login-card">
      <div class="login-brand">
        <div class="logo">AI</div>
        <div>
          <h1>面料AI质检分析系统</h1>
          <span>面料检测 · 缺陷分析 · 质量决策</span>
        </div>
      </div>

      <div class="login-form-head">
        <strong>账号登录</strong>
        <span>请输入授权账号进入系统</span>
      </div>

      <a-form layout="vertical" :model="formState" class="login-form" @finish="handleSubmit">
        <a-form-item label="用户名" name="username">
          <a-input v-model:value="formState.username" size="large" placeholder="请输入管理员账号" />
        </a-form-item>
        <a-form-item label="密码" name="password">
          <a-input-password v-model:value="formState.password" size="large" placeholder="请输入密码" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" size="large" :loading="loading" class="login-button" html-type="submit" block>
            进入系统
          </a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
