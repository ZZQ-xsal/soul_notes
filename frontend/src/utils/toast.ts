//* 轻量全局提示: 模块级状态 + 订阅, ToastHost 通过 useSyncExternalStore 渲染.

export type ToastType = 'info' | 'success' | 'warning' | 'error'

export interface ToastItem {
  id: number
  text: string
  type: ToastType
}

let items: ToastItem[] = []
let seq = 0
let version = 0
const listeners = new Set<() => void>()

function emit(): void {
  version += 1
  listeners.forEach((fn) => fn())
}

/** 弹出一条提示, 3.2s 后自动消失 */
export function toast(text: string, type: ToastType = 'info'): void {
  const item: ToastItem = { id: ++seq, text, type }
  items = [...items, item]
  emit()
  window.setTimeout(() => {
    items = items.filter((t) => t.id !== item.id)
    emit()
  }, 3200)
}

export function subscribeToasts(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function getToastVersion(): number {
  return version
}

export function getToasts(): ToastItem[] {
  return items
}
