//* 实时推送 WebSocket: 浏览器 WebSocket 无法自定义请求头, 故经 token 查询参数认证.
//* 后端 WebSocketAuthUpgradeCheck 支持 Authorization 头与 token 查询参数两种提取方式.

import type { AssessmentVo } from '../types'

export interface AlertPayload {
  type: string
  message: string
  hotline: string
  /** 预约入口随弹窗一并下发; 机构未配置时为空串, 更早的后端可能整个字段缺席 (均判空隐藏) */
  appointmentUrl?: string
}

export interface AlertSocketOptions {
  token: string
  onAlert: (payload: AlertPayload) => void
  onStatus?: (connected: boolean) => void
}

export interface ClinicalFeedOptions {
  token: string
  onAssessment: (assessment: AssessmentVo) => void
  onStatus?: (connected: boolean) => void
}

/** 通用推送连接: 断线自动重连 (3s 起步退避至 30s 上限); 返回断开函数 */
function connectSocket(
  path: string,
  token: string,
  onMessage: (payload: unknown) => void,
  onStatus?: (connected: boolean) => void,
): () => void {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${window.location.host}${path}?token=${encodeURIComponent(token)}`

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
        onMessage(JSON.parse(String(ev.data)))
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

/** 连接 /ws/alert (RED 预警弹窗通道) */
export function connectAlertSocket(options: AlertSocketOptions): () => void {
  const { token, onAlert, onStatus } = options
  return connectSocket(
    '/ws/alert',
    token,
    (payload) => {
      const p = payload as AlertPayload
      if (p.type === 'RED_ALERT') onAlert(p)
    },
    onStatus,
  )
}

/** 连接 /ws/clinical/feed (咨询员工作台: 新评估到达即推); 升级时后端校验 COUNSELOR/ADMIN */
export function connectClinicalFeed(options: ClinicalFeedOptions): () => void {
  const { token, onAssessment, onStatus } = options
  return connectSocket(
    '/ws/clinical/feed',
    token,
    (payload) => {
      const p = payload as { type?: string; assessment?: AssessmentVo }
      if (p.type === 'NEW_ASSESSMENT' && p.assessment) onAssessment(p.assessment)
    },
    onStatus,
  )
}
