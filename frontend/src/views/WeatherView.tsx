//* 情绪天气预报页: 日期范围过滤 (预设 + 自定义), 天气日卡 + 趋势图 + 数据表.
//* 数据刷新时保持旧帧降透明, 不做骨架屏跳变.

import { useCallback, useEffect, useState } from 'react'
import { getWeather } from '../api/diary'
import { ApiError } from '../api/http'
import { toast } from '../utils/toast'
import { formatDecimal, formatScore, formatShortDate, toDateParam } from '../utils/format'
import { resolveWeather } from '../utils/weather'
import type { WeatherDay } from '../types'
import TrendChart from '../components/weather/TrendChart'
import WeatherIcon from '../components/weather/WeatherIcon'

const PRESETS = [
  { label: '近 7 天', days: 7 },
  { label: '近 14 天', days: 14 },
  { label: '近 30 天', days: 30 },
]

function defaultRange(days: number): { start: string; end: string } {
  const end = new Date()
  const start = new Date()
  start.setDate(end.getDate() - (days - 1))
  return { start: toDateParam(start), end: toDateParam(end) }
}

export default function WeatherView() {
  const [presetDays, setPresetDays] = useState(14)
  const [custom, setCustom] = useState<{ start: string; end: string } | null>(null)
  const [data, setData] = useState<WeatherDay[]>([])
  const [loading, setLoading] = useState(false)
  const [focusIndex, setFocusIndex] = useState<number | null>(null)

  const range = custom ?? defaultRange(presetDays)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await getWeather(range.start, range.end)
      setData(list)
      setFocusIndex(null)
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '加载情绪天气失败', 'error')
    } finally {
      setLoading(false)
    }
  }, [range.start, range.end]) // eslint-disable-line react-hooks/exhaustive-deps -- 仅依赖起止日期原语

  useEffect(() => {
    void load()
  }, [load])

  const hero = data.length > 0 ? data[data.length - 1] : null
  const heroWeather = resolveWeather(hero?.weatherType)

  return (
    <div className="weather-view">
      <div className="view-head">
        <div>
          <h1 className="view-title">情绪天气预报</h1>
          <p className="view-sub">你的情绪, 也有晴雨变化</p>
        </div>
      </div>

      {/* 过滤行: 预设在前, 自定义日期在后, 作用于下方所有图表 */}
      <div className="filter-row card">
        <div className="preset-group" role="group" aria-label="日期范围">
          {PRESETS.map((p) => (
            <button
              key={p.days}
              type="button"
              className={`preset-btn${!custom && presetDays === p.days ? ' active' : ''}`}
              onClick={() => {
                setCustom(null)
                setPresetDays(p.days)
              }}
            >
              {p.label}
            </button>
          ))}
        </div>
        <label className="filter-field">
          <span className="label">自定义开始</span>
          <input
            type="date"
            className="input"
            value={range.start}
            max={range.end}
            onChange={(e) => setCustom({ start: e.target.value, end: range.end })}
          />
        </label>
        <label className="filter-field">
          <span className="label">自定义结束</span>
          <input
            type="date"
            className="input"
            value={range.end}
            min={range.start}
            onChange={(e) => setCustom({ start: range.start, end: e.target.value })}
          />
        </label>
      </div>

      {hero ? (
        <div className={`weather-hero card${loading ? ' is-loading-frame' : ''}`}>
          <div className="hero-icon">{heroWeather && <WeatherIcon type={heroWeather.icon} size={72} />}</div>
          <div className="hero-main">
            <div className="hero-date">{hero.date}</div>
            <div className="hero-label">{heroWeather?.label ?? '未知'}</div>
            <p className="hero-tip">{heroWeather?.tip}</p>
          </div>
          <div className="hero-values">
            <span className="hero-value">
              <span className="score-dot series-fill-1" aria-hidden="true" /> 积极 <strong className="tabular">{formatScore(hero.positiveAvg)}</strong>
            </span>
            <span className="hero-value">
              <span className="score-dot series-fill-2" aria-hidden="true" /> 消极 <strong className="tabular">{formatScore(hero.negativeAvg)}</strong>
            </span>
            <span className="hero-value">
              <span className="score-dot series-fill-3" aria-hidden="true" /> 焦虑 <strong className="tabular">{formatScore(hero.anxietyAvg)}</strong>
            </span>
          </div>
        </div>
      ) : (
        <div className="empty-state card">
          <span className="empty-icon" aria-hidden="true">
            ☀
          </span>
          <p>这段时间还没有日记, 记录心情后这里会生成你的专属情绪天气预报</p>
        </div>
      )}

      {data.length > 0 && (
        <>
          <div className={`weather-strip${loading ? ' is-loading-frame' : ''}`} aria-label="按日天气概览">
            {data.map((d, i) => {
              const w = resolveWeather(d.weatherType)
              return (
                <button
                  key={d.date}
                  type="button"
                  className={`weather-day${focusIndex === i ? ' active' : ''}`}
                  onClick={() => setFocusIndex(focusIndex === i ? null : i)}
                  aria-pressed={focusIndex === i}
                >
                  <span className="weather-day-date">{formatShortDate(d.date)}</span>
                  {w && <WeatherIcon type={w.icon} size={30} />}
                  <span className="weather-day-label">{w?.label ?? '未知'}</span>
                  <span className="weather-day-count tabular">{d.entryCount} 篇</span>
                </button>
              )
            })}
          </div>

          <div className={`weather-chart card${loading ? ' is-loading-frame' : ''}`}>
            <TrendChart
              labels={data.map((d) => formatShortDate(d.date))}
              series={[
                { name: '积极', values: data.map((d) => d.positiveAvg) },
                { name: '消极', values: data.map((d) => d.negativeAvg) },
                { name: '焦虑', values: data.map((d) => d.anxietyAvg) },
              ]}
              focusIndex={focusIndex}
            />
          </div>

          {/* 表格视图: 悬浮提示之外的无障碍通道 */}
          <div className="weather-table card">
            <table>
              <caption className="visually-hidden">情绪天气预报数据表</caption>
              <thead>
                <tr>
                  <th scope="col">日期</th>
                  <th scope="col">天气</th>
                  <th scope="col" className="tabular">
                    积极
                  </th>
                  <th scope="col" className="tabular">
                    消极
                  </th>
                  <th scope="col" className="tabular">
                    焦虑
                  </th>
                  <th scope="col" className="tabular">
                    记录
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.map((d, i) => {
                  const w = resolveWeather(d.weatherType)
                  return (
                    <tr key={d.date} className={focusIndex === i ? 'is-active' : ''} onClick={() => setFocusIndex(focusIndex === i ? null : i)}>
                      <td>{d.date}</td>
                      <td>
                        <span className="table-weather">
                          {w && <WeatherIcon type={w.icon} size={16} />}
                          {w?.label ?? '未知'}
                        </span>
                      </td>
                      <td className="tabular">{formatDecimal(d.positiveAvg)}</td>
                      <td className="tabular">{formatDecimal(d.negativeAvg)}</td>
                      <td className="tabular">{formatDecimal(d.anxietyAvg)}</td>
                      <td className="tabular">{d.entryCount}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
