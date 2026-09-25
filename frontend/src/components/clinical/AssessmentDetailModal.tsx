//* 预警条目详情: 摘要 / 结构化标签 / 该学生历史记录 (仅实名解锁条目可查).
//! 掩码条目的 userId 是 8 位短码, 学生维度接口只接受完整 UUID, 故此时不提供历史入口.

import { useState } from 'react'
import { listStudentAssessments } from '../../api/clinical'
import { ApiError } from '../../api/http'
import { toast } from '../../utils/toast'
import { formatDateTime } from '../../utils/format'
import { resolveWarning } from '../../utils/weather'
import type { AssessmentVo } from '../../types'

interface Props {
  assessment: AssessmentVo
  onClose: () => void
}

//! 只有未掩码的条目才拿得到完整 UUID (RevealPolicy 解锁等级, 后端默认 RED).
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

//! tags 的 canonical 形态是字符串数组; 后端原样透传 AI 载荷不做校验, 故数组/对象两种都兜.
function tagStrings(tags: AssessmentVo['tags']): string[] {
  if (!Array.isArray(tags)) return []
  return tags.filter((t): t is string => typeof t === 'string' && t.trim() !== '')
}

/** 非数组形态按标量键值渲染 (嵌套结构形状不保证, 只取一层标量). */
function scalarEntries(tags: AssessmentVo['tags']): [string, string][] {
  if (!tags || Array.isArray(tags) || typeof tags !== 'object') return []
  return Object.entries(tags)
    .filter(([, v]) => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean')
    .map(([k, v]) => [k, String(v)])
}

export default function AssessmentDetailModal({ assessment, onClose }: Props) {
  const [timeline, setTimeline] = useState<AssessmentVo[] | null>(null)
  const [loading, setLoading] = useState(false)
  const warning = resolveWarning(assessment.riskLevel)
  const canQueryTimeline = UUID_RE.test(assessment.userId)
  const tags = tagStrings(assessment.tags)
  const tagRows = scalarEntries(assessment.tags)

  const loadTimeline = async (): Promise<void> => {
    setLoading(true)
    try {
      setTimeline(await listStudentAssessments(assessment.userId, { size: 20 }))
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '加载该学生历史失败', 'error')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-label="预警条目详情"
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="modal-close" onClick={onClose} aria-label="关闭">
          ×
        </button>

        <div className="clin-detail-head">
          <strong className="clin-detail-name">{assessment.displayName}</strong>
          <span className={`clin-badge is-${warning.tone}`}>{warning.label}</span>
        </div>
        <time className="detail-time">{formatDateTime(assessment.createdAt)}</time>

        <p className="detail-content">{assessment.summary || '(无摘要)'}</p>

        <section className="detail-analysis" aria-label="情绪标签">
          <h3>情绪标签</h3>
          {tags.length > 0 ? (
            <ul className="clin-tag-chips">
              {tags.map((t, i) => (
                <li key={`${t}-${i}`} className="clin-tag-chip">
                  {t}
                </li>
              ))}
            </ul>
          ) : tagRows.length > 0 ? (
            <ul className="clin-tags">
              {tagRows.map(([k, v]) => (
                <li key={k}>
                  <span className="clin-tag-key">{k}</span>
                  <span className="clin-tag-val">{v}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="analysis-missing">本轮对话未提取到情绪标签。</p>
          )}
        </section>

        <section className="detail-analysis" aria-label="学生历史记录">
          <h3>该学生历史记录</h3>
          {!canQueryTimeline ? (
            <p className="analysis-missing">
              该条目未解锁实名 (后端按 RevealPolicy 掩码), 无法按学生查询历史。
            </p>
          ) : timeline === null ? (
            <button type="button" className="btn secondary sm" onClick={() => void loadTimeline()} disabled={loading}>
              {loading ? '加载中…' : '加载历史记录'}
            </button>
          ) : timeline.length === 0 ? (
            <p className="analysis-missing">该学生暂无其它预警记录。</p>
          ) : (
            <ul className="clin-timeline">
              {timeline.map((t) => {
                const w = resolveWarning(t.riskLevel)
                return (
                  <li key={t.id}>
                    <span className={`clin-badge sm is-${w.tone}`}>{w.label}</span>
                    <time className="tabular">{formatDateTime(t.createdAt)}</time>
                    <span className="clin-timeline-summary">{t.summary}</span>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      </div>
    </div>
  )
}
