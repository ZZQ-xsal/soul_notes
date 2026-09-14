//* 预警 WebSocket: 浏览器 WebSocket 无法自定义请求头, 故经 token 查询参数认证.
//* 后端 WebSocketAuthUpgradeCheck 支持 Authorization 头与 token 查询参数两种提取方式.

export interface AlertPayload {
  type: string
  message: string
  hotline: string
}

export interface AlertSocketOptions {
  token: string
  onAlert: (payload: AlertPayload) => void
  onStatus?: (connected: boolean) => void
}

/** 连接 /ws/alert, 断线自动重连 (3s 起步退避至 30s 上限); 返回断开函数 */
export function connectAlertSocket(options: AlertSocketOptions): () => void {
  const { token, onAlert, onStatus } = options
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${window.location.host}/ws/alert?token=${encodeURIComponent(token)}`

  let ws: WebSocket | null = null
  let closed = false
  let retries = 0
  let timer: number | null = null

  const open = (): void => {
    if (closed) return
    ws = new WebSocket(url)
    ws.onopen = () => {
      retries = 0
      onStatus?.(true)
    }
    ws.onmessage = (ev) => {
      try {
        const payload = JSON.parse(String(ev.data)) as AlertPayload
        if (payload.type === 'RED_ALERT') onAlert(payload)
      } catch {
        //* 忽略无法解析的消息, 不中断连接
      }
    }
    ws.onclose = () => {
      onStatus?.(false)
      if (closed) return
      const delay = Math.min(3000 * 2 ** retries, 30000)
      retries += 1
      timer = window.setTimeout(open, delay)
    }
    ws.onerror = () => {
      ws?.close()
    }
  }

  open()

  return () => {
    closed = true
    if (timer !== null) window.clearTimeout(timer)
    ws?.close()
  }
}
