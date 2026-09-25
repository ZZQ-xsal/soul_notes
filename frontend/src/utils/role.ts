//* 角色 → 首页: 学生进业务页, 咨询师/管理员进工作台 (后端按 @RolesAllowed 隔离两套接口).

import type { UserRole } from '../types'

export function homePathOf(role: UserRole | null | undefined): string {
  return role === 'COUNSELOR' || role === 'ADMIN' ? '/clinical' : '/diaries'
}
