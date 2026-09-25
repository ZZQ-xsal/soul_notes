//* 树洞对话接口: /api/v1/chat/* (非流式 / SSE 流式 / 会话列表 / 历史消息 / 删除会话)

import { api, ApiError, getToken } from './http'
import type { ApiResponse, ChatMessage, ChatSessionVo } from '../types'

export function listSessions(): Promise<ChatSessionVo[]> {
  return api<ChatSessionVo[]>('/chat/sessions')
}

export function listMessages(sessionId: string): Promise<ChatMessage[]> {
  return api<ChatMessage[]>(`/chat/sessions/${sessionId}/messages`)
}

export function deleteSession(sessionId: string): Promise<void> {
  return api<void>(`/chat/sessions/${sessionId}`, { method: 'DELETE' })
}

export function sendMessage(sessionId: string | null, content: string): Promise<ChatMessage> {
  return api<ChatMessage>('/chat/send', { method: 'POST', body: { sessionId, content } })
}

export interface StreamOptions {
  sessionId: string | null
  content: string
  onChunk: (chunk: string) => void
  signal?: AbortSignal
}

//* SSE 流式回复: Quarkus 将 Multi<String> 的每项序列化为 "data: <chunk>" 一行, 逐块透传 AI token.
//! 后端流中不含 sessionId, 新会话的 id 需调用方在首轮回复后经会话列表回查 (见 ChatView 的续聊启发式).
export async function streamMessage(options: StreamOptions): Promise<void> {
  const { sessionId, content, onChunk, signal } = options

  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ sessionId, content }),
    signal,
  })

  if (!res.ok) {
    let message = `流式请求失败 (HTTP ${res.status})`
    try {
      const payload = (await res.json()) as ApiResponse<unknown>
      message = payload.message || message
    } catch {
      //* 非 JSON 响应时保留默认文案
    }
    throw new ApiError(message, null, res.status)
  }

  const reader = res.body?.getReader()
  if (!reader) throw new ApiError('当前浏览器不支持流式读取', null, res.status)

  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '')
      buffer = buffer.slice(idx + 1)
      
      if (line.startsWith('data:')) onChunk(line.slice(5).replace(/^ /, ''))
    }
  }
}
