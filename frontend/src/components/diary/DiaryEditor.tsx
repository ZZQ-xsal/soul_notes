//* 写日记编辑器: 文字 / 语音两种来源.
//* 语音经 MediaRecorder 录音或本地文件读取, 转码为 16kHz 单声道 WAV 上传 /voice/upload 同步转写;
//* 转写文本回填可编辑, 提交时与音频一并进入 DiaryCreateRequest (VOICE 管线), 失败回落纯语音提交.

import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { createDiary } from '../../api/diary'
import { ApiError } from '../../api/http'
import { uploadVoice } from '../../api/voice'
import { useAuth } from '../../context/AuthContext'
import { blobToWav16kMono } from '../../utils/audio'
import { formatDateTime } from '../../utils/format'
import type { DiaryItem } from '../../types'

interface Props {
  onClose: () => void
  onCreated: (diary: DiaryItem) => void
}

//* 未提交草稿: 关弹窗/刷新/切页都不丢, 下次打开编辑器自动回填.
//* 只存文字正文 — 录音原始体积远超 localStorage 配额 (5MB 级), 不存.
const DRAFT_KEY_PREFIX = 'soul.diary-draft.v1'

interface DiaryDraft {
  content: string
  savedAt: string
}

function readDraft(key: string): DiaryDraft | null {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<DiaryDraft>
    //! 缺正文或时间戳的残留值一律当无草稿 (旧格式 / 手改过 localStorage).
    if (!parsed.content?.trim() || !parsed.savedAt) return null
    return { content: parsed.content, savedAt: parsed.savedAt }
  } catch {
    return null
  }
}

function writeDraft(key: string, content: string): void {
  //* 正文清空 = 丢弃草稿 (用户把文本删干净了, 就是不要了).
  if (!content.trim()) {
    localStorage.removeItem(key)
    return
  }
  localStorage.setItem(key, JSON.stringify({ content, savedAt: new Date().toISOString() }))
}

//* 输入停顿多久落盘: 每次按键都写会拖慢输入, 停顿写入 + 卸载兜底 (见下) 已覆盖丢字场景.
const DRAFT_DEBOUNCE_MS = 800

//* 与后端 voice.storage.max-size 默认值 (10MB) 对齐.
const MAX_AUDIO_BYTES = 10 * 1024 * 1024
//* 单次录音上限 3 分钟: WAV 16kHz 单声道 PCM16 为 32KB/s, 3 分钟 ≈ 5.8MB, 距 10MB 上限留有余量.
const MAX_RECORD_SECONDS = 180

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
  const { user } = useAuth()
  const [mode, setMode] = useState<'TEXT' | 'VOICE'>('TEXT')
  //* 草稿键按用户分: 同一浏览器换账号登录时, 不会读到上一个人留下的草稿.
  const draftKey = `${DRAFT_KEY_PREFIX}.${user?.userId ?? 'anonymous'}`
  const [initialDraft] = useState(() => readDraft(draftKey))
  const [content, setContent] = useState(initialDraft?.content ?? '')
  const [draftRestored, setDraftRestored] = useState(initialDraft !== null)
  const [recording, setRecording] = useState(false)
  const [recordSeconds, setRecordSeconds] = useState(0)
  const [audioBlob, setAudioBlob] = useState<Blob | null>(null)
  const [audioBase64, setAudioBase64] = useState<string | null>(null)
  const [transcribing, setTranscribing] = useState(false)
  const [transcript, setTranscript] = useState('')
  const [transcriptFailed, setTranscriptFailed] = useState('')
  const [wavBase64, setWavBase64] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [micAvailable, setMicAvailable] = useState(true)

  const mediaRef = useRef<MediaRecorder | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const timerRef = useRef<number | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const previewUrlRef = useRef<string | null>(null)
  //* 转写请求序号: 重录/丢弃后, 在途旧请求的回包不再写状态 (竞态防护).
  const transcribeSeqRef = useRef(0)
  //* 最新正文: 供卸载时的兜底落盘读取 (定时器已被 cleanup 取消, 拿不到 state 的最新值).
  const contentRef = useRef(content)
  contentRef.current = content

  //* 卸载清理: 停计时器、释放麦克风轨道与预览 URL.
  useEffect(
    () => () => {
      if (timerRef.current !== null) window.clearInterval(timerRef.current)
      streamRef.current?.getTracks().forEach((t) => t.stop())
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current)
    },
    [],
  )

  //* 草稿落盘: 输入停顿后写入 (打字过程中不写).
  useEffect(() => {
    const timer = window.setTimeout(() => writeDraft(draftKey, content), DRAFT_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [draftKey, content])

  //* 卸载兜底: 关弹窗/切页时, 最后不到一个防抖周期的输入也要落盘.
  //* 提交成功时 contentRef 已被清空, 这里顺手把草稿删掉, 不会复活已提交的正文.
  useEffect(() => () => writeDraft(draftKey, contentRef.current), [draftKey])

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

  //* 转码 + 上传同步转写; 结果非阻塞: 失败仅提示, 提交时回落纯语音日记.
  const startTranscribe = async (blob: Blob): Promise<void> => {
    const seq = ++transcribeSeqRef.current
    setTranscribing(true)
    setTranscript('')
    setTranscriptFailed('')
    setWavBase64(null)
    try {
      const wav = await blobToWav16kMono(blob)
      if (seq !== transcribeSeqRef.current) return //* 已被新音频替换, 丢弃本次结果
      if (wav.size > MAX_AUDIO_BYTES) {
        setTranscriptFailed('音频时长过长, 无法转写 (上限约 5 分钟); 仍可提交为语音日记')
        return
      }
      setWavBase64(await blobToBase64(wav))
      const resp = await uploadVoice(wav)
      if (seq !== transcribeSeqRef.current) return
      if (resp.status === 'TRANSCRIBED') {
        if (resp.transcribedText) {
          setTranscript(resp.transcribedText)
        } else {
          //* 静音为空串属合法成功, 按降级提示处理 (可补录文字或直接提交语音).
          setTranscriptFailed('未识别到语音内容 (可能为静音), 可直接提交语音日记')
        }
      } else {
        setTranscriptFailed(resp.message ?? '语音转写失败, 将按纯语音日记提交')
      }
    } catch (err) {
      if (seq !== transcribeSeqRef.current) return
      setTranscriptFailed(err instanceof ApiError ? err.message : '语音转写失败, 将按纯语音日记提交')
    } finally {
      if (seq === transcribeSeqRef.current) setTranscribing(false)
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
        void startTranscribe(blob)
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
    transcribeSeqRef.current++ //* 使在途转写请求的回包失效
    setTranscribing(false)
    setTranscript('')
    setTranscriptFailed('')
    setWavBase64(null)
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
      void startTranscribe(file)
    } catch {
      setError('文件读取失败')
    }
  }

  const canSubmit = mode === 'TEXT' ? content.trim().length > 0 : audioBase64 !== null && !transcribing

  const handleSubmit = async (): Promise<void> => {
    if (!canSubmit || submitting) return
    setSubmitting(true)
    setError('')
    try {
      const diary =
        mode === 'TEXT'
          ? await createDiary({ content: content.trim(), audioData: null, sourceType: 'TEXT' })
          : await createDiary({
              //* 有转写文本时一并提交 (AI 分析基于文字); 用户清空文本则回落纯语音日记.
              content: transcript.trim() || null,
              //* 优先提交统一格式的 WAV; 转码本身失败时才用原始音频.
              audioData: wavBase64 ?? audioBase64,
              sourceType: 'VOICE',
            })
      //* 提交成功: 清空正文引用 (弹窗随后卸载, 卸载兜底据此删除草稿), 避免已提交内容复活成草稿.
      contentRef.current = ''
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
              onChange={(e) => {
                setContent(e.target.value)
                setDraftRestored(false)
              }}
              maxLength={2000}
              rows={7}
            />
            <div className="editor-hint">{content.length} / 2000</div>
            {draftRestored && initialDraft && (
              <p className="editor-hint" role="status">
                已恢复上次未提交的草稿 ({formatDateTime(initialDraft.savedAt)}) · 清空文本即丢弃
              </p>
            )}
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
                {transcribing && (
                  <p className="editor-hint" role="status" aria-live="polite">
                    正在转写语音…
                  </p>
                )}
                {transcript.length > 0 && (
                  <>
                    <textarea
                      className="textarea"
                      rows={4}
                      value={transcript}
                      onChange={(e) => setTranscript(e.target.value)}
                      maxLength={2000}
                      aria-label="语音转写文本"
                      placeholder="转写文本可修改后保存"
                    />
                    <div className="editor-hint">{transcript.length} / 2000</div>
                  </>
                )}
                {transcriptFailed && <p className="form-warn">{transcriptFailed}</p>}
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
            <p className="editor-hint">语音将转码为 WAV 上传并本地转写 (文本可修改); 单次录音最长 3 分钟, 文件不超过 10MB。</p>
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
