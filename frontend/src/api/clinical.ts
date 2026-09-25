//* 咨询员工作台接口: /api/v1/clinical/* (后端 @RolesAllowed(COUNSELOR, ADMIN), 学生访问返回空体 403).

import { api } from './http'
import type { AssessmentVo, RiskLevel, StatsSummary } from '../types'

export type AssessmentListParams = {
  /** 留空 = 不限等级; 只接受 YELLOW / RED (其它值后端 400) */
  level?: RiskLevel | ''
  /** 时间窗天数, 默认 7; 0 或负数 = 不限时间 */
  days?: number
  page?: number
  size?: number
}

export function listAssessments(params: AssessmentListParams = {}): Promise<AssessmentVo[]> {
  return api<AssessmentVo[]>('/clinical/assessments', { params })
}

//! userId 必须是完整 UUID —— 掩码条目只给 8 位短码, 拿短码调本接口后端会 400.
export function listStudentAssessments(
  userId: string,
  params: { page?: number; size?: number } = {},
): Promise<AssessmentVo[]> {
  return api<AssessmentVo[]>(`/clinical/students/${userId}/assessments`, { params })
}

export function getStatsSummary(days = 7): Promise<StatsSummary> {
  return api<StatsSummary>('/clinical/stats/summary', { params: { days } })
}
