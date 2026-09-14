//* 情绪天气图标 (手写 SVG): 图形差异承载身份, 颜色仅作辅助, 图标始终与文字标签配套出现.

import type { WeatherIconType } from '../../utils/weather'

interface Props {
  type: WeatherIconType
  size?: number
}

const SUN_RAYS: Array<[number, number, number, number]> = [
  [32, 3, 32, 11],
  [32, 53, 32, 61],
  [3, 32, 11, 32],
  [53, 32, 61, 32],
  [11.5, 11.5, 17, 17],
  [47, 47, 52.5, 52.5],
  [11.5, 52.5, 17, 47],
  [47, 17, 52.5, 11.5],
]

/** 卡通云朵: 三个圆 + 底部圆角矩形拼接 */
function Cloud({ y = 40, scale = 1, fill = 'var(--weather-cloud)' }: { y?: number; scale?: number; fill?: string }) {
  return (
    <g fill={fill} transform={scale === 1 ? undefined : `translate(${32 - 32 * scale} ${y - y * scale}) scale(${scale})`}>
      <circle cx="20" cy={y} r="11" />
      <circle cx="32" cy={y - 6} r="14" />
      <circle cx="44" cy={y} r="10" />
      <rect x="10" y={y} width="44" height="8" rx="4" />
    </g>
  )
}

export default function WeatherIcon({ type, size = 32 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" focusable="false">
      {type === 'sunny' && (
        <>
          <g stroke="var(--weather-sun)" strokeWidth="4" strokeLinecap="round">
            {SUN_RAYS.map(([x1, y1, x2, y2]) => (
              <line key={`${x1}-${y1}`} x1={x1} y1={y1} x2={x2} y2={y2} />
            ))}
          </g>
          <circle cx="32" cy="32" r="13" fill="var(--weather-sun)" />
        </>
      )}
      {type === 'cloudy' && (
        <>
          <circle cx="20" cy="20" r="8" fill="var(--weather-sun)" />
          <Cloud y={42} scale={0.9} />
        </>
      )}
      {type === 'overcast' && (
        <>
          <Cloud y={34} scale={0.85} fill="color-mix(in srgb, var(--weather-cloud) 60%, transparent)" />
          <Cloud y={44} />
        </>
      )}
      {type === 'rainy' && (
        <>
          <Cloud y={32} />
          <g stroke="var(--weather-rain)" strokeWidth="4" strokeLinecap="round">
            <line x1="24" y1="50" x2="20" y2="57" />
            <line x1="34" y1="52" x2="30" y2="59" />
            <line x1="44" y1="50" x2="40" y2="57" />
          </g>
        </>
      )}
      {type === 'thunderstorm' && (
        <>
          <Cloud y={32} />
          <polygon points="32,26 20,44 30,44 26,56 42,36 32,36" fill="var(--weather-storm)" />
        </>
      )}
      {type === 'unknown' && (
        <>
          <circle cx="32" cy="32" r="20" fill="none" stroke="var(--text-muted)" strokeWidth="3" strokeDasharray="6 5" />
          <text x="32" y="40" textAnchor="middle" fontSize="24" fill="var(--text-muted)">
            ?
          </text>
        </>
      )}
    </svg>
  )
}
