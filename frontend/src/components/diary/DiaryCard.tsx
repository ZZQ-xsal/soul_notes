//* 日记卡片: 内容预览 + 天气/预警徽章 + AI 反馈 + 语音播放.

import { useMemo, useState } from 'react'
import type { MouseEvent } from 'react'
import type { DiaryItem } from '../../types'
import { formatDateTime } from '../../utils/format'
import { resolveWarning, resolveWeather } from '../../utils/weather'
import WeatherIcon from '../weather/WeatherIcon'
import { playAudioUrl } from '../../api/voice'
import { toast } from '../../utils/toast'

interface Props {
  diary: DiaryItem
  onOpen: (diary: DiaryItem) => void
  onDelete: (diary: DiaryItem) => void
}

export default function DiaryCard({ diary, onOpen, onDelete }: Props) {
  const weather = useMemo(() => resolveWeather(diary.analysisResult?.weather), [diary.analysisResult])
  const warning = useMemo(() => resolveWarning(diary.analysisResult?.warningLevel), [diary.analysisResult])
  const [playing, setPlaying] = useState(false)

  const handlePlay = async (e: MouseEvent): Promise<void> => {
    e.stopPropagation()
    if (!diary.audioUrl || playing) return
    try {
      setPlaying(true)
      await playAudioUrl(diary.audioUrl, () => setPlaying(false))
    } catch (err) {
      setPlaying(false)
      toast(err instanceof Error ? err.message : '语音播放失败', 'error')
    }
  }

  const handleDelete = (e: MouseEvent): void => {
    e.stopPropagation()
    if (window.confirm('确定删除这篇日记吗? 删除后无法恢复。')) onDelete(diary)
  }

  const analysis = diary.analysisResult

  return (
    <article className="diary-card card" onClick={() => onOpen(diary)}>
      <div className="diary-card-head">
        <time className="diary-time">{formatDateTime(diary.createdAt)}</time>
        <div className="diary-badges">
          {weather && (
            <span className="badge muted">
              <WeatherIcon type={weather.icon} size={14} />
              {weather.label}
            </span>
          )}
          {/* 学生端不展示 "需关注" 徽章, 只保留高危预警 */}
          {analysis && warning.tone === 'critical' && (
            <span className="badge critical" role="status">
              <span aria-hidden="true">⚠</span>
              {warning.label}
            </span>
          )}
        </div>
      </div>
      {diary.content ? (
        <p className="diary-preview line-clamp-2">{diary.content}</p>
      ) : (
        <p className="diary-preview diary-preview-muted">语音日记</p>
      )}
      <div className="diary-card-foot">
        <div className="diary-scores">
          {/* AI 对这篇日记的反馈 (后端 summary: 鼓励/建议/喝彩, 非医学化); 加"心灵札记："前缀提示出自 AI; 不展示情绪分值 */}
          {analysis?.summary ? (
            <p className="diary-ai-feedback">
              <span className="ai-feedback-tag">心灵札记：</span>
              {analysis.summary}
            </p>
          ) : (
            <span className="score-chip score-chip-muted">
              {analysis ? '本篇暂无 AI 反馈' : 'AI 分析暂不可用'}
            </span>
          )}
        </div>
        <div className="diary-actions">
          {diary.audioUrl && (
            <button
              type="button"
              className="btn ghost sm"
              onClick={handlePlay}
              aria-label={playing ? '停止播放语音' : '播放语音'}
            >
              {playing ? '⏸ 停止' : '▶ 播放语音'}
            </button>
          )}
          <button type="button" className="btn ghost sm diary-delete" onClick={handleDelete}>
            删除
          </button>
        </div>
      </div>
    </article>
  )
}
