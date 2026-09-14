import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

//* 开发期将 REST (/api) 与 WebSocket (/ws) 请求代理到 Quarkus 后端, 前端无需关心跨域.
//* 端口固定 5173, 与后端 CORS 白名单默认值 quarkus.http.cors.origins 保持一致.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: true },
    },
  },
})
