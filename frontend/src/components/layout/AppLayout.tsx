//* 应用外壳: 侧边品牌 + 导航 + 用户区; 窄屏收缩为顶部导航条.

import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from '../../context/AuthContext'
import { toast } from '../../utils/toast'
import type { UserRole } from '../../types'

const ROLE_LABELS: Record<UserRole, string> = {
  STUDENT: '学生',
  COUNSELOR: '咨询师',
  ADMIN: '管理员',
}

interface NavItem {
  to: string
  label: string
  icon: ReactNode
  /** 可见角色; 学生业务页与咨询员工作台互斥 (后端接口同样按角色隔离) */
  roles: UserRole[]
}

//* 内联 SVG 导航图标 (20x20, 跟随 currentColor)
const ICON_DIARY = (
  <svg viewBox="0 0 20 20" aria-hidden="true">
    <path d="M4 2.5h9a3 3 0 0 1 3 3v12h-9a3 3 0 0 1-3-3v-12z" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <path d="M4 5.5h9" stroke="currentColor" strokeWidth="1.5" />
    <path d="M4 9h9" stroke="currentColor" strokeWidth="1.5" />
  </svg>
)

const ICON_WEATHER = (
  <svg viewBox="0 0 20 20" aria-hidden="true">
    <circle cx="8" cy="7" r="2.6" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <path d="M3 14.5h10a2.6 2.6 0 0 0 .6-5.1A4.5 4.5 0 0 0 5.3 7.8 3.4 3.4 0 0 0 3 14.5z" fill="none" stroke="currentColor" strokeWidth="1.5" />
  </svg>
)

const ICON_CHAT = (
  <svg viewBox="0 0 20 20" aria-hidden="true">
    <path d="M3 4.5h14v9H9l-4 3v-3H3v-9z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
  </svg>
)

const ICON_CRISIS = (
  <svg viewBox="0 0 20 20" aria-hidden="true">
    <circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <circle cx="10" cy="10" r="2.6" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <path d="M3.6 7.2 7.2 3.6M16.4 7.2 12.8 3.6M3.6 12.8 7.2 16.4M16.4 12.8 12.8 16.4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
  </svg>
)

const ICON_CLINICAL = (
  <svg viewBox="0 0 20 20" aria-hidden="true">
    <path d="M5.5 3h9v14h-9z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
    <path d="M8 7h4M8 10h4M8 13h2.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    <path d="M3 5.5v9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
  </svg>
)

const NAV_ITEMS: NavItem[] = [
  { to: '/diaries', label: '心情日记', icon: ICON_DIARY, roles: ['STUDENT'] },
  { to: '/weather', label: '情绪天气', icon: ICON_WEATHER, roles: ['STUDENT'] },
  { to: '/chat', label: '树洞对话', icon: ICON_CHAT, roles: ['STUDENT'] },
  { to: '/clinical', label: '咨询员工作台', icon: ICON_CLINICAL, roles: ['COUNSELOR', 'ADMIN'] },
  { to: '/crisis', label: '求助资源', icon: ICON_CRISIS, roles: ['STUDENT', 'COUNSELOR', 'ADMIN'] },
]

export default function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  //* 导航按角色过滤: 学生看不到工作台, 咨询师看不到学生业务页 (后端同样按角色拒绝).
  const navItems = NAV_ITEMS.filter((item) => item.roles.includes(user?.role ?? 'STUDENT'))

  const handleLogout = async (): Promise<void> => {
    await logout()
    toast('已退出登录', 'info')
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand">
          <svg className="brand-logo" viewBox="0 0 64 64" aria-hidden="true">
            <circle cx="32" cy="32" r="30" fill="var(--brand)" />
            <path d="M32 46C20 38 12 30 12 22a10 10 0 0 1 20-4 10 10 0 0 1 20 4c0 8-8 16-20 24z" fill="var(--brand-ink)" />
          </svg>
          <div className="brand-text">
            <span className="brand-title">心灵札记</span>
            <span className="brand-sub">Xinling Zhaji</span>
          </div>
        </div>
        <nav className="app-nav" aria-label="主导航">
          {navItems.map((item) => (
            <NavLink key={item.to} to={item.to} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
              <span className="nav-icon">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="user-chip">
            <span className="user-avatar" aria-hidden="true">
              {user?.username.slice(0, 1).toUpperCase()}
            </span>
            <div className="user-meta">
              <span className="user-name">{user?.username}</span>
              <span className="user-role">{ROLE_LABELS[user?.role ?? 'STUDENT']}</span>
            </div>
          </div>
          <button type="button" className="btn ghost logout-btn" onClick={handleLogout}>
            退出登录
          </button>
        </div>
      </aside>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}
