//* 认证接口: /api/v1/auth/*

import { api } from './http'
import type { AuthData } from '../types'

export function login(username: string, password: string): Promise<AuthData> {
  return api<AuthData>('/auth/login', { method: 'POST', auth: false, body: { username, password } })
}

//* 后端注册仅允许 STUDENT 角色 (提权防护), 前端固定传 STUDENT.
export function register(username: string, password: string): Promise<AuthData> {
  return api<AuthData>('/auth/register', { method: 'POST', auth: false, body: { username, password, role: 'STUDENT' } })
}

export function logout(): Promise<void> {
  return api<void>('/auth/logout', { method: 'POST' })
}
