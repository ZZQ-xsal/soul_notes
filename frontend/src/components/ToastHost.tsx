//* 全局提示容器: 订阅 toast 模块, 渲染底部居中提示条.

import { useSyncExternalStore } from 'react'
import { getToasts, getToastVersion, subscribeToasts } from '../utils/toast'
import type { ToastItem } from '../utils/toast'

const TYPE_ICON: Record<ToastItem['type'], string> = {
  info: 'ℹ',
  success: '✓',
  warning: '⚠',
  error: '✕',
}

export default function ToastHost() {
  const version = useSyncExternalStore(subscribeToasts, getToastVersion)
  void version //* 快照版本号驱动重渲染, 列表内容从模块读取.
  const toasts = getToasts()

  return (
    <div className="toast-host" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.type}`}>
          <span className="toast-icon" aria-hidden="true">
            {TYPE_ICON[t.type]}
          </span>
          <span className="toast-text">{t.text}</span>
        </div>
      ))}
    </div>
  )
}
