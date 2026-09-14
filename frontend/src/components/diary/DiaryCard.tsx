//* 日记卡片: 内容预览 + 天气/预警徽章 + 情绪分值 + 语音播放.

import { useMemo, useState } from 'react'
import type { MouseEvent } from 'react'
import type { DiaryItem } from '../../types'
import { formatDateTime, formatScore } from '../../utils/format'
import { resolveWarning, resolveWeather } from '../../utils/weather'
import WeatherIcon from '../weather/WeatherIcon'
import { playAudioUrl } from '../../api/voice'
import { toast } from '../../utils/toast'

interface Props {
  diary: DiaryItem
  onOpen: (diary: DiaryItem) => void
  onDelete: (diary: DiaryItem) => void
}

//* 分值行与图表系列色保持同一身份映射: 积极=槽位1, 消极=槽位2, 焦虑=槽位3.
const SCORE_ROWS = [
  { key: 'positive', label: '积极', cls: 'series-fill-1' },
  { key: 'negative', label: '消极', cls: 'series-fill-2' },
  { key: 'anxiety', label: '焦虑', cls: 'series-fill-3' },
] as const

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
          {analysis && warning.tone !== 'none' && (
            <span className={`badge ${warning.tone}`} role="status">
              <span aria-hidden="true">{warning.tone === 'critical' ? '⚠' : '◐'}</span>
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
          {analysis &&
            SCORE_ROWS.map((row) => (
              <span key={row.key} className="score-chip">
                <span className={`score-dot ${row.cls}`} aria-hidden="true" />
                {row.label} <span className="tabular">{formatScore(analysis[row.key])}</span>
              </span>
            ))}
          {!analysis && <span className="score-chip score-chip-muted">AI 分析暂不可用</span>}
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
