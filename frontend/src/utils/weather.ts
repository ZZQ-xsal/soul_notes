//* 情绪天气/预警等级的元数据映射.
//* 键同时兼容后端枚举名 (SUNNY) 与 mock 数据中的小写值 (sunny).

export type WeatherIconType = 'sunny' | 'cloudy' | 'overcast' | 'rainy' | 'thunderstorm' | 'unknown'

export interface WeatherMeta {
  key: string
  label: string
  icon: WeatherIconType
  tip: string
}

const WEATHER_MAP: Record<string, Omit<WeatherMeta, 'key'>> = {
  SUNNY: { label: '晴', icon: 'sunny', tip: '情绪积极明朗' },
  CLOUDY: { label: '多云', icon: 'cloudy', tip: '情绪平稳温和' },
  OVERCAST: { label: '阴', icon: 'overcast', tip: '情绪略低沉' },
  RAINY: { label: '雨', icon: 'rainy', tip: '情绪低落, 需要自我关怀' },
  THUNDERSTORM: { label: '雷暴', icon: 'thunderstorm', tip: '情绪波动强烈, 建议寻求支持' },
}

/** 将后端天气字段解析为统一元数据; 无法识别时降级为未知图标 + 原文标签 */
export function resolveWeather(raw: string | { label?: string; icon?: string } | null | undefined): WeatherMeta | null {
  if (!raw) return null
  if (typeof raw === 'string') {
    const key = raw.toUpperCase()
    const meta = WEATHER_MAP[key]
    return meta ? { key, ...meta } : { key: raw, label: raw, icon: 'unknown', tip: '' }
  }
  return {
    key: raw.label ?? '',
    label: raw.label ?? '未知',
    icon: (raw.icon as WeatherIconType | undefined) ?? 'unknown',
    tip: '',
  }
}

export interface WarningMeta {
  label: string
  tone: 'none' | 'warning' | 'critical'
}

const WARNING_MAP: Record<string, WarningMeta> = {
  NONE: { label: '状态平稳', tone: 'none' },
  YELLOW: { label: '需关注', tone: 'warning' },
  RED: { label: '高危预警', tone: 'critical' },
}

export function resolveWarning(raw: string | null | undefined): WarningMeta {
  if (!raw) return WARNING_MAP.NONE
  return WARNING_MAP[raw.toUpperCase()] ?? { label: raw, tone: 'none' }
}
