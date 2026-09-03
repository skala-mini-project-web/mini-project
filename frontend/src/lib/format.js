// Labels + semantic tones. Color rationing (DESIGN.md): violet = AI surface,
// crimson = critical/failure only, blue = info, green = success, neutral = rest.

export const PRODUCT_TYPE_LABEL = {
  INVESTMENT: '투자',
  LOAN: '대출',
  SAVINGS: '예금',
}

export const FINDING_TYPE_LABEL = {
  FRAMING: '프레이밍',
  OMISSION: '누락',
  MISUNDERSTANDING: '오인',
  ACCESSIBILITY: '접근성',
}

export const RULE_LABEL = {
  RETURN_FRAMING: '수익 프레이밍',
  LOSS_SOFTENING: '손실 완화',
  COST_OMISSION: '비용 누락',
  STABILITY_KEYWORD: '안정성 키워드',
  FORMAL_CONFIRMATION: '형식적 확인',
  COGNITIVE_ACCESSIBILITY: '인지 접근성',
}

export const PERSONA_LABEL = {
  FINANCIAL_BEGINNER: '금융 초보자',
  SENIOR: '고령 금융소비자',
  LOW_LITERACY: '저문해 소비자',
  LOSS_SENSITIVE: '손실 민감 소비자',
  DIGITAL_NOVICE: '디지털 초보자',
}

export const ACTION_TYPE_LABEL = {
  LABEL: '라벨',
  WARNING: '경고',
  QUESTION: '확인 질문',
  COMPARISON: '비교',
}

const STATUS = {
  // documents
  UPLOADED: { label: '업로드됨', tone: 'neutral' },
  EXTRACTING: { label: '추출 중', tone: 'accent' },
  READY: { label: '추출 완료', tone: 'ok' },
  // analysis
  CREATED: { label: '생성됨', tone: 'neutral' },
  RUNNING: { label: '분석 중', tone: 'accent' },
  COMPLETED: { label: '완료', tone: 'ok' },
  FAILED: { label: '실패', tone: 'high' },
  // review
  PENDING: { label: '검토 대기', tone: 'neutral' },
  APPROVED: { label: '승인', tone: 'ok' },
  REJECTED: { label: '반려', tone: 'high' },
  // product
  DRAFT: { label: '초안', tone: 'neutral' },
  ANALYZED: { label: '분석됨', tone: 'neutral' },
  IN_REVIEW: { label: '검토 중', tone: 'neutral' },
  NEEDS_FIX: { label: '수정 필요', tone: 'high' },
  // risk pattern / guardfit
  ACTIVE: { label: '활성', tone: 'ok' },
}

export function statusMeta(status) {
  return STATUS[status] || { label: status || '-', tone: 'neutral' }
}

const SEVERITY = {
  HIGH: { label: 'HIGH', tone: 'high' },
  MEDIUM: { label: 'MEDIUM', tone: 'med' },
  LOW: { label: 'LOW', tone: 'low' },
}
export function severityMeta(sev) {
  return SEVERITY[sev] || { label: sev || '-', tone: 'neutral' }
}

export function personaName(code) {
  return PERSONA_LABEL[code] || code
}

export function formatDateTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  // sv-SE renders ISO-like "2026-09-02 09:00" (no dots, 24h).
  return new Intl.DateTimeFormat('sv-SE', {
    dateStyle: 'short',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(d)
}

// Single lifecycle status for a product row: a running analysis takes
// precedence over the stored product status (avoids showing 분석 중 + 분석됨).
export function productStatusKey(p) {
  const a = p?.latestAnalysis
  if (a && (a.status === 'RUNNING' || a.status === 'CREATED')) return 'RUNNING'
  return p?.status
}

export function formatBytes(n) {
  if (!n) return '-'
  const mb = n / (1024 * 1024)
  if (mb >= 1) return `${mb.toFixed(1)} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}
