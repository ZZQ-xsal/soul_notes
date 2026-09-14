//* 危机干预离线兜底接口: GET /api/v1/crisis/hotline (免认证, 前端可缓存)

import { api } from './http'
import type { HotlineInfo } from '../types'

export function getHotline(): Promise<HotlineInfo> {
  return api<HotlineInfo>('/crisis/hotline', { auth: false })
}
