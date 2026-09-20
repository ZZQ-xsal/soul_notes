//* AI 服务使用协议与免责声明弹窗: 完整阅读 + 勾选同意; 同意状态写入 localStorage (同浏览器下次免勾).

import { useState } from 'react'

export const AGREEMENT_STORAGE_KEY = 'soulnotes.ai-disclaimer.v1'

interface Props {
  onAgree: () => void
  onClose: () => void
}

export default function AgreementModal({ onAgree, onClose }: Props) {
  const [checked, setChecked] = useState(false)

  const confirm = (): void => {
    if (!checked) return
    localStorage.setItem(AGREEMENT_STORAGE_KEY, '1')
    onAgree()
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-label="AI 服务使用协议与免责声明"
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="modal-close" onClick={onClose} aria-label="关闭">
          ×
        </button>
        <h1 className="agreement-title">AI 服务使用协议与免责声明</h1>
        <p className="agreement-meta">版本 v1.0 · 生效日期 2026-09-20</p>
        <div className="agreement-body">
          <h2>一、服务性质与说明</h2>
          <p>心灵札记提供 AI 对话、情绪天气分析等功能, 用于日常情绪记录与自我觉察。</p>
          <p>AI 功能输出由大语言模型自动生成, 不代表任何个人或机构的专业意见。</p>

          <h2>二、AI 工具边界(请务必了解)</h2>
          <ul>
            <li>AI 不是心理咨询师、精神科医生或任何持证专业人员, 不提供诊断、治疗、用药建议。</li>
            <li>AI 不了解你的真实处境, 输出可能存在不准确、不完整或不恰当之处, 仅供参考。</li>
            <li>请勿将 AI 输出作为医疗、用药、法律或其他专业决策的依据。</li>
          </ul>

          <h2>三、危机情形处理</h2>
          <p>
            AI 无法处理危机情形。如你有自伤、自杀或伤害他人的想法, 或处于任何紧急危险中, 请立即拨打 110(报警)或
            120(急救), 或通过"需要帮助"页面使用 24 小时心理援助热线。
          </p>
          <p>
            请优先联系现实中的家人、朋友或专业机构, <strong>不要依赖 AI 应对危机</strong>。
          </p>

          <h2>四、隐私与数据</h2>
          <ul>
            <li>为提供服务, 你的账号信息、日记与 AI 对话内容将被存储和处理。</li>
            <li>请勿在对话中主动发送身份证号、家庭住址、银行卡号等敏感个人信息。</li>
            <li>平台将以合理的技术与管理措施保护数据安全。</li>
          </ul>

          <h2>五、用户责任</h2>
          <ul>
            <li>你应自行判断 AI 输出的适用性, 并对采纳后的行为及后果负责。</li>
            <li>你不得利用本服务从事违法活动, 或诱导 AI 生成违法违规内容。</li>
          </ul>

          <h2>六、免责声明</h2>
          <ul>
            <li>在法律允许的最大范围内, 平台对因使用或无法使用本服务(含 AI 功能)产生的直接或间接损失不承担责任。</li>
            <li>平台不对 AI 输出的准确性、完整性与可用性作任何明示或默示保证。</li>
          </ul>

          <h2>七、协议的更新与接受</h2>
          <ul>
            <li>平台可能修订本协议; 重大修订时将再次提示阅读与同意, 继续使用即视为接受更新后的协议。</li>
            <li>不同意本协议将无法登录使用本服务。</li>
          </ul>
        </div>
        <label className="agreement-check">
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
          <span>我已完整阅读并同意以上全部内容</span>
        </label>
        <button type="button" className="btn agreement-confirm" disabled={!checked} onClick={confirm}>
          同意并继续
        </button>
      </div>
    </div>
  )
}
