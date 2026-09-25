//* 求助资源页 (公开可达): 热线优先服务端 /crisis/hotline, 失败时用本地缓存或默认值 (离线兜底精神).

import { Link } from 'react-router-dom'
import { useAlert } from '../context/AlertContext'
import { useAuth } from '../context/AuthContext'

export default function CrisisView() {
  const { hotline } = useAlert()
  const { isAuthenticated } = useAuth()

  return (
    <div className="crisis-page">
      <div className="crisis-card card">
        <h1>你不需要独自面对</h1>
        <p className="crisis-sub">如果你正处于危机时刻, 或只是想找人说说话, 这些资源随时为你服务。</p>
        <div className="hotline-grid">
          <a className="hotline-card hotline-primary" href={`tel:${hotline.primary}`}>
            <span className="hotline-label">{hotline.name}</span>
            <span className="hotline-number">{hotline.primary}</span>
            <span className="hotline-tip">点击拨打 · 24 小时</span>
          </a>
          {hotline.backup && (
            <a className="hotline-card" href={`tel:${hotline.backup}`}>
              <span className="hotline-label">备用热线</span>
              <span className="hotline-number">{hotline.backup}</span>
              <span className="hotline-tip">点击拨打</span>
            </a>
          )}
          {/* 预约入口由机构配置 (crisis.appointment.url) 下发, 未配置时为空串 -> 整卡不渲染 */}
          {hotline.appointmentUrl && (
            <a
              className="hotline-card hotline-appointment"
              href={hotline.appointmentUrl}
              target="_blank"
              rel="noreferrer"
            >
              <span className="hotline-label">心理咨询预约</span>
              <span className="hotline-action">前往预约入口</span>
              <span className="hotline-tip">校内心理中心 · 新窗口打开</span>
            </a>
          )}
        </div>
        <p className="crisis-message">{hotline.message}</p>
        <div className="emergency-note">
          <strong>紧急情况</strong>
          <p>
            如有即刻的人身危险, 请立即拨打 <a href="tel:110">110</a> 或 <a href="tel:120">120</a>。
          </p>
        </div>
        <p className="crisis-footer">
          热线信息会缓存至本地, 网络不可用时仍可查看最近一次缓存。
          <br />
          {isAuthenticated ? <Link to="/diaries">返回应用</Link> : <Link to="/login">返回登录</Link>}
        </p>
      </div>
    </div>
  )
}
