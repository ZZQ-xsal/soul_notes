//* 应用入口: Provider 组合 + 路由 (路由表见 src/router/).
//* BrowserRouter 置于最外层供 AppRoutes 消费; RedAlertModal / CrisisModal 挂在 Routes 之外但自身不依赖路由,
//* 因此未登录时同样能弹出 (RED 预警与求助资源都不该被登录态或路由拦住).

import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { AlertProvider } from './context/AlertContext'
import AppRoutes from './router'
import RedAlertModal from './components/alert/RedAlertModal'
import ToastHost from './components/ToastHost'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AlertProvider>
          <AppRoutes />
          <RedAlertModal />
          <ToastHost />
        </AlertProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}
