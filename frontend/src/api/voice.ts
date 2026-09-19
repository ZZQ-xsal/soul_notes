//* 语音辅助: <audio> 标签无法携带 Authorization 头, 需先 fetch 为 Blob 再播放.

import { api, ApiError, getToken } from './http'
import type { VoiceUploadResponse } from '../types'

/** 上传语音文件并同步转录 (multipart, field 名与后端 @RestForm("file") 一致).
 *  转录失败不回 5xx, 而是 status=FAILED + message, 由调用方决定回落策略. */
export function uploadVoice(file: Blob): Promise<VoiceUploadResponse> {
  const form = new FormData()
  form.append('file', file, 'recording.wav')
  return api<VoiceUploadResponse>('/voice/upload', { method: 'POST', formData: form })
}

/** 拉取受保护的语音文件并播放, 返回停止函数; onEnded 在播放自然结束时触发 */
export async function playAudioUrl(audioUrl: string, onEnded?: () => void): Promise<() => void> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(audioUrl, { headers })
  if (!res.ok) throw new ApiError(`语音加载失败 (HTTP ${res.status})`, null, res.status)

  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const audio = new Audio(url)
  audio.onended = () => {
    URL.revokeObjectURL(url)
    onEnded?.()
  }
  await audio.play()
  return () => {
    audio.pause()
    URL.revokeObjectURL(url)
  }
}
