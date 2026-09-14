//* 路由表: /login /register /crisis 公开; 其余页面需登录 (RequireAuth 守卫).
//* BrowserRouter 在 App.tsx 最外层; 此处组件消费 useAuth/useAlert (RequireAuth/各视图),
//* 因此本组件必须挂载在 AuthProvider/AlertProvider 之内.

import { Navigate, Route, Routes } from 'react-router-dom'
import RequireAuth from './RequireAuth'
import AppLayout from '../components/layout/AppLayout'
import LoginView from '../views/LoginView'
import RegisterView from '../views/RegisterView'
import CrisisView from '../views/CrisisView'
import DiaryView from '../views/DiaryView'
import WeatherView from '../views/WeatherView'
import ChatView from '../views/ChatView'

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginView />} />
      <Route path="/register" element={<RegisterView />} />
      {/* 求助资源页公开可达: RED 预警弹窗在未登录时也能跳转进来 */}
      <Route path="/crisis" element={<CrisisView />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Navigate to="/diaries" replace />} />
          <Route path="/diaries" element={<DiaryView />} />
          <Route path="/weather" element={<WeatherView />} />
          <Route path="/chat" element={<ChatView />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
