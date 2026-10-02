//* RED 高危预警弹窗: 由 /ws/alert 推送或本地分析结果触发, 始终展示热线与求助入口;
//* "查看全部求助资源" 原地换成 CrisisModal 弹窗 (不再跳页).

import { useState } from 'react'
import { useAlert } from '../../context/AlertContext'
import CrisisModal from '../crisis/CrisisModal'

export default function RedAlertModal() {
  const { alert, dismiss, hotline } = useAlert()
  const [crisisOpen, setCrisisOpen] = useState(false)

  //! 收起预警后必须继续渲染求助弹窗: 若照旧在此 `return null`, 点"查看全部求助资源"会因 alert.visible
  //! 变 false 把刚打开的求助弹窗一并卸载掉.
  if (!alert.visible)
    return crisisOpen ? <CrisisModal onClose={() => setCrisisOpen(false)} /> : null

  //* 预约入口: 推送里带了就用推送的, 否则回落全局热线配置 (crisis.appointment.url), 均为空则整块隐藏.
  const appointmentUrl = alert.appointmentUrl || hotline.appointmentUrl

  //* 收起预警, 求助资源以弹窗续上 (不再跳页: 危急时刻不该把用户从当前上下文里带走);
  //* 此时若又推来一条 RED, alert.visible 复真 -> 预警覆盖全屏 (z-index 100 > 弹窗的 60), 关掉预警后
  //* 求助弹窗因 crisisOpen 仍为真而回到屏幕上.
  const goCrisis = (): void => {
    dismiss()
    setCrisisOpen(true)
  }

  return (
    <div className="red-alert-overlay" role="alertdialog" aria-modal="true" aria-label="高危预警">
      <div className="red-alert-card">
        <div className="red-alert-banner">
          <span className="red-alert-icon" aria-hidden="true">
            ⚠
          </span>
          <div>
            <h2>请先停一停, 我们很担心你</h2>
            <p>我们检测到你正经历非常艰难的时刻。你不需要独自面对, 专业的帮助随时可用。</p>
          </div>
        </div>
        {alert.message && <p className="red-alert-message">{alert.message}</p>}
        <div className="red-alert-hotline">
          <span className="red-alert-hotline-label">{hotline.name} · 24 小时</span>
          <a className="red-alert-number" href={`tel:${alert.hotline}`}>
            {alert.hotline}
          </a>
        </div>
        <div className="red-alert-actions">
          <a className="btn danger" href={`tel:${alert.hotline}`}>
            立即拨打
          </a>
          {appointmentUrl && (
            //* 新窗口打开: 危机弹窗不应被预约页替换掉 (热线与求助入口要继续留在屏幕上).
            <a className="btn secondary" href={appointmentUrl} target="_blank" rel="noreferrer">
              预约咨询
            </a>
          )}
          <button type="button" className="btn secondary" onClick={goCrisis}>
            查看全部求助资源
          </button>
          <button type="button" className="btn ghost" onClick={dismiss}>
            我知道了
          </button>
        </div>
        {hotline.backup && (
          <p className="red-alert-backup">
            备用热线: <a href={`tel:${hotline.backup}`}>{hotline.backup}</a>
          </p>
        )}
      </div>
    </div>
  )
}
