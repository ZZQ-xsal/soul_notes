//* 单条对话气泡: 用户居右 (品牌色), 树洞居左 (卡片色 + 树洞头像).

interface Props {
  role: 'user' | 'assistant'
  content: string
  /** 流式输出中显示光标 */
  streaming?: boolean
}

export default function ChatBubble({ role, content, streaming = false }: Props) {
  if (role === 'user') {
    return (
      <div className="chat-row is-user">
        <div className="chat-bubble user-bubble">{content}</div>
      </div>
    )
  }
  return (
    <div className="chat-row is-assistant">
      <span className="chat-avatar" aria-hidden="true">
        <svg viewBox="0 0 64 64">
          <circle cx="32" cy="32" r="30" fill="var(--brand)" />
          <path d="M32 46C20 38 12 30 12 22a10 10 0 0 1 20-4 10 10 0 0 1 20 4c0 8-8 16-20 24z" fill="var(--brand-ink)" />
        </svg>
      </span>
      <div className="chat-bubble assistant-bubble">
        {content}
        {streaming && (
          <span className="stream-cursor" aria-label="树洞正在输入">
            ▍
          </span>
        )}
      </div>
    </div>
  )
}
