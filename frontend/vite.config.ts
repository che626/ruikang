import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { viteStaticCopy } from "vite-plugin-static-copy";
import path from "node:path";

export default defineConfig({
  plugins: [
    vue(),
    viteStaticCopy({
      targets: [
        {
          src: "node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.asyncify.{mjs,wasm}",
          dest: "ort",
        },
      ],
    }),
  ],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
    conditions: ["onnxruntime-web-use-extern-wasm"],
  },
  server: {
    host: "0.0.0.0",
    port: 5173,
    headers: {
      "Cross-Origin-Opener-Policy": "same-origin",
      "Cross-Origin-Embedder-Policy": "require-corp",
      "Cross-Origin-Resource-Policy": "same-origin",
    },
    proxy: {
      "/api": {
        target: "http://localhost:8081",
        changeOrigin: true,
        configure(proxy) {
          proxy.on("proxyReq", (proxyRequest) => {
            proxyRequest.removeHeader("origin");
          });
        },
      },
    },
  },
});
