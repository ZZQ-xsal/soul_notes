//* 路由表: /login /register /crisis 公开; 其余页面需登录 (RequireAuth 守卫).
//* 学生页与咨询员工作台按角色分流 (RequireRole), 首页按角色落到各自默认页.
//* BrowserRouter 在 App.tsx 最外层; 此处组件消费 useAuth/useAlert (RequireAuth/各视图),
//* 因此本组件必须挂载在 AuthProvider/AlertProvider 之内.

import { Navigate, Route, Routes } from 'react-router-dom'
import RequireAuth from './RequireAuth'
import RequireRole from './RequireRole'
import AppLayout from '../components/layout/AppLayout'
import LoginView from '../views/LoginView'
import RegisterView from '../views/RegisterView'
import CrisisView from '../views/CrisisView'
import DiaryView from '../views/DiaryView'
import WeatherView from '../views/WeatherView'
import ChatView from '../views/ChatView'
import ClinicalView from '../views/ClinicalView'
import { useAuth } from '../context/AuthContext'
import { homePathOf } from '../utils/role'

/** 首页按角色分流: 学生 → 日记, 咨询师/管理员 → 工作台 */
function HomeRedirect() {
  const { user } = useAuth()
  return <Navigate to={homePathOf(user?.role)} replace />
}

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginView />} />
      <Route path="/register" element={<RegisterView />} />
      {/* 求助资源页公开可达: RED 预警弹窗在未登录时也能跳转进来 */}
      <Route path="/crisis" element={<CrisisView />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="/" element={<HomeRedirect />} />
          <Route element={<RequireRole roles={['STUDENT']} />}>
            <Route path="/diaries" element={<DiaryView />} />
            <Route path="/weather" element={<WeatherView />} />
            <Route path="/chat" element={<ChatView />} />
          </Route>
          <Route element={<RequireRole roles={['COUNSELOR', 'ADMIN']} />}>
            <Route path="/clinical" element={<ClinicalView />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
