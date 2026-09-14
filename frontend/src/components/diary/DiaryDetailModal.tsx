//* 日记详情: 完整内容 / 语音播放 / AI 情感分析 (天气、三档分值、预警提示与热线入口).

import { useEffect, useMemo, useRef, useState } from 'react'
import type { DiaryItem } from '../../types'
import { formatDateTime, formatScore } from '../../utils/format'
import { resolveWarning, resolveWeather } from '../../utils/weather'
import WeatherIcon from '../weather/WeatherIcon'
import { playAudioUrl } from '../../api/voice'
import { useAlert } from '../../context/AlertContext'
import { toast } from '../../utils/toast'

interface Props {
  diary: DiaryItem
  onClose: () => void
}

//* 分值条与图表系列色保持同一身份映射 (积极=槽位1, 消极=槽位2, 焦虑=槽位3).
const SCORE_ROWS = [
  { key: 'positive', label: '积极', fillCls: 'series-fill-1' },
  { key: 'negative', label: '消极', fillCls: 'series-fill-2' },
  { key: 'anxiety', label: '焦虑', fillCls: 'series-fill-3' },
] as const

export default function DiaryDetailModal({ diary, onClose }: Props) {
  const { hotline } = useAlert()
  const weather = useMemo(() => resolveWeather(diary.analysisResult?.weather), [diary.analysisResult])
  const warning = useMemo(() => resolveWarning(diary.analysisResult?.warningLevel), [diary.analysisResult])
  const [playing, setPlaying] = useState(false)
  const stopRef = useRef<(() => void) | null>(null)

  //* 关闭弹窗时停止语音播放.
  useEffect(
    () => () => {
      stopRef.current?.()
    },
    [],
  )

  const togglePlay = async (): Promise<void> => {
    if (!diary.audioUrl) return
    if (playing) {
      stopRef.current?.()
      setPlaying(false)
      return
    }
    try {
      const stop = await playAudioUrl(diary.audioUrl, () => setPlaying(false))
      stopRef.current = stop
      setPlaying(true)
    } catch (err) {
      toast(err instanceof Error ? err.message : '语音播放失败', 'error')
    }
  }

  const analysis = diary.analysisResult

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel" role="dialog" aria-modal="true" aria-label="日记详情" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="modal-close" onClick={onClose} aria-label="关闭">
          ×
        </button>
        <time className="detail-time">{formatDateTime(diary.createdAt)}</time>

        {diary.content ? (
          <p className="detail-content">{diary.content}</p>
        ) : (
          <p className="detail-content detail-content-muted">这是一篇语音日记</p>
        )}

        {diary.audioUrl && (
          <button type="button" className="btn secondary sm detail-play" onClick={togglePlay}>
            {playing ? '⏸ 停止播放' : '▶ 播放语音'}
          </button>
        )}

        <section className="detail-analysis" aria-label="AI 情感分析">
          <h3>情绪分析</h3>
          {analysis ? (
            <>
              <div className="analysis-weather">
                {weather && <WeatherIcon type={weather.icon} size={44} />}
                <div>
                  <strong>{weather?.label ?? analysis.weather}</strong>
                  {weather?.tip && <p>{weather.tip}</p>}
                </div>
              </div>
              <div className="analysis-meters">
                {SCORE_ROWS.map((row) => (
                  <div key={row.key} className="meter-row">
                    <span className="meter-label">{row.label}</span>
                    <span className="meter-track">
                      <span className={`meter-fill ${row.fillCls}`} style={{ width: `${Math.round(analysis[row.key] * 100)}%` }} />
                    </span>
                    <span className="meter-value tabular">{formatScore(analysis[row.key])}</span>
                  </div>
                ))}
              </div>
              {analysis.summary && <p className="analysis-summary">{analysis.summary}</p>}
              {warning.tone === 'critical' && (
                <div className="analysis-alert is-critical" role="alert">
                  <span className="analysis-alert-icon" aria-hidden="true">
                    ⚠
                  </span>
                  <p>检测到高危信号。请立即联系心理中心或拨打热线, 你不需要独自面对。</p>
                  <a className="btn danger sm" href={`tel:${hotline.primary}`}>
                    {hotline.name}: {hotline.primary}
                  </a>
                </div>
              )}
              {warning.tone === 'warning' && (
                <div className="analysis-alert is-warning">
                  <span className="analysis-alert-icon" aria-hidden="true">
                    ◐
                  </span>
                  <p>情绪需要被关注。建议多与信任的人倾诉, 或来树洞聊聊。</p>
                </div>
              )}
            </>
          ) : (
            <p className="analysis-missing">AI 分析暂不可用 (服务端 AI 未接入时自动降级), 欢迎随时来树洞聊聊。</p>
          )}
        </section>
      </div>
    </div>
  )
}
