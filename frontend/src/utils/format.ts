//* 日期/数值格式化工具 (统一按浏览器本地时区渲染).

const pad = (n: number): string => String(n).padStart(2, '0')

/** ISO 时间串 -> "MM-DD HH:mm" (日记卡片等场景) */
export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** "YYYY-MM-DD" -> "MM-DD" (图表横轴短标签) */
export function formatShortDate(dateStr: string): string {
  const [, m, dd] = dateStr.split('-')
  return `${m}-${dd}`
}

/** Date -> "YYYY-MM-DD" (查询参数与 input[type=date] 值; 不用 toISOString, 避免时区偏移) */
export function toDateParam(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 0-1 情绪分值 -> 百分比文本 "85%" */
export function formatScore(v: number): string {
  return `${Math.round(v * 100)}%`
}

/** 0-1 数值 -> 两位小数 (图表端点标签 / 悬浮提示) */
export function formatDecimal(v: number): string {
  return v.toFixed(2)
}
