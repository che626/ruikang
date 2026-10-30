<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message } from "ant-design-vue";
import { UserOutlined, LockOutlined } from "@ant-design/icons-vue";
import { login } from "@/api/auth";
import { SESSION_MARKER, TOKEN_KEY } from "@/auth/session";

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
    localStorage.setItem(TOKEN_KEY, SESSION_MARKER);
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
    <div class="login-card">
      <div class="login-brand">
        <svg class="brand-mark" width="38" height="38" viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <path d="M16 2 30 10 16 18 2 10 16 2Z" fill="#409eff" />
          <path d="m2 16 14 8 14-8v6L16 30 2 22v-6Z" fill="#79bbff" />
          <path d="m2 10 14 8v6L2 16v-6Z" fill="#337ecc" />
        </svg>
        <div>
          <h1>面料质检系统</h1>
          <span>管理后台</span>
        </div>
      </div>

      <div class="login-form-head">
        <strong>账号登录</strong>
        <span>请输入账号和密码</span>
      </div>

      <a-form layout="vertical" :model="formState" class="login-form" @finish="handleSubmit">
        <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model:value="formState.username" size="large" placeholder="请输入用户名" autocomplete="username"><template #prefix><UserOutlined /></template></a-input>
        </a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="formState.password" size="large" placeholder="请输入密码" autocomplete="current-password"><template #prefix><LockOutlined /></template></a-input-password>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" size="large" :loading="loading" class="login-button" html-type="submit" block>
            登录
          </a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
