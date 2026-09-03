// =============================================================================
// GuardLab seed data (from Mock 데이터 명세서 v0.2).
// Fixed IDs, synthetic demo content. Legal/policy text is demo-only synthetic
// data and is not real legal interpretation.
// =============================================================================

import { computeRiskScore } from './scenarios.js'
import {
  GOLDEN_ANALYSIS,
  GOLDEN_AUDIT_LOG,
  GOLDEN_DOCUMENT,
  GOLDEN_EVIDENCE,
  GOLDEN_FACTS,
  GOLDEN_PERSONAS,
  GOLDEN_PRODUCT,
  GOLDEN_RED_TEAM_PACK,
  GOLDEN_REVIEW,
  buildGoldenOutcome,
  validateGoldenFixture,
} from './fixtures/v1/index.js'

validateGoldenFixture()
const GOLDEN_OUTCOME = buildGoldenOutcome()
const INCLUDE_BULK_SEED = import.meta.env?.VITE_DEMO_BULK_SEED === 'true'

const RISK_PATTERN_TITLES = {
  PRINCIPAL_PROTECTION_MISUNDERSTANDING: '원금 보장 오인',
  RETURN_FRAMING: '수익 프레이밍',
  LOSS_SOFTENING: '손실 위험 축소',
  COST_OMISSION: '비용 정보 누락',
  STABILITY_KEYWORD: '안정성 표현 오인',
  FORMAL_CONFIRMATION: '형식적 확인',
  COGNITIVE_ACCESSIBILITY: '인지 접근성',
}
const FINDING_TYPE_TITLES = {
  FRAMING: '오인 유발 표현',
  OMISSION: '필수 정보 누락',
  MISUNDERSTANDING: '소비자 오인',
  ACCESSIBILITY: '정보 접근성',
}
export function riskPatternTitle(finding) {
  return RISK_PATTERN_TITLES[finding.categoryCode || finding.aiDetail?.categoryCode]
    || RISK_PATTERN_TITLES[finding.ruleCode || finding.aiDetail?.ruleCode]
    || FINDING_TYPE_TITLES[finding.findingType || finding.aiDetail?.findingType]
    || '기타 위험 패턴'
}

export const USERS = [
  { id: 'USER-PM-001', name: '박서준 대리', role: 'PRODUCT_MANAGER', active: true },
  { id: 'USER-PM-002', name: '김민지 과장', role: 'PRODUCT_MANAGER', active: true },
  { id: 'USER-CR-001', name: '정하윤 수석', role: 'COMPLIANCE_REVIEWER', active: true },
]

export const PERSONA_TEMPLATES = [
  ...GOLDEN_PERSONAS,
  { personaId: 'PERSONA-FIN-BEGINNER', code: 'FINANCIAL_BEGINNER', name: '금융 초보자', riskFocus: '수익 보장 오해, 비용 누락', active: true },
  { personaId: 'PERSONA-SENIOR', code: 'SENIOR', name: '고령 금융소비자', riskFocus: '원금 보장 오해, 접근성', active: true },
  { personaId: 'PERSONA-LOW-LITERACY', code: 'LOW_LITERACY', name: '저문해 소비자', riskFocus: '전문용어, 인지 부담', active: true },
  { personaId: 'PERSONA-LOSS-SENSITIVE', code: 'LOSS_SENSITIVE', name: '손실 민감 소비자', riskFocus: '손실 완화 표현', active: true },
  { personaId: 'PERSONA-DIGITAL-NOVICE', code: 'DIGITAL_NOVICE', name: '디지털 초보자', riskFocus: 'CTA, 확인 흐름', active: true },
].filter((item) => INCLUDE_BULK_SEED || typeof item.personaId === 'number')

export const RED_TEAM_PACKS = [
  ...GOLDEN_RED_TEAM_PACK,
  {
    redTeamPackId: 'LEGACY-CORE-FINANCIAL-RISK-V1',
    code: 'CORE_FINANCIAL_RISK_V1',
    name: '금융소비자 핵심 위험 Pack',
    rules: [
      { ruleCode: 'RETURN_FRAMING', findingType: 'FRAMING', purpose: '과거 수익이 미래 확정처럼 보이는지' },
      { ruleCode: 'LOSS_SOFTENING', findingType: 'FRAMING', purpose: '손실 가능성을 축소, 완화하는지' },
      { ruleCode: 'COST_OMISSION', findingType: 'OMISSION', purpose: '수수료, 비용이 빠졌는지' },
      { ruleCode: 'STABILITY_KEYWORD', findingType: 'MISUNDERSTANDING', purpose: '안정 표현이 원금 보장으로 오인되는지' },
      { ruleCode: 'FORMAL_CONFIRMATION', findingType: 'MISUNDERSTANDING', purpose: '이해 확인이 형식적 동의에 그치는지' },
      { ruleCode: 'COGNITIVE_ACCESSIBILITY', findingType: 'ACCESSIBILITY', purpose: '고령, 저문해 소비자에게 인지 부담이 큰지' },
    ],
  },
].filter((item) => INCLUDE_BULK_SEED || typeof item.redTeamPackId === 'number')

export const EVIDENCE_DOCUMENTS = [
  ...GOLDEN_EVIDENCE,
  {
    documentId: 'POLICY-003',
    title: '금융상품 중요정보 표시 내부준칙 (데모)',
    sourceType: 'INTERNAL_POLICY',
    version: 'DEMO-2026.1',
    excerpt: '원금손실 가능성은 수익 및 안정성 표현과 인접해 표시해야 합니다.',
    active: true,
    synthetic: true,
    disclaimer: '발표용 합성 데이터이며 실제 법률 해석이 아닙니다.',
  },
  {
    documentId: 'LAW-014',
    title: '자본시장법 설명의무 관련 조항 (데모)',
    sourceType: 'LAW',
    version: 'DEMO-2026.1',
    excerpt: '투자상품의 손실 가능성과 비용 구조를 일반 소비자가 이해할 수 있게 설명해야 합니다.',
    active: true,
    synthetic: true,
    disclaimer: '발표용 합성 데이터이며 실제 법률 해석이 아닙니다.',
  },
  {
    documentId: 'GUIDE-021',
    title: '금융소비자보호 표현 가이드 (데모)',
    sourceType: 'INTERNAL_POLICY',
    version: 'DEMO-2025.4',
    excerpt: '고령 및 저문해 소비자를 위해 핵심 위험 문구는 쉬운 표현으로 반복 안내합니다.',
    active: true,
    synthetic: true,
    disclaimer: '발표용 합성 데이터이며 실제 법률 해석이 아닙니다.',
  },
].filter((item) => INCLUDE_BULK_SEED || typeof item.documentId === 'number')

// Golden-path findings for the seeded completed analysis (score 82).
const DEMO_FINDINGS = [
  {
    findingId: 'FND-001',
    findingType: 'FRAMING',
    categoryCode: 'PRINCIPAL_PROTECTION_MISUNDERSTANDING',
    ruleCode: 'STABILITY_KEYWORD',
    statement: '안정성 표현이 원금보장으로 오인될 가능성이 있습니다.',
    severity: 'HIGH',
    sourceReferences: [{ documentId: 'PDOC-001', page: 1, excerpt: '최근 안정적인 수익률을 기록한 투자상품입니다.' }],
    affectedPersonaCodes: ['FINANCIAL_BEGINNER', 'SENIOR'],
    evidenceReferences: [
      { evidenceDocumentId: 'POLICY-003', excerpt: '원금손실 가능성은 안정성 표현과 인접해 표시해야 합니다.', sourceType: 'INTERNAL_POLICY' },
    ],
    recommendation: '같은 영역에 원금 손실 가능성을 명시하세요.',
  },
  {
    findingId: 'FND-002',
    findingType: 'OMISSION',
    categoryCode: 'COST_OMISSION',
    ruleCode: 'COST_OMISSION',
    statement: '총비용 및 운용 보수가 인접 위치에 표시되지 않았습니다.',
    severity: 'MEDIUM',
    sourceReferences: [{ documentId: 'PDOC-001', page: 2, excerpt: '운용 보수' }],
    affectedPersonaCodes: ['FINANCIAL_BEGINNER'],
    evidenceReferences: [
      { evidenceDocumentId: 'POLICY-003', excerpt: '비용 정보는 수익 표현과 인접해 표시해야 합니다.', sourceType: 'INTERNAL_POLICY' },
    ],
    recommendation: '총비용 예시를 수익 표현 인접 위치에 표시하세요.',
  },
]

const CORE_PRODUCTS = [
  { productId: 'PROD-001', ownerId: 'USER-PM-001', name: '스마트 인컴 투자상품', productType: 'INVESTMENT', description: '월 지급식 인컴형 투자상품', status: 'IN_REVIEW', createdAt: '2026-09-02T09:00:00+09:00' },
  { productId: 'PROD-002', ownerId: 'USER-PM-001', name: '글로벌 배당 ETF 랩', productType: 'INVESTMENT', description: '해외 배당주 ETF 편입 랩상품', status: 'ANALYZED', createdAt: '2026-09-01T14:20:00+09:00' },
  { productId: 'PROD-003', ownerId: 'USER-PM-001', name: '생활든든 신용대출', productType: 'LOAN', description: '직장인 대상 신용대출', status: 'DRAFT', createdAt: '2026-09-02T08:10:00+09:00' },
  { productId: 'PROD-004', ownerId: 'USER-PM-001', name: '안심 정기예금', productType: 'SAVINGS', description: '원금 보장형 정기예금', status: 'DRAFT', createdAt: '2026-09-01T11:00:00+09:00' },
]
const PM_B_PRODUCT = {
  productId: 'PROD-PM-B-001', ownerId: 'USER-PM-002', name: 'PM B 전용 투자상품',
  productType: 'INVESTMENT', description: '수평 권한 검증용 상품', status: 'IN_REVIEW',
  createdAt: '2026-09-03T10:00:00+09:00',
}

// Bulk demo products so list scale (search, filter, pagination) is exercised.
const PREFIX = ['스마트', '프리미엄', '든든한', '글로벌', '내일의', '해바라기', '그린', '다임', '토니', '퍼스트', '애브니', '로엔', '샤인', '위드', '오로라', '세이프']
const SUFFIX = {
  INVESTMENT: ['투자상품', 'ETF 랩', '단기채권형', '리츠 재간접', 'TDF 패키지'],
  LOAN: ['신용대출', '전세대출', '직장인 대출', '비상금 대출'],
  SAVINGS: ['정기예금', '자유적금', '파킹적금', '체크적금'],
}
const TYPES = ['INVESTMENT', 'LOAN', 'SAVINGS']
const STATUS_POOL = ['ANALYZED', 'IN_REVIEW', 'APPROVED', 'NEEDS_FIX']
const EXTRA_PRODUCTS = Array.from({ length: 22 }, (_, i) => {
  const type = TYPES[i % TYPES.length]
  const suf = SUFFIX[type]
  const day = String(28 - (i % 26)).padStart(2, '0')
  const hh = String(8 + (i % 10)).padStart(2, '0')
  return {
    productId: `PROD-${101 + i}`,
    ownerId: 'USER-PM-001',
    name: `${PREFIX[i % PREFIX.length]} ${suf[i % suf.length]}`,
    productType: type,
    description: '데모 시드 상품',
    status: STATUS_POOL[i % STATUS_POOL.length],
    createdAt: `2026-08-${day}T${hh}:15:00+09:00`,
  }
})

export const PRODUCTS = [GOLDEN_PRODUCT, PM_B_PRODUCT, ...(INCLUDE_BULK_SEED ? [...CORE_PRODUCTS, ...EXTRA_PRODUCTS] : [])]

// ---- Coherent children for bulk products (so none are hollow shells) --------
const SEV_RANK = { HIGH: 3, MEDIUM: 2, LOW: 1 }
// Realistic per-type product blurbs. Every finding excerpt is a real substring
// of its document text, so the result view's 원문 근거 always matches.
const DOC_SAMPLES = {
  INVESTMENT: [
    { text: '본 상품은 최근 3년 연속 안정적인 수익률을 기록한 투자상품입니다. 연 5.8% 수준의 수익을 기대할 수 있으며, 시장 변동과 무관하게 꾸준한 성과를 지향합니다. 운용 보수와 판매 수수료가 부과됩니다.', findings: [
      { findingType: 'FRAMING', ruleCode: 'STABILITY_KEYWORD', severity: 'HIGH', statement: '안정성 표현이 원금보장으로 오인될 수 있습니다.', excerpt: '안정적인 수익률을 기록한', personas: ['FINANCIAL_BEGINNER', 'SENIOR'], ev: 'POLICY-003', reco: '원금 손실 가능성을 같은 영역에 명시하세요.' },
      { findingType: 'FRAMING', ruleCode: 'RETURN_FRAMING', severity: 'MEDIUM', statement: '기대 수익이 확정 수익처럼 표현되었습니다.', excerpt: '연 5.8% 수준의 수익을 기대', personas: ['FINANCIAL_BEGINNER'], ev: 'LAW-014', reco: '기대 수익이 미래 성과를 보장하지 않음을 표시하세요.' },
    ] },
    { text: '글로벌 우량 기업에 분산 투자하여 원금 손실 위험을 최소화한 상품입니다. 배당을 통해 월 지급식 인컴을 추구합니다. 환율 변동에 따라 손실이 발생할 수 있습니다.', findings: [
      { findingType: 'FRAMING', ruleCode: 'LOSS_SOFTENING', severity: 'HIGH', statement: '손실 가능성을 축소하는 표현입니다.', excerpt: '원금 손실 위험을 최소화한', personas: ['LOSS_SENSITIVE', 'FINANCIAL_BEGINNER'], ev: 'POLICY-003', reco: '손실 가능성을 축소 없이 명확히 표시하세요.' },
      { findingType: 'ACCESSIBILITY', ruleCode: 'COGNITIVE_ACCESSIBILITY', severity: 'LOW', statement: '전문용어로 인지 부담이 있습니다.', excerpt: '월 지급식 인컴', personas: ['LOW_LITERACY', 'SENIOR'], ev: null, reco: '핵심 용어에 쉬운 설명을 병기하세요.' },
    ] },
    { text: 'AI 기반 자산배분으로 시장을 이기는 성과를 목표로 합니다. 지난 분기 벤치마크 대비 초과 수익을 달성했습니다. 투자에는 원금 손실이 따를 수 있습니다.', findings: [
      { findingType: 'MISUNDERSTANDING', ruleCode: 'FORMAL_CONFIRMATION', severity: 'MEDIUM', statement: '과거 성과가 미래를 보장하는 것으로 오인될 수 있습니다.', excerpt: '초과 수익을 달성', personas: ['FINANCIAL_BEGINNER', 'DIGITAL_NOVICE'], ev: 'POLICY-003', reco: '성과 측정 기간과 기준을 명시하세요.' },
    ] },
  ],
  LOAN: [
    { text: '직장인이라면 누구나 최저 연 3.9% 금리로 간편하게 대출받을 수 있습니다. 복잡한 서류 없이 5분 만에 한도 조회가 가능합니다. 실제 금리는 신용도에 따라 달라집니다.', findings: [
      { findingType: 'FRAMING', ruleCode: 'RETURN_FRAMING', severity: 'MEDIUM', statement: '최저 금리가 일반 조건처럼 강조되었습니다.', excerpt: '최저 연 3.9% 금리', personas: ['FINANCIAL_BEGINNER', 'DIGITAL_NOVICE'], ev: 'LAW-014', reco: '최저금리 적용 조건과 평균금리를 함께 표시하세요.' },
      { findingType: 'OMISSION', ruleCode: 'COST_OMISSION', severity: 'MEDIUM', statement: '중도상환수수료 등 비용 안내가 누락되었습니다.', excerpt: '5분 만에 한도 조회', personas: ['LOW_LITERACY'], ev: 'POLICY-003', reco: '중도상환수수료와 연체이자 등 비용을 안내하세요.' },
    ] },
    { text: '생활비가 필요할 때 부담 없이 신청하세요. 낮은 금리로 이자 걱정을 덜어드립니다. 연체 시 높은 지연배상금이 부과될 수 있습니다.', findings: [
      { findingType: 'FRAMING', ruleCode: 'LOSS_SOFTENING', severity: 'MEDIUM', statement: '상환 부담을 과도하게 축소했습니다.', excerpt: '이자 걱정을 덜어드립니다', personas: ['LOSS_SENSITIVE'], ev: 'POLICY-003', reco: '상환 부담과 연체 위험을 함께 안내하세요.' },
    ] },
    { text: '전세자금을 안전하게 마련하세요. 정부 지원 연계로 우대 금리를 제공합니다. 보증 조건 미충족 시 대출이 제한될 수 있습니다.', findings: [
      { findingType: 'ACCESSIBILITY', ruleCode: 'COGNITIVE_ACCESSIBILITY', severity: 'LOW', statement: '우대 조건이 어려운 표현으로 안내되었습니다.', excerpt: '정부 지원 연계로 우대 금리', personas: ['LOW_LITERACY', 'SENIOR'], ev: null, reco: '우대 조건을 쉬운 표현으로 명시하세요.' },
    ] },
  ],
  SAVINGS: [
    { text: '연 4.0% 확정 금리를 제공하는 안심 정기예금입니다. 원금이 보장되어 안전하게 목돈을 모을 수 있습니다. 중도 해지 시 약정 이율이 적용되지 않습니다.', findings: [
      { findingType: 'MISUNDERSTANDING', ruleCode: 'STABILITY_KEYWORD', severity: 'HIGH', statement: '원금 보장 표현의 보호 범위가 불명확합니다.', excerpt: '원금이 보장되어 안전하게', personas: ['SENIOR', 'FINANCIAL_BEGINNER'], ev: 'POLICY-003', reco: '예금자보호 한도(5천만원) 기준을 명시하세요.' },
    ] },
    { text: '자유롭게 입출금하며 매일 이자가 쌓이는 파킹통장입니다. 조건 없이 높은 금리를 드립니다. 우대 금리는 일정 조건 충족 시 적용됩니다.', findings: [
      { findingType: 'FRAMING', ruleCode: 'RETURN_FRAMING', severity: 'MEDIUM', statement: '조건부 우대금리가 무조건인 것처럼 표현되었습니다.', excerpt: '조건 없이 높은 금리', personas: ['FINANCIAL_BEGINNER', 'DIGITAL_NOVICE'], ev: 'LAW-014', reco: '기본금리와 우대금리 조건을 구분해 표시하세요.' },
    ] },
    { text: '목표 금액까지 습관처럼 모으는 적금입니다. 만기까지 유지하면 최고 금리를 받을 수 있습니다. 중도 인출 시 이자가 삭감됩니다.', findings: [
      { findingType: 'MISUNDERSTANDING', ruleCode: 'FORMAL_CONFIRMATION', severity: 'LOW', statement: '최고 금리 달성 조건이 형식적으로 안내되었습니다.', excerpt: '최고 금리를 받을 수 있습니다', personas: ['FINANCIAL_BEGINNER'], ev: 'POLICY-003', reco: '최고금리 달성 조건을 명확히 안내하세요.' },
    ] },
  ],
}
let _fseq = 0
function mkFinding(docId, t) {
  _fseq += 1
  return {
    findingId: `FND-9${100 + _fseq}`, findingType: t.findingType, categoryCode: t.ruleCode, ruleCode: t.ruleCode,
    statement: t.statement, severity: t.severity,
    sourceReferences: [{ documentId: docId, page: 1, excerpt: t.excerpt }],
    affectedPersonaCodes: t.personas,
    evidenceReferences: t.ev ? [{ evidenceDocumentId: t.ev, excerpt: '근거 준칙 발췌 (데모)', sourceType: 'INTERNAL_POLICY' }] : [],
    recommendation: t.reco,
  }
}
const GF_TYPES = [
  { actionType: 'WARNING', label: '원금 손실 가능성 있음', placement: '상품 상세 상단' },
  { actionType: 'LABEL', label: '과거 수익은 미래 성과를 보장하지 않습니다', placement: '수익률 표기 옆' },
  { actionType: 'QUESTION', label: '위험 고지 확인 후 가입 진행', placement: '가입 마지막 단계' },
  { actionType: 'COMPARISON', label: '총비용·수수료 상세 고지', placement: '비용 안내 섹션' },
  { actionType: 'WARNING', label: '예금자보호 한도(5천만원) 안내', placement: '금리 표기 하단' },
]
function buildBulk() {
  const docs = [], analyses = [], reviews = [], risk = [], guardfit = []
  const typeCount = {}
  EXTRA_PRODUCTS.forEach((p, i) => {
    const idn = 100 + i
    const docId = `PDOC-9${idn}`
    const samples = DOC_SAMPLES[p.productType]
    const k = (typeCount[p.productType] = (typeCount[p.productType] ?? -1) + 1)
    const sample = samples[k % samples.length]
    docs.push({
      documentId: docId, productId: p.productId, fileName: `${p.name}_설명서.pdf`,
      mediaType: 'application/pdf', fileSize: 1800000 + i * 4096,
      storageKey: `local://uploads/${docId}/seed.pdf`, checksumSha256: `seed${idn}`.padEnd(64, '0'),
      extractStatus: 'READY', extractMethod: 'pdf-text',
      rawExtractedText: sample.text, verifiedText: sample.text,
      confirmed: true, confirmedBy: 'USER-PM-001', confirmedAt: p.createdAt,
      attemptCount: 1, error: null, clockDriven: false,
    })
    const findings = sample.findings.map((f) => mkFinding(docId, f))
    const analysisId = `ANL-9${idn}`
    analyses.push({
      analysisId, productDocumentId: docId, productId: p.productId,
      status: 'COMPLETED', riskScore: computeRiskScore(findings), providerType: 'MOCK',
      scenarioCode: 'GUARANTEE_MISUNDERSTANDING_HIGH', evidenceDocumentIds: ['POLICY-003'],
      personaIds: ['PERSONA-FIN-BEGINNER', 'PERSONA-SENIOR'], redTeamPackId: 51,
      findings, attemptCount: 1, error: null, clockDriven: false, createdAt: p.createdAt,
    })
    const maxSev = findings.reduce((a, f) => (SEV_RANK[f.severity] > SEV_RANK[a] ? f.severity : a), 'LOW')
    const reviewId = `REV-9${idn}`
    if (p.status === 'IN_REVIEW') {
      reviews.push({ reviewId, analysisId, productId: p.productId, productName: p.name, maxSeverity: maxSev, status: 'PENDING', submittedBy: 'USER-PM-001', ownerName: '박서준 대리', submittedAt: p.createdAt, submissionComment: '검토 요청', reviewerId: null, decidedAt: null, comment: null, selectedFindingIds: [] })
    } else if (p.status === 'APPROVED') {
      const hi = findings.find((f) => f.severity === 'HIGH') || findings[0]
      const rpId = `RISK-9${idn}`
      reviews.push({ reviewId, analysisId, productId: p.productId, productName: p.name, maxSeverity: maxSev, status: 'APPROVED', submittedBy: 'USER-PM-001', ownerName: '박서준 대리', submittedAt: p.createdAt, submissionComment: '검토 요청', reviewerId: 'USER-CR-001', decidedAt: p.createdAt, comment: '승인 처리', selectedFindingIds: [hi.findingId], riskPatternIds: [rpId] })
      risk.push({ riskPatternId: rpId, title: riskPatternTitle(hi), findingStatement: hi.statement, severity: hi.severity, ruleCode: hi.ruleCode, affectedPersonaCodes: hi.affectedPersonaCodes, sourceFindingId: hi.findingId, sourceReviewId: reviewId, sourceAnalysisId: analysisId, status: 'ACTIVE', createdAt: p.createdAt, sourceExcerpt: hi.sourceReferences[0]?.excerpt || '', recommendation: hi.recommendation })
      const at = GF_TYPES[i % GF_TYPES.length]
      guardfit.push({ actionId: `GFA-9${idn}`, riskPatternId: rpId, actionType: at.actionType, label: at.label, placement: at.placement, required: i % 3 !== 0, status: 'APPROVED', createdBy: 'USER-CR-001', updatedBy: 'USER-CR-001', updatedAt: p.createdAt })
    } else if (p.status === 'NEEDS_FIX') {
      reviews.push({ reviewId, analysisId, productId: p.productId, productName: p.name, maxSeverity: maxSev, status: 'REJECTED', submittedBy: 'USER-PM-001', ownerName: '박서준 대리', submittedAt: p.createdAt, submissionComment: '검토 요청', reviewerId: 'USER-CR-001', decidedAt: p.createdAt, comment: '원금 손실 가능성 문구 보완이 필요합니다.', selectedFindingIds: [] })
    }
  })
  return { docs, analyses, reviews, risk, guardfit }
}
const BULK = INCLUDE_BULK_SEED ? buildBulk() : { docs: [], analyses: [], reviews: [], risk: [], guardfit: [] }

export const PRODUCT_DOCUMENTS = [
  GOLDEN_DOCUMENT,
  {
    documentId: 'PDOC-PM-B-001', productId: PM_B_PRODUCT.productId, fileName: 'PM_B_상품설명서.pdf',
    mediaType: 'application/pdf', fileSize: 102400,
    storageKey: 'local://uploads/PDOC-PM-B-001/seed.pdf',
    checksumSha256: 'b'.repeat(64), extractStatus: 'READY', extractMethod: 'pdf-text',
    rawExtractedText: 'PM B 상품은 시장 상황에 따라 원금 손실이 발생할 수 있습니다.',
    verifiedText: 'PM B 상품은 시장 상황에 따라 원금 손실이 발생할 수 있습니다.',
    confirmed: true, confirmedBy: 'USER-PM-002', confirmedAt: '2026-09-03T10:02:00+09:00',
    attemptCount: 1, error: null, clockDriven: false,
  },
  {
    documentId: 'PDOC-001', productId: 'PROD-001', fileName: '스마트인컴_상품설명서.pdf',
    mediaType: 'application/pdf', fileSize: 2516582,
    storageKey: 'local://uploads/PDOC-001/seed.pdf',
    checksumSha256: 'a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00',
    extractStatus: 'READY',
    rawExtractedText: '최근 안정적인 수익률을 기록한 투자상품입니다. 운용 보수가 부과됩니다.',
    verifiedText: '최근 안정적인 수익률을 기록했습니다. 원금 손실 가능성이 있습니다. 운용 보수가 부과됩니다.',
    confirmed: true, confirmedBy: 'USER-PM-001', confirmedAt: '2026-09-02T09:02:00+09:00',
    attemptCount: 1, error: null, clockDriven: false,
  },
  {
    documentId: 'PDOC-002', productId: 'PROD-002', fileName: '글로벌배당ETF_설명서.pptx',
    mediaType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', fileSize: 4210562,
    storageKey: 'local://uploads/PDOC-002/seed.pptx',
    checksumSha256: 'b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff0011',
    extractStatus: 'EXTRACTING',
    rawExtractedText: null, verifiedText: null, confirmed: false,
    attemptCount: 1, error: null, clockDriven: false,
  },
  ...BULK.docs,
].filter((item) => INCLUDE_BULK_SEED || typeof item.documentId === 'number' || item.documentId === 'PDOC-PM-B-001')

export const ANALYSES = [
  { ...GOLDEN_ANALYSIS, ...GOLDEN_OUTCOME },
  {
    analysisId: 'ANL-PM-B-001', productDocumentId: 'PDOC-PM-B-001', productId: PM_B_PRODUCT.productId,
    status: 'COMPLETED', riskScore: 60, providerType: 'MOCK',
    scenarioCode: 'GUARANTEE_MISUNDERSTANDING_HIGH', evidenceDocumentIds: [21],
    personaIds: [41], redTeamPackId: 51,
    findings: [{
      ...DEMO_FINDINGS[0],
      findingId: 'FND-PM-B-001',
      sourceReferences: [{ documentId: 'PDOC-PM-B-001', page: 1, excerpt: '원금 손실이 발생할 수 있습니다.' }],
    }],
    attemptCount: 1, error: null, clockDriven: false, createdAt: '2026-09-03T10:05:00+09:00',
  },
  {
    analysisId: 'ANL-001', productDocumentId: 'PDOC-001', productId: 'PROD-001',
    status: 'COMPLETED', riskScore: 82, providerType: 'MOCK',
    scenarioCode: 'GUARANTEE_MISUNDERSTANDING_HIGH',
    evidenceDocumentIds: ['POLICY-003'],
    personaIds: ['PERSONA-FIN-BEGINNER', 'PERSONA-SENIOR'],
    redTeamPackId: 51,
    findings: DEMO_FINDINGS, attemptCount: 1, error: null, clockDriven: false,
    createdAt: '2026-09-02T09:03:00+09:00',
  },
  {
    analysisId: 'ANL-002', productDocumentId: 'PDOC-002', productId: 'PROD-002',
    status: 'RUNNING', riskScore: null, providerType: 'MOCK',
    scenarioCode: 'COST_OMISSION_MEDIUM', progress: 65,
    evidenceDocumentIds: ['POLICY-003'], personaIds: ['PERSONA-FIN-BEGINNER'],
    redTeamPackId: 51,
    findings: [], attemptCount: 1, error: null, clockDriven: false,
    createdAt: '2026-09-02T09:20:00+09:00',
  },
  ...BULK.analyses,
].filter((item) => INCLUDE_BULK_SEED || typeof item.analysisId === 'number' || item.analysisId === 'ANL-PM-B-001')

export const REVIEWS = [
  GOLDEN_REVIEW,
  {
    reviewId: 'REV-PM-B-001', analysisId: 'ANL-PM-B-001', productId: PM_B_PRODUCT.productId,
    productName: PM_B_PRODUCT.name, maxSeverity: 'HIGH', status: 'PENDING',
    submittedBy: 'USER-PM-002', ownerName: '김민지 과장',
    submittedAt: '2026-09-03T10:10:00+09:00', submissionComment: 'PM B 검토 요청',
    reviewerId: null, decidedAt: null, comment: null, selectedFindingIds: [],
  },
  {
    reviewId: 'REV-001', analysisId: 'ANL-001', productId: 'PROD-001', productName: '스마트 인컴 투자상품',
    maxSeverity: 'HIGH', status: 'PENDING',
    submittedBy: 'USER-PM-001', ownerName: '박서준 대리',
    submittedAt: '2026-09-02T09:10:00+09:00', submissionComment: '고위험 Finding 검토 요청',
    reviewerId: null, decidedAt: null, comment: null, selectedFindingIds: [],
  },
  ...BULK.reviews,
].filter((item) => INCLUDE_BULK_SEED || typeof item.reviewId === 'number' || item.reviewId === 'REV-PM-B-001')

export const GROUND_TRUTH_FACTS = [
  ...GOLDEN_FACTS,
  {
    factId: 'FACT-PM-B-001', documentId: 'PDOC-PM-B-001', factType: 'PRINCIPAL_LOSS',
    label: '원금손실 가능성', value: '시장 상황에 따라 원금 손실 가능',
    importance: 'CRITICAL', verificationStatus: 'VERIFIED',
    sourceReferences: [{ evidenceDocumentId: 21, page: 1, excerpt: '원금 손실이 발생할 수 있습니다.' }],
    extractionSource: 'MOCK_FIXTURE', verifiedBy: 'USER-PM-002', verifiedAt: '2026-09-03T10:03:00+09:00',
  },
]

// Pre-approved pattern history so the reviewer library and PM guardfit view
// are populated on first load.
export const RISK_PATTERNS = [
  {
    riskPatternId: 'RISK-900', title: '수익 프레이밍', findingStatement: '기대 수익이 확정 수익처럼 전달되어 미래 성과를 보장하는 것으로 오인될 수 있습니다.', severity: 'HIGH', ruleCode: 'RETURN_FRAMING',
    affectedPersonaCodes: ['FINANCIAL_BEGINNER'], sourceFindingId: 'FND-900',
    sourceReviewId: 'REV-900', sourceAnalysisId: 'ANL-900', status: 'ACTIVE', createdAt: '2026-09-01T16:00:00+09:00',
    sourceExcerpt: '연 5% 수익을 기대할 수 있는 상품입니다.', recommendation: '과거 수익은 미래 성과를 보장하지 않는다는 문구를 인접 표시하세요.',
  },
  {
    riskPatternId: 'RISK-901', title: '비용 정보 누락', findingStatement: '총비용과 수수료 정보가 수익 표현과 인접한 위치에 충분히 안내되지 않았습니다.', severity: 'MEDIUM', ruleCode: 'COST_OMISSION',
    affectedPersonaCodes: ['FINANCIAL_BEGINNER', 'LOW_LITERACY'], sourceFindingId: 'FND-901',
    sourceReviewId: 'REV-900', sourceAnalysisId: 'ANL-900', status: 'ACTIVE', createdAt: '2026-09-01T16:05:00+09:00',
    sourceExcerpt: '수수료 안내', recommendation: '총비용 예시를 수익 표현과 함께 제시하세요.',
  },
  ...BULK.risk,
].filter((item) => INCLUDE_BULK_SEED || typeof item.riskPatternId === 'number')

export const GUARDFIT_ACTIONS = [
  {
    actionId: 'GFA-900', riskPatternId: 'RISK-900', actionType: 'WARNING',
    label: '수익률은 미래 성과를 보장하지 않습니다', placement: '상품 상세 상단',
    required: true, status: 'APPROVED', createdBy: 'USER-CR-001', updatedBy: 'USER-CR-001',
    updatedAt: '2026-09-01T16:30:00+09:00',
  },
  {
    actionId: 'GFA-901', riskPatternId: 'RISK-901', actionType: 'LABEL',
    label: '총비용 예시 표기', placement: '수익 표현 인접', required: false,
    status: 'DRAFT', createdBy: 'USER-CR-001', updatedBy: null, updatedAt: null,
  },
  {
    actionId: 'GFA-902', riskPatternId: 'RISK-901', actionType: 'COMPARISON',
    label: '총비용·수수료 예시를 수익 표현과 함께 고지', placement: '수익률 표기 하단', required: true,
    status: 'APPROVED', createdBy: 'USER-CR-001', updatedBy: 'USER-CR-001', updatedAt: '2026-09-01T16:40:00+09:00',
  },
  {
    actionId: 'GFA-903', riskPatternId: 'RISK-900', actionType: 'QUESTION',
    label: '원금 손실 가능성 확인 후 가입 진행', placement: '가입 마지막 단계', required: true,
    status: 'APPROVED', createdBy: 'USER-CR-001', updatedBy: 'USER-CR-001', updatedAt: '2026-09-01T16:45:00+09:00',
  },
  ...BULK.guardfit,
].filter((item) => INCLUDE_BULK_SEED || typeof item.actionId === 'number')

const CORE_AUDIT = [
  { auditId: 'AUD-900', resourceType: 'REVIEW', resourceId: 'REV-900', action: 'REVIEW_APPROVED', actorId: 'USER-CR-001', traceId: 'trc-20260901-0015', createdAt: '2026-09-01T16:00:00+09:00' },
  { auditId: 'AUD-901', resourceType: 'GUARDFIT_ACTION', resourceId: 'GFA-900', action: 'ACTION_APPROVED', actorId: 'USER-CR-001', traceId: 'trc-20260901-0022', createdAt: '2026-09-01T16:30:00+09:00' },
]
const AUDIT_EVENTS = [
  ['REVIEW', 'REVIEW_SUBMITTED', 'USER-PM-001'], ['REVIEW', 'REVIEW_APPROVED', 'USER-CR-001'],
  ['REVIEW', 'REVIEW_REJECTED', 'USER-CR-001'], ['GUARDFIT_ACTION', 'ACTION_CREATED', 'USER-CR-001'],
  ['GUARDFIT_ACTION', 'ACTION_APPROVED', 'USER-CR-001'], ['GUARDFIT_ACTION', 'ACTION_CREATED', 'USER-CR-001'],
]
const EXTRA_AUDIT = Array.from({ length: 20 }, (_, i) => {
  const [rt, act, actor] = AUDIT_EVENTS[i % AUDIT_EVENTS.length]
  const day = String(28 - (i % 20)).padStart(2, '0')
  const mm = String(10 + (i % 40)).padStart(2, '0')
  return {
    auditId: `AUD-${800 - i}`,
    resourceType: rt,
    resourceId: `${rt === 'REVIEW' ? 'REV' : 'GFA'}-${700 + i}`,
    action: act,
    actorId: actor,
    traceId: `trc-202608${day}-${String(1000 + i).slice(-4)}`,
    createdAt: `2026-08-${day}T${mm}:12:00+09:00`,
  }
})
export const AUDIT_LOGS = [GOLDEN_AUDIT_LOG, ...(INCLUDE_BULK_SEED ? [...CORE_AUDIT, ...EXTRA_AUDIT] : [])]
