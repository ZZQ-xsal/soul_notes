//* 后端地址解析中心: 开发期留空走相对路径 (由 Vite 代理转发, 见 vite.config.ts),
//* 套壳 APP / 独立部署时经构建期环境变量 VITE_API_BASE 注入后端服务根地址.

/** 读取并校验 VITE_API_BASE; 留空或格式非法时回退相对路径 (非法值显式报错, 避免静默连错地址) */
function readBase(): string {
  const raw = (import.meta.env.VITE_API_BASE ?? '').trim().replace(/\/+$/, '')
  if (!raw) return ''
  try {
    new URL(raw)
    return raw
  } catch {
    console.error(`[soul] VITE_API_BASE 不是合法的绝对地址, 已回退相对路径: ${raw}`)
    return ''
  }
}

/** 后端服务根地址 (无尾部斜杠); 空串 = 相对路径模式 (依赖 Vite 代理) */
export const RAW_BASE = readBase()

/** REST 接口前缀 */
export const API_BASE = `${RAW_BASE}/api/v1`

/** 后端下发的相对地址 (如语音 audioUrl) 补全为可访问地址; 已是 http(s) 绝对地址则原样返回 */
export function resolveUrl(path: string): string {
  return /^https?:\/\//i.test(path) ? path : RAW_BASE + path
}

/** WebSocket 完整地址: 由后端根地址推导协议 (http→ws, https→wss) 与主机; 未配置时沿用当前页面主机.
 *  注意: 不保留根地址中的路径前缀 (WebSocket 端点固定挂在服务根, 与 /api/v1 不同). */
export function wsUrl(path: string): string {
  const target = new URL(RAW_BASE || window.location.href)
  const proto = target.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${target.host}${path}`
}
