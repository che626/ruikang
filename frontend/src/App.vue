<script setup lang="ts">
import zhCN from "ant-design-vue/es/locale/zh_CN";

const theme = {
  token: {
    colorPrimary: "#409eff", colorInfo: "#409eff", colorSuccess: "#67c23a",
    colorWarning: "#e6a23c", colorError: "#f56c6c", colorText: "#303133",
    colorTextSecondary: "#606266", colorBorder: "#dcdfe6", colorBgLayout: "#f5f7f9",
    borderRadius: 4, fontSize: 14, controlHeight: 32,
    fontFamily: '"Helvetica Neue", Helvetica, "PingFang SC", "Microsoft YaHei", Arial, sans-serif',
  },
  components: {
    Button: { primaryShadow: "none", defaultShadow: "none", dangerShadow: "none" },
    Table: { headerBg: "#f8f8f9", headerColor: "#606266", rowHoverBg: "#f5f7fa", cellPaddingBlock: 12, cellPaddingBlockSM: 10 },
  },
};
</script>

<template>
  <a-config-provider :theme="theme" :locale="zhCN"><router-view /></a-config-provider>
</template>
