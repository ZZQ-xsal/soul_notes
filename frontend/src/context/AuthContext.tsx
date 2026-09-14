//* 全局认证状态: token 持久化于 localStorage, 401 广播触发统一清空.

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import * as authApi from '../api/auth'
import { clearStoredAuth, readStoredAuth, saveStoredAuth, setUnauthorizedHandler } from '../api/http'
import type { AuthData, UserRole } from '../types'

export interface AuthUser {
  userId: string
  username: string
  role: UserRole
}

interface AuthContextValue {
  token: string | null
  user: AuthUser | null
  isAuthenticated: boolean
  login: (username: string, password: string) => Promise<AuthData>
  register: (username: string, password: string) => Promise<AuthData>
  logout: () => Promise<void>
  clear: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function toUser(data: AuthData): AuthUser {
  return { userId: data.userId, username: data.username, role: data.role }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<{ token: string; user: AuthUser } | null>(() => {
    const stored = readStoredAuth()
    return stored ? { token: stored.token, user: stored.user as AuthUser } : null
  })

  const clear = useCallback(() => {
    clearStoredAuth()
    setAuth(null)
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const data = await authApi.login(username, password)
    saveStoredAuth(data.token, toUser(data))
    setAuth({ token: data.token, user: toUser(data) })
    return data
  }, [])

  const register = useCallback(async (username: string, password: string) => {
    const data = await authApi.register(username, password)
    saveStoredAuth(data.token, toUser(data))
    setAuth({ token: data.token, user: toUser(data) })
    return data
  }, [])

  const logout = useCallback(async () => {
    try {
      //! 登出接口可能因 token 已过期返回 401, 本地清理不应被阻塞.
      await authApi.logout()
    } catch {
      //* 忽略服务端登出失败
    }
    clear()
  }, [clear])

  useEffect(() => {
    setUnauthorizedHandler(clear)
    return () => setUnauthorizedHandler(null)
  }, [clear])

  const value = useMemo<AuthContextValue>(
    () => ({
      token: auth?.token ?? null,
      user: auth?.user ?? null,
      isAuthenticated: auth !== null,
      login,
      register,
      logout,
      clear,
    }),
    [auth, login, register, logout, clear],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
