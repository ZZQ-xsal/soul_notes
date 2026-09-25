//* 树洞对话页: 会话列表 + SSE 流式对话 (失败降级非流式).

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { listSessions, sendMessage, streamMessage } from '../api/chat'
import { ApiError } from '../api/http'
import { toast } from '../utils/toast'
import { formatDateTime } from '../utils/format'
import type { ChatMessage, ChatSessionVo } from '../types'
import ChatBubble from '../components/chat/ChatBubble'

const GREETING: ChatMessage = {
  role: 'assistant',
  content: '你好, 我是心灵札记。这里很安全, 你的每一句话都会被认真倾听。今天想聊点什么?',
}

export default function ChatView() {
  const [sessions, setSessions] = useState<ChatSessionVo[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sessionNote, setSessionNote] = useState<string | null>(null)
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)

  const sortedSessions = useMemo(
    () => [...sessions].sort((a, b) => b.lastUpdateTime.localeCompare(a.lastUpdateTime)),
    [sessions],
  )

  const loadSessions = useCallback(async () => {
    try {
      setSessions(await listSessions())
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '加载会话失败', 'error')
    }
  }, [])

  useEffect(() => {
    void loadSessions()
  }, [loadSessions])

  //* 卸载时中止进行中的流式请求.
  useEffect(
    () => () => {
      abortRef.current?.abort()
    },
    [],
  )

  //* 新消息/流式增量时自动滚动到底部.
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  const openSession = (s: ChatSessionVo): void => {
    setActiveId(s.sessionId)
    setMessages([])
    //! 后端未提供会话历史消息接口, 仅能展示预览; 后续消息从当前会话继续.
    setSessionNote(`该会话共有 ${s.messageCount} 条历史消息, 最近一条: ${s.preview}`)
  }

  const startNewChat = (): void => {
    setActiveId(null)
    setMessages([])
    setSessionNote(null)
    setInput('')
  }

  //! 新会话的后端 SSE 流不返回 sessionId, 首轮回复后经会话列表回查最新会话实现续聊 (启发式).
  const resolveNewSession = async (): Promise<void> => {
    try {
      const list = await listSessions()
      setSessions(list)
      const newest = [...list].sort((a, b) => b.lastUpdateTime.localeCompare(a.lastUpdateTime))[0]
      if (newest) {
        setActiveId(newest.sessionId)
        setSessionNote(`已自动续接会话 (${newest.messageCount} 条消息)`)
      }
    } catch {
      //* 回查失败不阻塞对话, 下一条消息将另起新会话.
    }
  }

  const handleSend = async (): Promise<void> => {
    const content = input.trim()
    if (!content || streaming) return
    setInput('')
    setMessages((prev) => [...prev, { role: 'user', content }])
    setStreaming(true)

    const controller = new AbortController()
    abortRef.current = controller
    let partial = ''

    try {
      await streamMessage({
        sessionId: activeId,
        content,
        signal: controller.signal,
        onChunk: (chunk) => {
          partial += chunk
          setMessages((prev) => {
            const next = [...prev]
            const last = next[next.length - 1]
            if (last && last.role === 'assistant') next[next.length - 1] = { ...last, content: partial }
            else next.push({ role: 'assistant', content: partial })
            return next
          })
        },
      })
      if (!activeId) await resolveNewSession()
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      //* 流式失败且无任何内容时降级为非流式接口.
      if (!partial) {
        try {
          const reply = await sendMessage(activeId, content)
          setMessages((prev) => [...prev, reply])
          if (!activeId) await resolveNewSession()
        } catch (err2) {
          toast(err2 instanceof ApiError ? err2.message : '消息发送失败', 'error')
        }
      } else {
        //* 流式中断但有部分内容: 服务端已建会话, 新会话同样要回查 sessionId, 否则下一条消息另起会话.
        if (!activeId) void resolveNewSession()
        toast(err instanceof ApiError ? err.message : '流式中断, 已显示部分回复', 'warning')
      }
    } finally {
      setStreaming(false)
      abortRef.current = null
    }
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>): void => {
    //* Enter 发送, Shift+Enter 换行.
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      void handleSend()
    }
  }

  const lastIsStreaming = streaming && messages.length > 0 && messages[messages.length - 1].role === 'assistant'

  return (
    <div className="chat-view">
      <aside className="chat-sessions card" aria-label="会话列表">
        <div className="chat-sessions-head">
          <span className="chat-sessions-title">历史会话</span>
          <button type="button" className="btn sm" onClick={startNewChat}>
            + 新对话
          </button>
        </div>
        <div className="chat-sessions-list">
          {sortedSessions.length === 0 && <p className="chat-sessions-empty">暂无历史会话</p>}
          {sortedSessions.map((s) => (
            <button
              key={s.sessionId}
              type="button"
              className={`session-item${s.sessionId === activeId ? ' active' : ''}`}
              onClick={() => openSession(s)}
            >
              <span className="session-preview line-clamp-2">{s.preview}</span>
              <span className="session-meta tabular">
                {s.messageCount} 条 · {formatDateTime(s.lastUpdateTime)}
              </span>
            </button>
          ))}
        </div>
      </aside>

      <div className="chat-main card">
        <div className="chat-messages" ref={listRef} aria-live="polite">
          {sessionNote && <p className="chat-note">{sessionNote}</p>}
          {messages.length === 0 && !sessionNote && <ChatBubble role="assistant" content={GREETING.content} />}
          {messages.map((m, i) => (
            <ChatBubble
              key={`${m.role}-${i}`}
              role={m.role}
              content={m.content}
              streaming={lastIsStreaming && i === messages.length - 1}
            />
          ))}
        </div>
        <div className="chat-input-area">
          <textarea
            className="textarea chat-input"
            placeholder="和树洞说点什么… (Enter 发送, Shift+Enter 换行)"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={2}
            disabled={streaming}
          />
          <button type="button" className="btn chat-send" onClick={() => void handleSend()} disabled={!input.trim() || streaming}>
            {streaming ? '回复中…' : '发送'}
          </button>
        </div>
      </div>
    </div>
  )
}
