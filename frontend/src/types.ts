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

/** ChatSessionVo: 会话概览 (后端不提供历史消息拉取接口) */
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

/** CrisisResource 返回的热线信息 */
export interface HotlineInfo {
  name: string
  primary: string
  backup: string
  message: string
}
