//* 应用入口: Provider 组合 + 路由 (路由表见 src/router/).
//* BrowserRouter 置于最外层: RedAlertModal 虽在 Routes 之外, 也需路由上下文 (useNavigate 跳转 /crisis).

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
