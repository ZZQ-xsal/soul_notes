//* 情绪趋势折线图 (手写 SVG, 零第三方图表依赖).
//* 遵循 dataviz 规范: 2px 折线/8px 端点标记带表面描边环/发丝网格线/悬浮十字线+统一提示框/键盘可达.
//* 系列色使用校验通过的分类槽位 1-3 (见 main.css 的 --series-N), 图例 + 端点标签 + 提示框 + 表格视图多重编码.

import { useMemo, useState } from 'react'
import type { KeyboardEvent, MouseEvent } from 'react'
import { formatDecimal } from '../../utils/format'

export interface TrendSeries {
  name: string
  values: number[]
}

interface Props {
  labels: string[]
  series: TrendSeries[]
  yMin?: number
  yMax?: number
  /** 外部聚焦某一天 (天气日卡点击联动), null 表示不聚焦 */
  focusIndex?: number | null
}

//* 固定视口, 随容器等比缩放, 文本同步缩放.
const W = 720
const H = 320
const PAD_L = 40
const PAD_R = 64
const PAD_T = 16
const PAD_B = 30

//* 端点标签最小纵向间距: 低于此值整体隐藏, 交由提示框与表格承载.
const END_LABEL_MIN_GAP = 14

export default function TrendChart({ labels, series, yMin = 0, yMax = 1, focusIndex = null }: Props) {
  const n = labels.length
  const plotW = W - PAD_L - PAD_R
  const plotH = H - PAD_T - PAD_B
  const [hover, setHover] = useState<number | null>(null)

  const xAt = (i: number): number => (n <= 1 ? PAD_L + plotW / 2 : PAD_L + (i / (n - 1)) * plotW)
  const yAt = (v: number): number => PAD_T + (1 - (v - yMin) / (yMax - yMin)) * plotH

  //* Y 轴刻度: 均匀 5 档, 整数不显示小数位.
  const ticks = useMemo(() => Array.from({ length: 5 }, (_, k) => yMin + ((yMax - yMin) * k) / 4), [yMin, yMax])

  //* X 轴标签稀疏化, 避免拥挤 (最多约 8 个).
  const labelStep = Math.max(1, Math.ceil(n / 8))

  //* 端点标签碰撞检测: 任一相邻末点标签纵向间距不足时整体隐藏.
  const showEndLabels = useMemo(() => {
    if (series.length < 2) return true
    const ends = series.map((s) => yAt(s.values[s.values.length - 1])).sort((a, b) => a - b)
    return ends.every((v, i) => i === 0 || v - ends[i - 1] >= END_LABEL_MIN_GAP)
  }, [series, yMin, yMax]) // eslint-disable-line react-hooks/exhaustive-deps -- yAt 由 yMin/yMax 派生, 已覆盖

  const rawActive = hover ?? focusIndex
  const active = rawActive !== null && rawActive >= 0 && rawActive < n ? rawActive : null

  const handleMouseMove = (e: MouseEvent<SVGSVGElement>): void => {
    if (n === 0) return
    const rect = e.currentTarget.getBoundingClientRect()
    const px = ((e.clientX - rect.left) / rect.width) * W
    const i = Math.round(((px - PAD_L) / plotW) * (n - 1))
    setHover(Math.max(0, Math.min(n - 1, i)))
  }

  const handleMouseLeave = (): void => setHover(null)

  //* 键盘可达: 左右方向键移动十字线, 与悬浮获得相同读数.
  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>): void => {
    if (n === 0) return
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const base = active ?? Math.floor(n / 2)
    setHover(e.key === 'ArrowLeft' ? Math.max(0, base - 1) : Math.min(n - 1, base + 1))
  }

  //* 提示框位置: 偏右时翻转到十字线左侧, 避免溢出容器.
  const tooltipLeftPct = active !== null ? (xAt(active) / W) * 100 : 0

  return (
    <div
      className="trend-chart"
      tabIndex={0}
      onKeyDown={handleKeyDown}
      aria-label={`情绪趋势图: 共 ${n} 天, 纵轴为情绪分值 0 到 1, 详细数值见下方数据表`}
    >
      <div className="trend-chart-legend">
        {series.map((s, i) => (
          <span key={s.name} className="legend-item">
            <span className={`line-key series-fill-${i + 1}`} aria-hidden="true" />
            <span className="legend-name">{s.name}</span>
          </span>
        ))}
      </div>
      <div className="trend-chart-plot">
        <svg viewBox={`0 0 ${W} ${H}`} onMouseMove={handleMouseMove} onMouseLeave={handleMouseLeave}>
          {ticks.map((t) => (
            <g key={t}>
              <line className="chart-gridline" x1={PAD_L} x2={PAD_L + plotW} y1={yAt(t)} y2={yAt(t)} />
              <text className="chart-tick-text" x={PAD_L - 8} y={yAt(t) + 4} textAnchor="end">
                {t === 0 || t === 1 ? String(t) : t.toFixed(2)}
              </text>
            </g>
          ))}
          <line className="chart-axis" x1={PAD_L} x2={PAD_L + plotW} y1={yAt(yMin)} y2={yAt(yMin)} />
          {labels.map((label, i) =>
            i % labelStep === 0 || i === n - 1 ? (
              <text key={`${label}-${i}`} className="chart-tick-text" x={xAt(i)} y={H - 8} textAnchor="middle">
                {label}
              </text>
            ) : null,
          )}
          {series.map((s, i) => {
            const points = s.values.map((v, j) => `${xAt(j)},${yAt(v)}`).join(' ')
            const last = s.values[s.values.length - 1]
            return (
              <g key={s.name}>
                <polyline className={`trend-line trend-line-${i + 1}`} points={points} />
                <circle className={`trend-dot series-fill-${i + 1}`} cx={xAt(n - 1)} cy={yAt(last)} r="4" />
                {showEndLabels && (
                  <text className="trend-end-label" x={xAt(n - 1) + 8} y={yAt(last) + 4}>
                    {formatDecimal(last)}
                  </text>
                )}
              </g>
            )
          })}
          {active !== null && (
            <g>
              <line className="chart-crosshair" x1={xAt(active)} x2={xAt(active)} y1={PAD_T} y2={PAD_T + plotH} />
              {series.map((s, i) => (
                <circle key={s.name} className={`trend-dot series-fill-${i + 1}`} cx={xAt(active)} cy={yAt(s.values[active])} r="4.5" />
              ))}
            </g>
          )}
        </svg>
        {active !== null && (
          <div className={`trend-tooltip${tooltipLeftPct > 55 ? ' is-left' : ''}`} style={{ left: `${tooltipLeftPct}%` }}>
            <div className="tooltip-title">{labels[active]}</div>
            {series.map((s, i) => (
              <div key={s.name} className="tooltip-row">
                <span className={`line-key series-fill-${i + 1}`} aria-hidden="true" />
                <span className="tooltip-name">{s.name}</span>
                <strong className="tooltip-value">{formatDecimal(s.values[active])}</strong>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
