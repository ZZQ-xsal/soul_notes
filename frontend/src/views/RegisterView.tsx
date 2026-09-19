//* 注册页: 密码强度实时校验 (与后端规则一致: >=8 位, 含字母/数字/特殊字符).

import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiError } from '../api/http'

const PASSWORD_RULES = [
  { key: 'length', label: '至少 8 位', test: (p: string) => p.length >= 8 },
  { key: 'letter', label: '包含字母', test: (p: string) => /[A-Za-z]/.test(p) },
  { key: 'digit', label: '包含数字', test: (p: string) => /\d/.test(p) },
  { key: 'special', label: '包含特殊字符', test: (p: string) => /[^A-Za-z0-9]/.test(p) },
] as const

export default function RegisterView() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const passwordOk = PASSWORD_RULES.every((r) => r.test(password))
  const canSubmit = username.trim().length >= 3 && passwordOk && password === confirm && !submitting

  const handleSubmit = async (e: FormEvent): Promise<void> => {
    e.preventDefault()
    if (!canSubmit) return
    setSubmitting(true)
    setError('')
    try {
      //* 注册成功即返回 token, 直接进入应用.
      await register(username.trim(), password)
      navigate('/diaries', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '注册失败, 请稍后再试')
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card card">
        <div className="auth-brand">
          <h1>加入心灵札记</h1>
          <p>注册仅需一个用户名和密码, 即刻开始记录心情</p>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label className="label" htmlFor="reg-username">
              用户名
            </label>
            <input
              id="reg-username"
              className="input"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              minLength={3}
              maxLength={32}
              required
            />
            <p className="field-hint">3-32 位, 可使用字母、数字、下划线与连字符</p>
          </div>
          <div className="form-field">
            <label className="label" htmlFor="reg-password">
              密码
            </label>
            <input
              id="reg-password"
              className="input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              required
            />
            <ul className="password-rules">
              {PASSWORD_RULES.map((r) => (
                <li key={r.key} className={r.test(password) ? 'is-ok' : ''}>
                  {r.test(password) ? '✓' : '○'} {r.label}
                </li>
              ))}
            </ul>
          </div>
          <div className="form-field">
            <label className="label" htmlFor="reg-confirm">
              确认密码
            </label>
            <input
              id="reg-confirm"
              className="input"
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="new-password"
              required
            />
            {confirm.length > 0 && confirm !== password && <p className="field-hint field-hint-error">两次输入的密码不一致</p>}
          </div>
          {error && <p className="form-error">{error}</p>}
          <button type="submit" className="btn auth-submit" disabled={!canSubmit}>
            {submitting ? '注册中…' : '注册并登录'}
          </button>
        </form>
        <p className="auth-switch">
          已有账号? <Link to="/login">去登录</Link>
        </p>
      </div>
    </div>
  )
}
