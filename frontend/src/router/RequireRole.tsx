//* 角色守卫: 角色不在白名单时退回该角色自己的首页.
//! 放在路由层而非视图内: 视图一旦挂载就会打出注定 403 的请求, 这里拦在渲染之前.

import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { homePathOf } from '../utils/role'
import type { UserRole } from '../types'

interface Props {
  roles: UserRole[]
}

export default function RequireRole({ roles }: Props) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  if (!roles.includes(user.role)) return <Navigate to={homePathOf(user.role)} replace />
  return <Outlet />
}
