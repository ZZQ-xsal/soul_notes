//* 与后端 DTO 对齐的类型定义 (后端: kurvcygnus.soulnotes.dto / domain.*.dto).

/** 后端统一响应壳 {code, message, data}, 成功时 code === 0 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
}

export type UserRole = 'STUDENT' | 'COUNSELOR' | 'ADMIN'

/** AuthResponse: 登录/注册成功返回 */
export interface AuthData {
  token: string
  userId: string
  username: string
  role: UserRole
}

/** DiaryResponse.OfAnalysisResult: 日记的 AI 情感分析结果 (JSONB 解析) */
export interface AnalysisResult {
  positive: number
  negative: number
  anxiety: number
  weather: string
  warningLevel: string
  summary?: string | null
}

/** DiaryResponse */
export interface DiaryItem {
  id: number
  userId: string
  content: string | null
  audioUrl: string | null
  analysisResult: AnalysisResult | null
  createdAt: string
}

//! 后端 EmotionWeatherVo.weatherType 为枚举 (默认序列化为名称字符串);
//! 同时兼容对象形态, 以防后端 Jackson 策略调整后返回 {label, icon} 结构.
export type WeatherTypeRaw = string | { label?: string; icon?: string } | null

/** EmotionWeatherVo: 按日聚合的情绪天气数据 */
export interface WeatherDay {
  date: string
  weatherType: WeatherTypeRaw
  positiveAvg: number
  negativeAvg: number
  anxietyAvg: number
  entryCount: number
}

/** ChatSessionVo: 会话概览 (历史消息另经 GET /chat/sessions/{id}/messages 拉取) */
export interface ChatSessionVo {
  sessionId: string
  messageCount: number
  lastUpdateTime: string
  preview: string
}

/** ChatMessageVo */
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  timestamp?: string | null
}

/** 风险等级: 后端只持久化这两档 (NONE 与越界值在写入时即被丢弃) */
export type RiskLevel = 'YELLOW' | 'RED'

/** AssessmentVo: 预警队列条目 (REST 与 WS 推送共用同一形态) */
export interface AssessmentVo {
  id: string
  /** 已过 RevealPolicy 脱敏: 解锁时为完整 UUID, 掩码时为 8 位短码 (不可反查) */
  userId: string
  displayName: string
  riskLevel: RiskLevel
  summary: string
  /** 情绪标签: 契约里是字符串数组 (AiPromptConstants 的 canonical schema);
   *  后端 JsonNode 原样透传不做校验, 宽松 schema 下也可能是对象, 故保留联合类型. */
  tags: string[] | Record<string, unknown> | null
  sessionId: string
  createdAt: string
}

/** StatsSummary: 管理驾驶舱聚合数据 */
export interface StatsSummary {
  /** 等级 → 条数 (仅含出现过的等级) */
  byLevel: Record<string, number>
  /** 按日分组, 日期升序; 无记录的日期不出现 */
  byDay: { date: string; yellow: number; red: number }[]
  totalStudents: number
}

/** CrisisResource 返回的热线信息 */
export interface HotlineInfo {
  name: string
  primary: string
  backup: string
  message: string
  /** 校内心理咨询预约入口 (crisis.appointment.url); 机构未配置时为空串, 前端判空隐藏入口 */
  appointmentUrl: string
}

//! 后端 VoiceStatus 枚举序列化为名称字符串; 转录失败不回 5xx,
//! 而是 status=FAILED + message 透传原因 (离线安全网语义).
export type VoiceStatus = 'TRANSCRIBED' | 'FAILED'

/** VoiceUploadResponse (v1.1.0): 上传语音文件同步转录的契约载体 */
export interface VoiceUploadResponse {
  audioUrl: string
  fileId: string
  status: VoiceStatus
  /** 转录文本 (失败时为 null; 静音时为空串) */
  transcribedText: string | null
  /** 失败原因 (成功时为 null) */
  message: string | null
}
