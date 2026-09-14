//* 写日记编辑器: 文字 / 语音两种来源.
//* 语音经 MediaRecorder 录音或本地文件读取为 Base64, 进入 DiaryCreateRequest.audioData (VOICE 管线).

import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { createDiary } from '../../api/diary'
import { ApiError } from '../../api/http'
import type { DiaryItem } from '../../types'

interface Props {
  onClose: () => void
  onCreated: (diary: DiaryItem) => void
}

//* 与后端 voice.storage.max-size 默认值 (10MB) 对齐.
const MAX_AUDIO_BYTES = 10 * 1024 * 1024
//* 单次录音上限 5 分钟, 避免 Base64 体积失控.
const MAX_RECORD_SECONDS = 300

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = String(reader.result ?? '')
      resolve(result.slice(result.indexOf(',') + 1)) //* 去掉 data URL 前缀
    }
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsDataURL(blob)
  })
}

function formatSeconds(total: number): string {
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

export default function DiaryEditor({ onClose, onCreated }: Props) {
  const [mode, setMode] = useState<'TEXT' | 'VOICE'>('TEXT')
  const [content, setContent] = useState('')
  const [recording, setRecording] = useState(false)
  const [recordSeconds, setRecordSeconds] = useState(0)
  const [audioBlob, setAudioBlob] = useState<Blob | null>(null)
  const [audioBase64, setAudioBase64] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [micAvailable, setMicAvailable] = useState(true)

  const mediaRef = useRef<MediaRecorder | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const timerRef = useRef<number | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const previewUrlRef = useRef<string | null>(null)

  //* 卸载清理: 停计时器、释放麦克风轨道与预览 URL.
  useEffect(
    () => () => {
      if (timerRef.current !== null) window.clearInterval(timerRef.current)
      streamRef.current?.getTracks().forEach((t) => t.stop())
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current)
    },
    [],
  )

  //* 录音到时自动停止 (副作用放在渲染后, 不在 setState 更新器内执行).
  useEffect(() => {
    if (recordSeconds >= MAX_RECORD_SECONDS && mediaRef.current?.state === 'recording') {
      mediaRef.current.stop()
      if (timerRef.current !== null) window.clearInterval(timerRef.current)
      setRecording(false)
    }
  }, [recordSeconds])

  const clearTimer = (): void => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current)
      timerRef.current = null
    }
  }

  const startRecording = async (): Promise<void> => {
    setError('')
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setMicAvailable(false)
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      streamRef.current = stream
      const mime = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : ''
      const recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream)
      mediaRef.current = recorder
      chunksRef.current = []
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data)
      }
      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop())
        streamRef.current = null
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || 'audio/webm' })
        setAudioBlob(blob)
        if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current)
        previewUrlRef.current = URL.createObjectURL(blob)
        setAudioBase64(await blobToBase64(blob))
      }
      recorder.start()
      setRecording(true)
      setRecordSeconds(0)
      timerRef.current = window.setInterval(() => setRecordSeconds((s) => s + 1), 1000)
    } catch {
      setError('无法访问麦克风, 请检查浏览器权限设置')
    }
  }

  const stopRecording = (): void => {
    if (mediaRef.current?.state === 'recording') mediaRef.current.stop()
    clearTimer()
    setRecording(false)
  }

  const discardAudio = (): void => {
    clearTimer()
    setAudioBlob(null)
    setAudioBase64(null)
    if (previewUrlRef.current) {
      URL.revokeObjectURL(previewUrlRef.current)
      previewUrlRef.current = null
    }
  }

  const handleFilePick = async (e: ChangeEvent<HTMLInputElement>): Promise<void> => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setError('')
    if (file.size > MAX_AUDIO_BYTES) {
      setError('音频文件不能超过 10MB')
      return
    }
    try {
      discardAudio()
      setAudioBlob(file)
      previewUrlRef.current = URL.createObjectURL(file)
      setAudioBase64(await blobToBase64(file))
    } catch {
      setError('文件读取失败')
    }
  }

  const canSubmit = mode === 'TEXT' ? content.trim().length > 0 : audioBase64 !== null

  const handleSubmit = async (): Promise<void> => {
    if (!canSubmit || submitting) return
    setSubmitting(true)
    setError('')
    try {
      const diary =
        mode === 'TEXT'
          ? await createDiary({ content: content.trim(), audioData: null, sourceType: 'TEXT' })
          : await createDiary({ content: null, audioData: audioBase64, sourceType: 'VOICE' })
      onCreated(diary)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '提交失败, 请稍后再试')
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel" role="dialog" aria-modal="true" aria-label="写日记" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="modal-close" onClick={onClose} aria-label="关闭">
          ×
        </button>
        <h2 className="editor-title">记录此刻的心情</h2>
        <div className="editor-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'TEXT'}
            className={`editor-tab${mode === 'TEXT' ? ' active' : ''}`}
            onClick={() => setMode('TEXT')}
          >
            文字
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'VOICE'}
            className={`editor-tab${mode === 'VOICE' ? ' active' : ''}`}
            onClick={() => setMode('VOICE')}
          >
            语音
          </button>
        </div>

        {mode === 'TEXT' ? (
          <div className="editor-pane">
            <textarea
              className="textarea"
              placeholder="今天发生了什么? 你的感受如何? 这里是一个安全的树洞…"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              maxLength={2000}
              rows={7}
            />
            <div className="editor-hint">{content.length} / 2000</div>
          </div>
        ) : (
          <div className="editor-pane">
            {!micAvailable && (
              <p className="form-error">当前浏览器不支持录音, 你可以选择下方的音频文件。</p>
            )}
            {recording ? (
              <div className="recorder-box is-recording" role="status" aria-live="polite">
                <span className="rec-dot" aria-hidden="true" />
                <span className="rec-time tabular">{formatSeconds(recordSeconds)}</span>
                <button type="button" className="btn danger sm" onClick={stopRecording}>
                  停止录音
                </button>
              </div>
            ) : audioBlob ? (
              <div className="recorder-box">
                <audio controls src={previewUrlRef.current ?? undefined} className="audio-preview" />
                <div className="recorder-actions">
                  <button type="button" className="btn secondary sm" onClick={discardAudio}>
                    重新录制
                  </button>
                  <label className="btn secondary sm file-pick-label">
                    选择音频文件
                    <input type="file" accept="audio/*" hidden onChange={handleFilePick} />
                  </label>
                </div>
              </div>
            ) : (
              <div className="recorder-box">
                <button type="button" className="btn" onClick={startRecording} disabled={!micAvailable}>
                  ● 开始录音
                </button>
                <label className="btn secondary file-pick-label">
                  选择音频文件
                  <input type="file" accept="audio/*" hidden onChange={handleFilePick} />
                </label>
              </div>
            )}
            <p className="editor-hint">语音将上传并由 AI 转写与分析; 单次录音最长 5 分钟, 文件不超过 10MB。</p>
          </div>
        )}

        {error && <p className="form-error">{error}</p>}

        <div className="editor-foot">
          <button type="button" className="btn secondary" onClick={onClose}>
            取消
          </button>
          <button type="button" className="btn" onClick={handleSubmit} disabled={!canSubmit || submitting}>
            {submitting ? '提交中…' : '保存日记'}
          </button>
        </div>
      </div>
    </div>
  )
}
