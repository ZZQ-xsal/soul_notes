//* 登录页: 含演示账号一键填充 (对应 sql_scripts/users_mock_data.sql).

import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ApiError } from "../api/http";
import AgreementModal, {
  AGREEMENT_STORAGE_KEY,
} from "../components/auth/AgreementModal";

const DEMO_ACCOUNTS = [
  { username: "alice", role: "学生" },
  { username: "bob", role: "学生" },
  { username: "charlie", role: "咨询师" },
  { username: "diana", role: "管理员" },
  { username: "eve", role: "学生" },
];
const DEMO_PASSWORD = "Soulnotes123!";

export default function LoginView() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  //* 协议同意状态: 同意过写入 localStorage, 同浏览器下次免勾; 未同意禁止登录.
  const [agreed, setAgreed] = useState(
    () => localStorage.getItem(AGREEMENT_STORAGE_KEY) === "1",
  );
  const [showAgreement, setShowAgreement] = useState(false);
  const [agreeHint, setAgreeHint] = useState(false);

  //* 登录成功后回跳来源页, 默认进日记页.
  const from = (location.state as { from?: string } | null)?.from ?? "/diaries";

  const handleSubmit = async (e: FormEvent): Promise<void> => {
    e.preventDefault();
    //* 未同意协议时点击登录: 按钮不置灰, 点击后给出勾选提醒.
    if (!agreed) {
      setAgreeHint(true);
      return;
    }
    if (!username.trim() || !password || submitting) return;
    setSubmitting(true);
    setError("");
    try {
      await login(username.trim(), password);
      //* 角色分流交给路由层 (RequireRole + homePathOf): 学生进业务页, 咨询师/管理员进工作台.
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "登录失败, 请稍后再试");
      setSubmitting(false);
    }
  };

  const fillDemo = (name: string): void => {
    setUsername(name);
    setPassword(DEMO_PASSWORD);
    setError("");
  };

  return (
    <div className="auth-page">
      <div className="sun-corner" aria-hidden="true">
        <div className="sun-spin" />
      </div>
      <div className="sky-decor" aria-hidden="true">
        <div className="cloud cloud-c1" />
        <div className="cloud cloud-c2" />
        <div className="cloud cloud-c3" />
        <div className="star star-s1" />
        <div className="star star-s2" />
        <div className="star star-s3" />
        <div className="star star-s4" />
        <div className="star star-s5" />
        <div className="star star-s6" />
        <div className="star star-s7" />
        <div className="star star-s8" />
      </div>
      <div className="tree-corner" aria-hidden="true" />
      <div className="auth-card card">
        <div className="auth-brand">
          <svg
            className="brand-logo auth-logo"
            viewBox="0 0 64 64"
            aria-hidden="true"
          >
            <circle cx="32" cy="32" r="30" fill="var(--brand)" />
            <path
              d="M32 46C20 38 12 30 12 22a10 10 0 0 1 20-4 10 10 0 0 1 20 4c0 8-8 16-20 24z"
              fill="var(--brand-ink)"
            />
          </svg>
          <h1>心灵札记</h1>
          <p>倾听你的每一种情绪</p>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label className="label" htmlFor="login-username">
              用户名
            </label>
            <input
              id="login-username"
              className="input"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </div>
          <div className="form-field">
            <label className="label" htmlFor="login-password">
              密码
            </label>
            <input
              id="login-password"
              className="input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          {error && <p className="form-error">{error}</p>}
          {agreeHint && !agreed && (
            <p className="form-warn">
              请先阅读并勾选同意《AI 服务使用协议与免责声明》, 然后再登录
            </p>
          )}
          <label className="agreement-row">
            <input
              type="checkbox"
              checked={agreed}
              onChange={(e) => {
                setAgreed(e.target.checked);
                if (e.target.checked) setAgreeHint(false);
                if (!e.target.checked)
                  localStorage.removeItem(AGREEMENT_STORAGE_KEY);
              }}
            />
            <span>我已阅读并同意</span>
            <button
              type="button"
              className="agreement-link"
              onClick={() => setShowAgreement(true)}
            >
              《AI 服务使用协议与免责声明》
            </button>
          </label>
          <button
            type="submit"
            className="btn auth-submit"
            disabled={!username.trim() || !password || submitting}
          >
            {submitting ? "登录中…" : "登录"}
          </button>
        </form>
        <p className="auth-switch">
          还没有账号? <Link to="/register">注册一个</Link> ·{" "}
          <Link to="/crisis">需要帮助?</Link>
        </p>
        <details className="demo-box">
          <summary>演示账号 (点击填充)</summary>
          <div className="demo-list">
            {DEMO_ACCOUNTS.map((a) => (
              <button
                key={a.username}
                type="button"
                className="btn ghost sm demo-item"
                onClick={() => fillDemo(a.username)}
              >
                <span className="demo-name">{a.username}</span>
                <span className="demo-role">{a.role}</span>
              </button>
            ))}
          </div>
        </details>
      </div>
      {showAgreement && (
        <AgreementModal
          onAgree={() => {
            setAgreed(true);
            setAgreeHint(false);
            setShowAgreement(false);
          }}
          onClose={() => setShowAgreement(false)}
        />
      )}
    </div>
  );
}
