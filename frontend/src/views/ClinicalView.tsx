//* 咨询员工作台: 预警队列 (筛选 + 实时推送) / 聚合统计 / 条目详情.
//* 隐私: 条目身份已由后端 RevealPolicy 脱敏, 前端只做展示, 不拼接也不推断真实身份.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getStatsSummary, listAssessments } from '../api/clinical'
import { ApiError } from '../api/http'
import { connectClinicalFeed } from '../api/ws'
import { useAuth } from '../context/AuthContext'
import { toast } from '../utils/toast'
import { formatDateTime } from '../utils/format'
import { resolveWarning } from '../utils/weather'
import AssessmentDetailModal from '../components/clinical/AssessmentDetailModal'
import type { AssessmentVo, RiskLevel, StatsSummary } from '../types'

const WARNING_META = resolveWarning('YELLOW')
const CRITICAL_META = resolveWarning('RED')

const LEVEL_OPTIONS: { value: RiskLevel | ''; label: string }[] = [
  { value: '', label: '全部等级' },
  { value: 'YELLOW', label: WARNING_META.label },
  { value: 'RED', label: CRITICAL_META.label },
]

const DAY_OPTIONS: { value: number; label: string }[] = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 0, label: '不限时间' },
]

export default function ClinicalView() {
  const { token, user } = useAuth()
  const [level, setLevel] = useState<RiskLevel | ''>('')
  const [days, setDays] = useState(7)
  const [items, setItems] = useState<AssessmentVo[]>([])
  const [stats, setStats] = useState<StatsSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [live, setLive] = useState(false)
  const [detail, setDetail] = useState<AssessmentVo | null>(null)

  //* 实时回调读最新筛选值, 避免把 level/days 塞进 WS 依赖 (否则每换一次筛选就重连).
  const filterRef = useRef({ level, days })
  filterRef.current = { level, days }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [list, summary] = await Promise.all([listAssessments({ level, days }), getStatsSummary(days)])
      setItems(list)
      setStats(summary)
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '加载预警队列失败', 'error')
    } finally {
      setLoading(false)
    }
  }, [level, days])

  useEffect(() => {
    void load()
  }, [load])

  //* 实时推送: 命中当前筛选的条目插入队首; 统计数字一并重取, 免得列表与概览对不上.
  useEffect(() => {
    if (!token) return
    return connectClinicalFeed({
      token,
      onStatus: setLive,
      onAssessment: (a) => {
        const { level: levelNow, days: daysNow } = filterRef.current
        toast(`新预警: ${a.displayName} · ${resolveWarning(a.riskLevel).label}`, 'warning')
        if (!levelNow || a.riskLevel === levelNow) {
          setItems((prev) => (prev.some((x) => x.id === a.id) ? prev : [a, ...prev]))
        }
        void getStatsSummary(daysNow)
          .then(setStats)
          .catch(() => {
            //* 统计刷新失败不影响队列, 下次筛选或刷新时自愈.
          })
      },
    })
  }, [token])

  //* 两条系列共用同一刻度: 取全窗口单日最大值, 否则黄红两根条不可比.
  const maxDay = useMemo(() => Math.max(1, ...(stats?.byDay ?? []).flatMap((d) => [d.yellow, d.red])), [stats])

  return (
    <div className="clin-view">
      <header className="clin-head">
        <div>
          <h1 className="clin-title">咨询员工作台</h1>
          <p className="clin-sub">
            {user?.username} ·{' '}
            <span className={live ? 'clin-live on' : 'clin-live'}>{live ? '实时推送已连接' : '实时推送断开, 重连中'}</span>
          </p>
        </div>
        <button type="button" className="btn ghost sm" onClick={() => void load()} disabled={loading}>
          {loading ? '刷新中…' : '刷新'}
        </button>
      </header>

      <section className="clin-stats" aria-label="预警概览">
        <div className="card clin-tile">
          <span className="clin-tile-label">涉及学生</span>
          <strong className="clin-tile-value tabular">{stats?.totalStudents ?? 0}</strong>
        </div>
        <div className="card clin-tile is-warning">
          <span className="clin-tile-label">{WARNING_META.label}</span>
          <strong className="clin-tile-value tabular">{stats?.byLevel?.YELLOW ?? 0}</strong>
        </div>
        <div className="card clin-tile is-critical">
          <span className="clin-tile-label">{CRITICAL_META.label}</span>
          <strong className="clin-tile-value tabular">{stats?.byLevel?.RED ?? 0}</strong>
        </div>
      </section>

      <section className="card clin-trend" aria-label="按日趋势">
        <div className="clin-trend-head">
          <h2 className="clin-section-title">按日趋势</h2>
          <div className="clin-legend">
            <span className="clin-legend-item">
              <i className="clin-swatch is-warning" aria-hidden="true" />
              {WARNING_META.label}
            </span>
            <span className="clin-legend-item">
              <i className="clin-swatch is-critical" aria-hidden="true" />
              {CRITICAL_META.label}
            </span>
          </div>
        </div>
        {!stats || stats.byDay.length === 0 ? (
          <p className="clin-empty">所选时间窗内暂无预警记录。</p>
        ) : (
          //! 颜色不是唯一编码: 每行都带数字, 行尾 title 给出完整口径 (黄色在浅底上对比度不足, 靠文字兜底).
          <div className="clin-days">
            {stats.byDay.map((d) => (
              <div
                className="clin-day"
                key={d.date}
                title={`${d.date}: ${WARNING_META.label} ${d.yellow} 条, ${CRITICAL_META.label} ${d.red} 条`}
              >
                <span className="clin-day-label tabular">{d.date.slice(5)}</span>
                <span className="clin-bars">
                  <span className="clin-bar-track">
                    <span className="clin-bar is-warning" style={{ width: `${(d.yellow / maxDay) * 100}%` }} />
                  </span>
                  <span className="clin-bar-track">
                    <span className="clin-bar is-critical" style={{ width: `${(d.red / maxDay) * 100}%` }} />
                  </span>
                </span>
                <span className="clin-day-count tabular">
                  {d.yellow} / {d.red}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="card clin-queue" aria-label="预警队列">
        <div className="clin-filters">
          <div className="clin-chips" role="group" aria-label="等级筛选">
            {LEVEL_OPTIONS.map((o) => (
              <button
                key={o.value || 'all'}
                type="button"
                className={`clin-chip${level === o.value ? ' active' : ''}`}
                onClick={() => setLevel(o.value)}
              >
                {o.label}
              </button>
            ))}
          </div>
          <div className="clin-chips" role="group" aria-label="时间窗筛选">
            {DAY_OPTIONS.map((o) => (
              <button
                key={o.value}
                type="button"
                className={`clin-chip${days === o.value ? ' active' : ''}`}
                onClick={() => setDays(o.value)}
              >
                {o.label}
              </button>
            ))}
          </div>
        </div>

        {loading && items.length === 0 ? (
          <p className="clin-empty">加载中…</p>
        ) : items.length === 0 ? (
          <p className="clin-empty">当前筛选下没有预警记录。</p>
        ) : (
          <ul className="clin-list">
            {items.map((a) => {
              const w = resolveWarning(a.riskLevel)
              return (
                <li key={a.id}>
                  <button type="button" className="clin-item" onClick={() => setDetail(a)}>
                    <span className={`clin-badge is-${w.tone}`}>{w.label}</span>
                    <span className="clin-item-main">
                      <span className="clin-item-name">{a.displayName}</span>
                      <span className="clin-item-summary line-clamp-2">{a.summary}</span>
                    </span>
                    <time className="clin-item-time tabular">{formatDateTime(a.createdAt)}</time>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      {detail && <AssessmentDetailModal assessment={detail} onClose={() => setDetail(null)} />}
    </div>
  )
}
