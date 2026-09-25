//* 危机预警与热线全局状态:
//* 1. 热线信息经 /crisis/hotline 拉取并缓存于 localStorage (离线兜底精神);
//* 2. 登录后连接 /ws/alert, RED 预警推送 -> 全局弹窗.

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { getHotline } from '../api/crisis'
import { connectAlertSocket } from '../api/ws'
import type { HotlineInfo } from '../types'
import { useAuth } from './AuthContext'

const HOTLINE_CACHE_KEY = 'soul.hotline'

//* 与后端 RedisStartupConfig 静态默认值保持一致, 作为最终兜底.
const DEFAULT_HOTLINE: HotlineInfo = {
  name: '全国心理援助热线',
  primary: '400-161-9995',
  backup: '12355',
  message: '你不需要独自面对一切, 专业的帮助随时可用。',
  //* 预约入口无静态默认值: 机构未配置即不展示.
  appointmentUrl: '',
}

function loadCachedHotline(): HotlineInfo {
  try {
    const raw = localStorage.getItem(HOTLINE_CACHE_KEY)
    //! 展开默认值兜底: 旧版本缓存没有 appointmentUrl 键 (后端 1.4.0 才下发).
    return raw ? { ...DEFAULT_HOTLINE, ...(JSON.parse(raw) as Partial<HotlineInfo>) } : DEFAULT_HOTLINE
  } catch {
    return DEFAULT_HOTLINE
  }
}

export interface AlertState {
  visible: boolean
  message: string
  hotline: string
  /** 弹窗自带的预约入口 (随 RED 推送下发); 空串 = 回落全局 hotline 的配置值 */
  appointmentUrl: string
}

interface AlertContextValue {
  hotline: HotlineInfo
  alert: AlertState
  showAlert: (message: string, hotline?: string | null, appointmentUrl?: string | null) => void
  dismiss: () => void
}

const AlertContext = createContext<AlertContextValue | null>(null)

export function AlertProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth()
  const [hotline, setHotline] = useState<HotlineInfo>(loadCachedHotline)
  const [alert, setAlert] = useState<AlertState>({ visible: false, message: '', hotline: '', appointmentUrl: '' })

  //* 供 WS 回调读取最新热线 (避免闭包过期).
  const hotlineRef = useRef(hotline)
  hotlineRef.current = hotline

  //* 启动时刷新热线并写缓存; 失败时保留缓存/默认值 (离线兜底).
  useEffect(() => {
    getHotline()
      .then((h) => {
        setHotline(h)
        localStorage.setItem(HOTLINE_CACHE_KEY, JSON.stringify(h))
      })
      .catch(() => {
        //* 后端不可达时静默使用缓存或默认值
      })
  }, [])

  const showAlert = useCallback((message: string, hotlineNum?: string | null, appointmentUrl?: string | null) => {
    setAlert({ visible: true, message, hotline: hotlineNum || hotlineRef.current.primary, appointmentUrl: appointmentUrl || '' })
  }, [])

  const dismiss = useCallback(() => {
    setAlert((a) => ({ ...a, visible: false }))
  }, [])

  //* 登录后建立预警 WebSocket, 登出/组件卸载时断开 (含退避重连).
  useEffect(() => {
    if (!token) return
    const stop = connectAlertSocket({
      token,
      onAlert: (payload) => showAlert(payload.message, payload.hotline, payload.appointmentUrl),
    })
    return stop
  }, [token, showAlert])

  const value = useMemo<AlertContextValue>(() => ({ hotline, alert, showAlert, dismiss }), [hotline, alert, showAlert, dismiss])

  return <AlertContext.Provider value={value}>{children}</AlertContext.Provider>
}

export function useAlert(): AlertContextValue {
  const ctx = useContext(AlertContext)
  if (!ctx) throw new Error('useAlert 必须在 AlertProvider 内使用')
  return ctx
}
