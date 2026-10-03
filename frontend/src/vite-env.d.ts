/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端服务根地址 (如 http://192.168.1.5:8080); 留空 = 相对路径, 由 Vite 开发代理转发 */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
