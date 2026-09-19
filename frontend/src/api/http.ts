//* 统一 HTTP 封装: 注入 JWT, 解析 {code, message, data} 响应壳, 401 时广播未授权事件.

import type { ApiResponse } from '../types'

const BASE = '/api/v1'
const TOKEN_KEY = 'soul.auth'

export class ApiError extends Error {
  readonly code: number | null
  readonly status: number

  constructor(message: string, code: number | null = null, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

interface StoredAuth {
  token: string
  user: unknown
}

export function readStoredAuth(): StoredAuth | null {
  try {
    const raw = localStorage.getItem(TOKEN_KEY)
    return raw ? (JSON.parse(raw) as StoredAuth) : null
  } catch {
    return null
  }
}

export function saveStoredAuth(token: string, user: unknown): void {
  localStorage.setItem(TOKEN_KEY, JSON.stringify({ token, user }))
}

export function clearStoredAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function getToken(): string | null {
  return readStoredAuth()?.token ?? null
}

//* 401 统一处理回调 (由 AuthProvider 注册), 回调解耦避免模块循环依赖.
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(fn: (() => void) | null): void {
  unauthorizedHandler = fn
}

export interface ApiOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** multipart 请求体 (与 JSON body 互斥); 不手动设 Content-Type, 浏览器自动带 boundary */
  formData?: FormData
  params?: Record<string, string | number | null | undefined>
  /** 是否携带 JWT; 登录/注册等匿名接口显式传 false */
  auth?: boolean
}

/** 调用后端 REST 接口, 成功返回 data, 失败抛出 ApiError */
export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const { method = 'GET', body, formData, params, auth = true } = options

  const query = params
    ? '?' +
      new URLSearchParams(
        Object.entries(params)
          .filter(([, v]) => v !== undefined && v !== null && v !== '')
          .map(([k, v]) => [k, String(v)]),
      ).toString()
    : ''

  const headers: Record<string, string> = {}
  if (body !== undefined && formData === undefined) headers['Content-Type'] = 'application/json'
  const token = auth ? getToken() : null
  if (token) headers.Authorization = `Bearer ${token}`

  let res: Response
  try {
    res = await fetch(BASE + path + query, {
      method,
      headers,
      body: formData ?? (body !== undefined ? JSON.stringify(body) : undefined),
    })
  } catch {
    throw new ApiError('无法连接服务器, 请确认后端已启动 (http://localhost:8080)', null, 0)
  }

  //! 仅当请求本身携带了 token 时 401 才代表会话失效; 登录失败等匿名 401 交由调用方展示.
  if (res.status === 401 && token) unauthorizedHandler?.()

  let payload: ApiResponse<T>
  try {
    payload = (await res.json()) as ApiResponse<T>
  } catch {
    //! 部分响应 (如安全层拒绝的 403) 不经过后端 GlobalExceptionMapper, 响应体为空或非 JSON,
    //! 无法拿到统一外壳, 只能按 HTTP 状态码给出人话提示.
    const hint =
      res.status === 403
        ? '没有权限访问该资源'
        : res.status === 404
          ? '资源不存在'
          : res.status >= 500
            ? '服务器内部错误'
            : '服务器返回异常'
    //* 带上请求路径, 报错时能直接定位是哪个接口被拒.
    throw new ApiError(`${hint} (HTTP ${res.status}, ${method} ${path})`, null, res.status)
  }

  if (payload.code !== 0 || !res.ok) {
    throw new ApiError(payload.message || `请求失败 (HTTP ${res.status})`, payload.code, res.status)
  }
  return payload.data as T
}
