// =============================================================================
// In-memory mock backend. Stands in for the Spring API so the frontend runs
// standalone (Interface First). Same JSON contracts the real backend returns.
//
// Async state is Clock-based: GET is read-only and never mutates state; status
// transitions are computed from timestamps (Mock 데이터 명세서 §8).
// =============================================================================
import { ApiError } from '../errors.js'
import * as seed from './seed.js'
import {
  NORMAL_SCENARIOS,
  ERROR_SCENARIOS,
  computeRiskScore,
  isErrorScenario,
} from './scenarios.js'
import { orchestrateMockAnalysis } from '../../lib/analyze.js'

const SEV_RANK = { HIGH: 3, MEDIUM: 2, LOW: 1 }
const clone = (v) => JSON.parse(JSON.stringify(v))
const nowMs = () => Date.now()
const iso = (ms = Date.now()) => new Date(ms).toISOString()
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

let seq = 1000
const nextId = () => ++seq
const traceId = () => `trc-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-${String(++seq).slice(-4)}`
const sameId = (left, right) => String(left) === String(right)
const normalizedText = (value) => String(value || '').trim().replace(/\s+/g, ' ')
async function analysisInputHash(inputSnapshot) {
  const bytes = new TextEncoder().encode(requestFingerprint(inputSnapshot))
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

function createStore() {
  return {
    users: clone(seed.USERS),
    personaTemplates: clone(seed.PERSONA_TEMPLATES),
    redTeamPacks: clone(seed.RED_TEAM_PACKS),
    evidenceDocuments: clone(seed.EVIDENCE_DOCUMENTS),
    groundTruthFacts: clone(seed.GROUND_TRUTH_FACTS),
    products: clone(seed.PRODUCTS),
    documents: clone(seed.PRODUCT_DOCUMENTS),
    analyses: clone(seed.ANALYSES),
    reviews: clone(seed.REVIEWS),
    riskPatterns: clone(seed.RISK_PATTERNS),
    guardfitActions: clone(seed.GUARDFIT_ACTIONS),
    auditLogs: clone(seed.AUDIT_LOGS),
    idempotency: new Map(),
  }
}

// Persist the whole mock store to localStorage so a page refresh keeps live
// data (created products, uploaded docs, analyses, decisions). In Node (smoke
// tests) localStorage is absent, so it stays a fresh in-memory store.
const LS_KEY = 'guardlab.store.v1'
const STORE_SCHEMA_VERSION = 6
const hasLS = typeof localStorage !== 'undefined'
function persist() {
  if (!hasLS) return
  try {
    const { idempotency, ...rest } = store
    localStorage.setItem(LS_KEY, JSON.stringify({ ...rest, __seq: seq, __schemaVersion: STORE_SCHEMA_VERSION }))
  } catch {}
}
function loadStore() {
  if (hasLS) {
    try {
      const raw = localStorage.getItem(LS_KEY)
      if (raw) {
        const d = JSON.parse(raw)
        if (d.__schemaVersion !== STORE_SCHEMA_VERSION) {
          localStorage.removeItem(LS_KEY)
          return createStore()
        }
        if (typeof d.__seq === 'number') seq = d.__seq
        const { __seq, __schemaVersion, ...rest } = d
        return { ...rest, idempotency: new Map() }
      }
    } catch {}
  }
  return createStore()
}
let store = loadStore()
for (const analysis of store.analyses) {
  if (analysis.status === 'COMPLETED') finalizeAnalysisSnapshot(analysis, analysis.completedAt || analysis.createdAt || iso())
}
if (hasLS && typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', (event) => {
    if (event.storageArea !== localStorage || event.key !== LS_KEY || event.newValue == null) return
    try {
      const d = JSON.parse(event.newValue)
      if (!d || typeof d !== 'object' || Array.isArray(d) || d.__schemaVersion !== STORE_SCHEMA_VERSION) return
      const { __seq, __schemaVersion, ...rest } = d
      const collections = [
        'users', 'personaTemplates', 'redTeamPacks', 'evidenceDocuments',
        'groundTruthFacts', 'products', 'documents', 'analyses', 'reviews',
        'riskPatterns', 'guardfitActions', 'auditLogs',
      ]
      if (!Number.isFinite(__seq) || !collections.every((key) => Array.isArray(rest[key]))) return

      const nextStore = { ...rest, idempotency: store.idempotency }
      const previousStore = store
      const previousSeq = seq
      store = nextStore
      seq = __seq
      try {
        for (const analysis of store.analyses) {
          if (analysis.status === 'COMPLETED') finalizeAnalysisSnapshot(analysis, analysis.completedAt || analysis.createdAt || iso())
        }
      } catch (error) {
        store = previousStore
        seq = previousSeq
        throw error
      }
    } catch {}
  })
}
export function resetStore() {
  store = createStore()
  seq = 1000
  for (const analysis of store.analyses) {
    if (analysis.status === 'COMPLETED') finalizeAnalysisSnapshot(analysis, analysis.completedAt || analysis.createdAt || iso())
  }
  if (hasLS) localStorage.removeItem(LS_KEY)
}

// ---- auth / rbac -----------------------------------------------------------
function requireAuth(auth) {
  if (!auth || !auth.userId || !auth.role) {
    throw new ApiError({ status: 401, errorCode: 'DEMO_IDENTITY_MISMATCH', message: '데모 사용자 정보가 없습니다.' })
  }
  const user = store.users.find((u) => u.id === auth.userId)
  if (!user || user.role !== auth.role) {
    throw new ApiError({ status: 401, errorCode: 'DEMO_IDENTITY_MISMATCH', message: '사용자와 역할이 일치하지 않습니다.' })
  }
  if (!user.active) {
    throw new ApiError({ status: 403, errorCode: 'USER_INACTIVE', message: '비활성 사용자입니다.' })
  }
  return user
}
function requireRole(user, role) {
  if (user.role !== role) {
    throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '이 작업을 수행할 권한이 없습니다.' })
  }
}
function productForDocument(document) {
  return document && store.products.find((product) => sameId(product.productId, document.productId))
}
function productForFact(fact) {
  const document = fact && store.documents.find((item) => sameId(item.documentId, fact.documentId))
  return productForDocument(document)
}
function productForAnalysis(analysis) {
  return analysis && store.products.find((product) => sameId(product.productId, analysis.productId))
}
function productForReview(review) {
  const analysis = review && store.analyses.find((item) => sameId(item.analysisId, review.analysisId))
  return productForAnalysis(analysis)
}
function latestAnalysisForProduct(productId) {
  return store.analyses
    .filter((analysis) => sameId(analysis.productId, productId))
    .reduce((latest, analysis) => {
      if (!latest) return analysis
      const createdAt = String(analysis.createdAt || '')
      const latestCreatedAt = String(latest.createdAt || '')
      if (createdAt !== latestCreatedAt) return createdAt > latestCreatedAt ? analysis : latest
      const analysisId = String(analysis.analysisId)
      const latestAnalysisId = String(latest.analysisId)
      const bothNumeric = /^\d+$/.test(analysisId) && /^\d+$/.test(latestAnalysisId)
      const analysisIsLater = bothNumeric
        ? Number(analysisId) > Number(latestAnalysisId)
        : analysisId > latestAnalysisId
      return analysisIsLater ? analysis : latest
    }, null)
}
function requireLatestAnalysis(analysis) {
  const latest = analysis && latestAnalysisForProduct(analysis.productId)
  if (latest && !sameId(latest.analysisId, analysis.analysisId)) {
    throw new ApiError({
      status: 409,
      errorCode: 'STALE_ANALYSIS_REVISION',
      message: '최신 분석만 검토 요청하거나 결정할 수 있습니다.',
    })
  }
}
function requirePmOwnership(user, product) {
  if (user.role === 'PRODUCT_MANAGER' && product?.ownerId !== user.id) {
    throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '소유한 상품의 리소스만 이용할 수 있습니다.' })
  }
}
function requestFingerprint(value) {
  const canonicalize = (item) => {
    if (Array.isArray(item)) return item.map(canonicalize)
    if (item && typeof item === 'object') {
      return Object.fromEntries(
        Object.keys(item)
          .filter((key) => item[key] !== undefined)
          .sort()
          .map((key) => [key, canonicalize(item[key])]),
      )
    }
    return item
  }
  return JSON.stringify(canonicalize(value))
}
function captureAnalysisInput(doc, verifiedFacts, evidenceIds, personaIds, pack, providerType, modelVersion, scenarioCode) {
  const byId = (left, right) => String(left).localeCompare(String(right))
  return {
    sourceDocument: {
      documentId: doc.documentId,
      fileName: doc.fileName,
      confirmedSourceText: normalizedText(doc.verifiedText),
    },
    groundTruthFacts: clone(verifiedFacts).sort((left, right) => byId(left.factId, right.factId)),
    evidenceDocuments: evidenceIds
      .map((id) => store.evidenceDocuments.find((item) => sameId(item.documentId, id)))
      .filter(Boolean)
      .map(clone)
      .sort((left, right) => byId(left.documentId, right.documentId)),
    personas: personaIds
      .map((id) => store.personaTemplates.find((item) => sameId(item.personaId, id)))
      .filter(Boolean)
      .map(clone)
      .sort((left, right) => byId(left.personaId, right.personaId)),
    redTeamPack: clone(pack),
    provider: { providerType, modelVersion },
    scenarioCode,
  }
}
async function idempotent(user, operation, key, fingerprint, produce) {
  const scopedKey = key && requestFingerprint([user.id, operation, key])
  if (scopedKey && store.idempotency.has(scopedKey)) {
    const record = store.idempotency.get(scopedKey)
    if (record.fingerprint !== fingerprint) {
      throw new ApiError({
        status: 409,
        errorCode: 'IDEMPOTENCY_KEY_REUSED',
        message: '동일한 멱등성 키가 다른 요청에 사용되었습니다.',
      })
    }
    return clone(record.response || await record.pending)
  }
  if (!scopedKey) return produce()

  const record = { fingerprint, response: null, pending: null }
  record.pending = Promise.resolve().then(produce)
  store.idempotency.set(scopedKey, record)
  try {
    const response = await record.pending
    record.response = clone(response)
    record.pending = null
    return response
  } catch (error) {
    if (store.idempotency.get(scopedKey) === record) store.idempotency.delete(scopedKey)
    throw error
  }
}

// ---- document extraction ----------------------------------------------------
// - Mock 기본 경로: 사전 제작 추출 텍스트 사용
// - 실험 경로: 클라이언트 추출 결과를 READY/FAILED로 반영
function viewDocument(doc) {
  const d = clone(doc)
  delete d._plan
  return d
}
export function ingestExtraction(documentId, result) {
  const doc = store.documents.find((x) => sameId(x.documentId, documentId))
  if (!doc) return
  if (result.failed) {
    doc.extractStatus = 'FAILED'
    doc.error = {
      errorCode: result.errorCode || 'EXTRACTION_ERROR',
      message: result.message || '추출 오류',
      retryable: !!result.retryable,
    }
  } else {
    doc.rawExtractedText = result.text
    doc.verifiedText = result.text
    doc.extractStatus = 'READY'
    doc.extractMethod = result.method || 'text'
    doc.error = null
  }
  persist()
}

// 로컬 LLM 분석 결과를 분석 레코드에 주입(클라이언트 analyze 파이프라인이 호출). GET은 read-only.
export function ingestAnalysis(analysisId, result) {
  const a = store.analyses.find((x) => sameId(x.analysisId, analysisId))
  if (!a) return
  a.clockDriven = false
  delete a._plan
  if (result.failed) {
    a.status = 'FAILED'
    a.error = { errorCode: result.errorCode || 'PROVIDER_RESPONSE_INVALID', message: result.message || '분석 실패', retryable: !!result.retryable }
  } else {
    a.status = 'COMPLETED'
    a.riskScore = result.riskScore
    const reportedProvider = result.providerType || result.provider
    const reportedModel = result.modelVersion || result.model
    const isLocalInjection = a.providerType === 'LOCAL_OLLAMA' || a.inputSnapshot?.provider?.providerType === 'LOCAL_OLLAMA'
    a.providerType = isLocalInjection && reportedProvider === 'MOCK'
      ? 'LOCAL_OLLAMA'
      : reportedProvider || 'LOCAL_OLLAMA'
    a.modelVersion = isLocalInjection && reportedModel === 'DETERMINISTIC_FIXTURE_V1'
      ? 'qwen2.5:7b-instruct'
      : reportedModel || 'qwen2.5:7b-instruct'
    if (a.inputSnapshot) {
      a.inputSnapshot.provider = { providerType: a.providerType, modelVersion: a.modelVersion }
    }
    a.ragGrounding = result.grounding || [] // RAG로 검색된 근거(문서·유사도)
    a.findings = (result.findings || []).map((f, i) => ({
      findingId: `FND-L${i + 1}-${analysisId}`,
      ...f,
      sourceReferences: (f.sourceReferences || []).map((reference) => ({
        ...reference,
        documentId: a.productDocumentId,
      })),
    }))
    a.error = null
    finalizeAnalysisSnapshot(a)
  }
  persist()
}

// ---- clock-driven analysis --------------------------------------------------
function planAnalysis(a, scenarioCode) {
  const start = nowMs()
  a.clockDriven = true
  a.scenarioCode = scenarioCode
  if (isErrorScenario(scenarioCode)) {
    const s = ERROR_SCENARIOS[scenarioCode]
    a._plan = {
      createdAt: start,
      runningAt: start + s.schedule.createdToRunningMs,
      doneAt: start + s.schedule.createdToRunningMs + s.schedule.runningToFailedMs,
      terminal: 'FAILED',
      error: s.error,
      recoverScenario: s.kind === 'FAIL_THEN_SUCCESS' ? s.successScenario : null,
    }
  } else {
    const s = NORMAL_SCENARIOS[scenarioCode] || NORMAL_SCENARIOS.GUARANTEE_MISUNDERSTANDING_HIGH
    const facts = a.inputSnapshot?.groundTruthFacts || []
    let outcome = orchestrateMockAnalysis({
      analysisId: a.analysisId,
      productDocumentId: a.productDocumentId,
      personaIds: a.personaIds,
      groundTruthFactIds: facts.map((fact) => fact.factId),
      evidenceDocumentIds: a.evidenceDocumentIds,
      sourceText: a.inputSnapshot?.sourceDocument?.confirmedSourceText || '',
    })
    if (scenarioCode === 'NO_FINDING') {
      outcome = { ...outcome, redTeamResults: [], findings: [], vulnerabilityPatterns: [], guardFitSuggestions: [] }
    }
    const findings = outcome.findings
    a._plan = {
      createdAt: start,
      runningAt: start + s.schedule.createdToRunningMs,
      doneAt: start + s.schedule.createdToRunningMs + s.schedule.runningToCompletedMs,
      terminal: 'COMPLETED',
      findings,
      outcome,
      riskScore: computeRiskScore(findings),
      error: null,
    }
  }
}
function settleAnalysis(a) {
  // Persist terminal result into the record once the clock passes doneAt.
  if (!a.clockDriven || !a._plan) return
  const t = nowMs()
  const p = a._plan
  if (t < p.doneAt) return
  if (p.terminal === 'COMPLETED') {
    a.status = 'COMPLETED'
    a.findings = p.findings
    Object.assign(a, p.outcome || {})
    a.riskScore = p.riskScore
    a.error = null
    finalizeAnalysisSnapshot(a, iso(p.doneAt))
  } else {
    a.status = 'FAILED'
    a.findings = []
    a.riskScore = null
    a.error = p.error
  }
}
function viewAnalysis(a) {
  const v = clone(a)
  if (a.clockDriven && a._plan) {
    const t = nowMs()
    const p = a._plan
    if (t < p.runningAt) {
      v.status = 'CREATED'
      v.stage = 'PREPARING'
      v.progress = 0
    } else if (t < p.doneAt) {
      v.status = 'RUNNING'
      const frac = (t - p.runningAt) / (p.doneAt - p.runningAt)
      v.progress = Math.min(95, Math.max(10, Math.round(frac * 100)))
      const stages = ['PERSONA_SIMULATION', 'RED_TEAM_ANALYSIS', 'EVALUATING', 'SCORING', 'AGGREGATING']
      v.stage = stages[Math.min(stages.length - 1, Math.floor(frac * stages.length))]
      v.error = null
    } else {
      settleAnalysis(a)
      v.status = a.status
      v.riskScore = a.riskScore
      v.error = a.error
      v.progress = a.status === 'COMPLETED' ? 100 : v.progress
      v.stage = a.status === 'COMPLETED' ? 'COMPLETED' : null
    }
  }
  delete v._plan
  delete v.findings
  return v
}

function ensureAnalysisInputSnapshot(a) {
  if (a.inputSnapshot) return a.inputSnapshot
  const doc = store.documents.find((item) => sameId(item.documentId, a.productDocumentId))
  const facts = store.groundTruthFacts.filter((fact) =>
    sameId(fact.documentId, a.productDocumentId) && fact.verificationStatus === 'VERIFIED')
  const pack = store.redTeamPacks.find((item) => sameId(item.redTeamPackId, a.redTeamPackId)) || null
  a.inputSnapshot = captureAnalysisInput(
    doc || { documentId: a.productDocumentId, fileName: '', verifiedText: '' },
    facts,
    a.evidenceDocumentIds || [],
    a.personaIds || [],
    pack,
    a.providerType || 'MOCK',
    a.modelVersion || (a.providerType === 'LOCAL_OLLAMA' ? 'qwen2.5:7b-instruct' : 'DETERMINISTIC_FIXTURE_V1'),
    a.scenarioCode || null,
  )
  return a.inputSnapshot
}
function finalizeAnalysisSnapshot(a, generatedAt = iso()) {
  if (a.resultSnapshot) return
  const input = ensureAnalysisInputSnapshot(a)
  const grounding = (a.ragGrounding && a.ragGrounding.length)
    ? a.ragGrounding.map((item) => ({
        documentId: item.documentId,
        title: item.title,
        sourceType: item.sourceType,
        score: item.score,
      }))
    : input.evidenceDocuments.map((item) => ({ documentId: item.documentId, title: item.title }))
  a.completedAt = generatedAt
  a.resultSnapshot = {
    contractVersion: '1.0',
    analysisId: a.analysisId,
    status: 'COMPLETED',
    riskScore: a.riskScore,
    inputHash: a.inputHash,
    scoreBreakdown: {
      severityBase: a.findings?.some((finding) => finding.severity === 'HIGH') ? 60 : a.findings?.length ? 35 : 0,
      personaBonus: Math.min(15, 5 * new Set((a.findings || []).flatMap((finding) => finding.affectedPersonaCodes || [])).size),
      ruleBonus: Math.min(12, 3 * new Set((a.findings || []).map((finding) => finding.ruleCode || finding.aiDetail?.ruleCode).filter(Boolean)).size),
      groundingBonus: a.findings?.some((finding) => finding.severity === 'HIGH') ? 6 : 0,
      scorePolicyVersion: '1.0',
    },
    sourceDocument: {
      documentId: input.sourceDocument.documentId,
      fileName: input.sourceDocument.fileName,
    },
    groundingDocuments: grounding,
    experimentSummary: {
      repetitionCountPerPersona: 3,
      selectedPersonaCount: input.personas.length,
      totalRunCount: a.personaRuns?.length || 0,
      stabilityThreshold: 0.67,
    },
    personaRuns: clone(a.personaRuns || []),
    personaSummaries: input.personas.map((persona) => {
      const runs = (a.personaRuns || []).filter((run) => sameId(run.personaId, persona.personaId))
      return {
        personaCode: runs[0]?.personaCode || persona.code || String(persona.personaId),
        averageComprehensionScore: runs.length
          ? Math.round(runs.reduce((sum, run) => sum + (run.questionResults?.[0]?.score || 0), 0) / runs.length)
          : 0,
        runCount: runs.length,
        topMisunderstandingCodes: [...new Set(runs.flatMap((run) => run.misunderstandingCandidates || []).map((item) => item.categoryCode))],
      }
    }),
    redTeamResults: clone(a.redTeamResults || []),
    redTeamSummary: {
      checkedRuleCount: input.redTeamPack?.rules?.length || 0,
      triggeredRuleCount: (a.redTeamResults || []).filter((item) => item.triggered).length,
      triggeredRuleCodes: (a.redTeamResults || []).filter((item) => item.triggered).map((item) => item.ruleCode),
    },
    findings: clone(a.findings || []),
    vulnerabilityPatterns: clone(a.vulnerabilityPatterns || []),
    guardFitSuggestions: clone(a.guardFitSuggestions || []),
    groundTruthFacts: clone(input.groundTruthFacts),
    provenance: {
      providerType: a.providerType || input.provider.providerType,
      modelVersion: a.modelVersion || input.provider.modelVersion,
      promptVersion: '1.0',
      outputSchemaVersion: '1.0',
      scorePolicyVersion: '1.0',
      taxonomyVersion: '1.0',
      generatedAt,
    },
  }
}

function maxSeverity(findings) {
  return findings.reduce((acc, f) => (SEV_RANK[f.severity] > SEV_RANK[acc] ? f.severity : acc), 'LOW')
}
function productSummary(p) {
  const docs = store.documents.filter((d) => d.productId === p.productId)
  const analyses = store.analyses.filter((a) => a.productId === p.productId)
  const latestDoc = docs[docs.length - 1]
  const latestAnalysis = analyses[analyses.length - 1]
  return {
    ...clone(p),
    latestDocument: latestDoc ? { documentId: latestDoc.documentId, status: viewDocument(latestDoc).extractStatus, confirmed: latestDoc.confirmed } : null,
    latestAnalysis: latestAnalysis ? { analysisId: latestAnalysis.analysisId, status: viewAnalysis(latestAnalysis).status } : null,
  }
}

// =============================================================================
// Handlers
// =============================================================================
export const mockServer = {
  async createSession({ userId, role }) {
    await wait(180)
    const user = store.users.find((u) => u.id === userId)
    if (!user) throw new ApiError({ status: 401, errorCode: 'DEMO_IDENTITY_MISMATCH', message: '존재하지 않는 데모 사용자입니다.' })
    if (user.role !== role) throw new ApiError({ status: 401, errorCode: 'DEMO_IDENTITY_MISMATCH', message: '선택한 역할이 사용자 역할과 다릅니다.' })
    if (!user.active) throw new ApiError({ status: 403, errorCode: 'USER_INACTIVE', message: '비활성 사용자입니다.' })
    return { userId: user.id, name: user.name, role: user.role, active: user.active }
  },

  async listUsers() {
    await wait(80)
    return clone(store.users)
  },

  async getDashboardMe(auth) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(220)
    const myProducts = store.products.filter((p) => p.ownerId === user.id)
    const myDocs = store.documents.filter((d) => myProducts.some((p) => p.productId === d.productId))
    const myAnalyses = store.analyses.filter((a) => myProducts.some((p) => p.productId === a.productId))
    const myReviews = store.reviews.filter((r) => r.submittedBy === user.id)
    return {
      summary: {
        products: myProducts.length,
        extracting: myDocs.filter((d) => viewDocument(d).extractStatus === 'EXTRACTING').length,
        runningAnalyses: myAnalyses.filter((a) => ['CREATED', 'RUNNING'].includes(viewAnalysis(a).status)).length,
        pendingReviews: myReviews.filter((r) => r.status === 'PENDING').length,
        rejectedReviews: myReviews.filter((r) => r.status === 'REJECTED').length,
      },
      recentItems: myProducts
        .slice()
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, 5)
        .map(productSummary),
    }
  },

  async getDashboardCompliance(auth) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(220)
    const pendingAnalysisIds = store.reviews
      .filter((review) => review.status === 'PENDING')
      .map((review) => review.analysisId)
    const highFindings = store.analyses
      .filter((analysis) => pendingAnalysisIds.some((analysisId) => sameId(analysis.analysisId, analysisId)))
      .filter((analysis) => viewAnalysis(analysis).status === 'COMPLETED')
      .flatMap((analysis) => analysis.findings || [])
      .filter((f) => f.severity === 'HIGH').length
    const today = new Date().toISOString().slice(0, 10)
    return {
      summary: {
        pendingReviews: store.reviews.filter((r) => r.status === 'PENDING').length,
        highFindings,
        activeRiskPatterns: store.riskPatterns.filter((r) => r.status === 'ACTIVE').length,
        decidedToday: store.reviews.filter((r) => r.decidedAt && r.decidedAt.slice(0, 10) === today).length,
      },
      priorityReviews: store.reviews
        .filter((r) => r.status === 'PENDING')
        .sort((a, b) => SEV_RANK[b.maxSeverity] - SEV_RANK[a.maxSeverity])
        .slice(0, 5)
        .map((r) => ({ reviewId: r.reviewId, analysisId: r.analysisId, productName: r.productName, maxSeverity: r.maxSeverity, ownerName: r.ownerName, status: r.status })),
    }
  },

  async listProducts(auth) {
    const user = requireAuth(auth)
    await wait(160)
    const list = user.role === 'PRODUCT_MANAGER' ? store.products.filter((p) => p.ownerId === user.id) : store.products
    return { items: list.map(productSummary) }
  },

  async createProduct(auth, body, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(260)
    const name = (body.name || '').trim()
    const fieldErrors = []
    if (name.length < 1 || name.length > 100) fieldErrors.push({ field: 'name', message: '이름은 1~100자여야 합니다.' })
    if ((body.description || '').length > 500) fieldErrors.push({ field: 'description', message: '설명은 500자 이하여야 합니다.' })
    if (!['INVESTMENT', 'LOAN', 'SAVINGS'].includes(body.productType)) fieldErrors.push({ field: 'productType', message: '상품 유형을 선택하세요.' })
    if (fieldErrors.length) throw new ApiError({ status: 400, errorCode: 'VALIDATION_ERROR', message: '입력값을 확인하세요.', fieldErrors })
    const fingerprint = requestFingerprint(body)
    return idempotent(user, 'CREATE_PRODUCT', idemKey, fingerprint, () => {
      const product = {
        productId: nextId('PROD'), ownerId: user.id, name,
        productType: body.productType, description: body.description || '',
        status: 'DRAFT', createdAt: iso(),
      }
      store.products.push(product)
      return { productId: product.productId, ownerId: product.ownerId, status: product.status, createdAt: product.createdAt }
    })
  },

  async getProduct(auth, productId) {
    const user = requireAuth(auth)
    await wait(160)
    const p = store.products.find((x) => sameId(x.productId, productId))
    if (!p) throw new ApiError({ status: 404, errorCode: 'PRODUCT_NOT_FOUND', message: '상품을 찾을 수 없습니다.' })
    if (user.role === 'PRODUCT_MANAGER' && p.ownerId !== user.id) throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '소유한 상품만 조회할 수 있습니다.' })
    const summary = productSummary(p)
    return {
      ...summary,
      documents: store.documents.filter((d) => sameId(d.productId, productId)).map(viewDocument),
      analyses: store.analyses.filter((a) => sameId(a.productId, productId)).map(viewAnalysis),
    }
  },

  async uploadDocument(auth, productId, file, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    const p = store.products.find((x) => sameId(x.productId, productId))
    if (!p) throw new ApiError({ status: 404, errorCode: 'PRODUCT_NOT_FOUND', message: '상품을 찾을 수 없습니다.' })
    if (p.ownerId !== user.id) throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '소유한 상품에만 업로드할 수 있습니다.' })
    const okType = /\.(pdf|pptx)$/i.test(file?.name || '') || ['application/pdf', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'].includes(file?.type)
    if (!okType) throw new ApiError({ status: 400, errorCode: 'UNSUPPORTED_FILE', message: 'PDF 또는 PPTX만 업로드할 수 있습니다.' })
    if (file && file.size > 10 * 1024 * 1024) throw new ApiError({ status: 413, errorCode: 'FILE_TOO_LARGE', message: '파일은 최대 10MB까지 허용됩니다.' })
    await wait(500)
    const fingerprint = requestFingerprint({
      productId,
      file: { name: file?.name, type: file?.type, size: file?.size },
    })
    return idempotent(user, 'UPLOAD_DOCUMENT', idemKey, fingerprint, () => {
      const documentId = nextId('PDOC')
      const doc = {
        documentId, productId: p.productId, fileName: file?.name || 'uploaded.pdf',
        mediaType: file?.type || 'application/pdf', fileSize: file?.size || 0,
        storageKey: `local://uploads/${documentId}/${crypto.randomUUID?.() || 'x'}.pdf`,
        checksumSha256: Array.from({ length: 64 }, () => '0123456789abcdef'[Math.floor(Math.random() * 16)]).join(''),
        extractStatus: 'EXTRACTING', rawExtractedText: null, verifiedText: null, extractMethod: null,
        confirmed: false, attemptCount: 1, error: null,
      }
      store.documents.push(doc)
      store.groundTruthFacts.push({
        factId: nextId(), documentId, factType: 'PRINCIPAL_LOSS', label: '원금손실 가능성',
        value: '시장 상황에 따라 원금 전액 손실 가능', importance: 'CRITICAL', verificationStatus: 'CANDIDATE',
        sourceReferences: [{ evidenceDocumentId: 21, page: 1, excerpt: '투자자는 원금의 전부를 손실할 수 있습니다.' }],
        extractionSource: 'MOCK_FIXTURE', verifiedBy: null, verifiedAt: null,
      })
      return { documentId, status: 'UPLOADED', statusUrl: `/api/documents/${documentId}`, fileName: doc.fileName, fileSize: doc.fileSize, checksumSha256: doc.checksumSha256 }
    })
  },

  async getDocument(auth, documentId) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(140)
    const doc = store.documents.find((d) => sameId(d.documentId, documentId))
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    requirePmOwnership(user, productForDocument(doc))
    return viewDocument(doc)
  },

  async patchDocumentText(auth, documentId, body) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(220)
    const doc = store.documents.find((d) => sameId(d.documentId, documentId))
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    requirePmOwnership(user, productForDocument(doc))
    const view = viewDocument(doc)
    if (view.extractStatus !== 'READY') throw new ApiError({ status: 409, errorCode: 'DOCUMENT_NOT_READY', message: '추출이 완료된 문서만 확정할 수 있습니다.' })
    if (!(body.verifiedText || '').trim()) throw new ApiError({ status: 400, errorCode: 'EMPTY_VERIFIED_TEXT', message: '확정 텍스트가 비어 있습니다.' })
    if ((body.verifiedText || '').length > 10000) {
      throw new ApiError({
        status: 400,
        errorCode: 'VALIDATION_ERROR',
        message: '입력값을 확인하세요.',
        fieldErrors: [{ field: 'verifiedText', message: '확정 텍스트는 10,000자 이하여야 합니다.' }],
      })
    }
    doc.verifiedText = body.verifiedText
    doc.confirmed = !!body.confirmed
    if (doc.confirmed) {
      doc.confirmedBy = user.id
      doc.confirmedAt = iso()
    }
    return { documentId, status: 'READY', confirmed: doc.confirmed, confirmedBy: doc.confirmedBy || null, confirmedAt: doc.confirmedAt || null }
  },

  async retryDocument(auth, documentId) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(240)
    const doc = store.documents.find((d) => sameId(d.documentId, documentId))
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    requirePmOwnership(user, productForDocument(doc))
    if (doc.extractStatus !== 'FAILED' || !doc.error?.retryable) throw new ApiError({ status: 409, errorCode: 'DOCUMENT_NOT_RETRYABLE', message: '재시도할 수 없는 문서입니다.' })
    doc.attemptCount += 1
    doc.extractStatus = 'EXTRACTING'
    doc.error = null
    return { documentId, status: 'EXTRACTING', attemptCount: doc.attemptCount, statusUrl: `/api/documents/${documentId}` }
  },

  async listEvidenceDocuments(auth, { active = true } = {}) {
    requireAuth(auth)
    await wait(140)
    return { items: store.evidenceDocuments.filter((d) => (active ? d.active : true)) }
  },
  async listPersonaTemplates(auth) {
    requireAuth(auth)
    await wait(120)
    return { items: store.personaTemplates.filter((p) => p.active) }
  },
  async listRedTeamPacks(auth) {
    requireAuth(auth)
    await wait(120)
    return { items: store.redTeamPacks }
  },

  async listGroundTruthFacts(auth, documentId) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(100)
    const document = store.documents.find((item) => sameId(item.documentId, documentId))
    if (document) requirePmOwnership(user, productForDocument(document))
    return { items: clone(store.groundTruthFacts.filter((fact) => sameId(fact.documentId, documentId))) }
  },

  async verifyGroundTruthFact(auth, factId, body) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(120)
    const fact = store.groundTruthFacts.find((item) => sameId(item.factId, factId))
    if (!fact) throw new ApiError({ status: 404, errorCode: 'GROUND_TRUTH_FACT_NOT_FOUND', message: '공식 상품 사실을 찾을 수 없습니다.' })
    requirePmOwnership(user, productForFact(fact))
    if (!['VERIFIED', 'REJECTED'].includes(body.verificationStatus)) throw new ApiError({ status: 400, errorCode: 'VALIDATION_ERROR', message: '확인 상태가 올바르지 않습니다.' })
    fact.verificationStatus = body.verificationStatus
    if (body.value != null) fact.value = normalizedText(body.value)
    fact.verifiedBy = user.id
    fact.verifiedAt = iso()
    return clone(fact)
  },

  async createAnalysis(auth, body, idemKey, scenarioOverride, opts = {}) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(300)
    const doc = store.documents.find((d) => sameId(d.documentId, body.productDocumentId))
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    requirePmOwnership(user, productForDocument(doc))
    const ev = body.evidenceDocumentIds || []
    const personas = body.personaIds || []
    if (ev.length < 1 || ev.length > 3) throw new ApiError({ status: 400, errorCode: 'INVALID_SELECTION_COUNT', message: '근거 문서는 1~3건 선택해야 합니다.' })
    if (personas.length < 1 || personas.length > 4) throw new ApiError({ status: 400, errorCode: 'INVALID_SELECTION_COUNT', message: 'Persona는 1~4개 선택해야 합니다.' })
    for (const id of ev) {
      const e = store.evidenceDocuments.find((x) => sameId(x.documentId, id))
      if (!e || !e.active) throw new ApiError({ status: 400, errorCode: 'INVALID_EVIDENCE_DOCUMENT', message: '비활성 근거 문서는 사용할 수 없습니다.' })
    }
    const pack = store.redTeamPacks.find((x) => sameId(x.redTeamPackId, body.redTeamPackId))
    if (!pack) throw new ApiError({ status: 400, errorCode: 'INVALID_RED_TEAM_PACK', message: 'Red Team Pack을 선택하세요.' })
    const fingerprint = requestFingerprint({ body, scenarioOverride: scenarioOverride || null, local: !!opts.local })
    return idempotent(user, 'CREATE_ANALYSIS', idemKey, fingerprint, async () => {
      if (!doc.confirmed) throw new ApiError({ status: 409, errorCode: 'DOCUMENT_NOT_CONFIRMED', message: '추출 텍스트 확인 후 분석을 요청하세요.' })
      const verifiedFacts = store.groundTruthFacts.filter((fact) => sameId(fact.documentId, doc.documentId) && fact.verificationStatus === 'VERIFIED')
      if (!verifiedFacts.length) throw new ApiError({ status: 409, errorCode: 'GROUND_TRUTH_NOT_VERIFIED', message: '확정된 공식 상품 사실이 필요합니다.' })
      const providerType = opts.local ? 'LOCAL_OLLAMA' : 'MOCK'
      const modelVersion = opts.local ? 'qwen2.5:7b-instruct' : 'DETERMINISTIC_FIXTURE_V1'
      const inputSnapshot = captureAnalysisInput(
        doc,
        verifiedFacts,
        ev,
        personas,
        pack,
        providerType,
        modelVersion,
        scenarioOverride || 'GUARANTEE_MISUNDERSTANDING_HIGH',
      )
      const inputHash = await analysisInputHash(inputSnapshot)
      const duplicate = store.analyses.find((analysis) =>
        sameId(analysis.productDocumentId, doc.documentId) && analysis.inputHash === inputHash && analysis.status !== 'FAILED')
      if (duplicate) {
        throw new ApiError({ status: 409, errorCode: 'DUPLICATE_ANALYSIS_INPUT', message: '동일 문서와 동일 조건으로 생성된 분석이 있습니다.', existingAnalysisId: duplicate.analysisId })
      }
      const analysisId = nextId('ANL')
      const a = {
        analysisId, productDocumentId: doc.documentId, productId: doc.productId,
        status: 'CREATED', riskScore: null, providerType, modelVersion,
        evidenceDocumentIds: ev, personaIds: personas, redTeamPackId: body.redTeamPackId,
        findings: [], inputHash, inputSnapshot, attemptCount: 1, error: null, createdAt: iso(),
      }
      if (opts.local) {
        a.status = 'RUNNING'
        a.clockDriven = false
      } else {
        planAnalysis(a, scenarioOverride || 'GUARANTEE_MISUNDERSTANDING_HIGH')
      }
      store.analyses.push(a)
      return { analysisId, status: opts.local ? 'RUNNING' : 'CREATED', statusUrl: `/api/analyses/${analysisId}`, resultUrl: `/api/analyses/${analysisId}/result` }
    })
  },

  async getAnalysis(auth, analysisId) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(120)
    const a = store.analyses.find((x) => sameId(x.analysisId, analysisId))
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    requirePmOwnership(user, productForAnalysis(a))
    const v = viewAnalysis(a)
    return { analysisId: a.analysisId, status: v.status, stage: v.stage, progress: v.progress ?? (v.status === 'COMPLETED' ? 100 : 0), riskScore: v.riskScore, requiresHumanApproval: true, retryable: !!v.error?.retryable, attemptCount: a.attemptCount, updatedAt: iso(), error: v.error || null }
  },

  async retryAnalysis(auth, analysisId) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(260)
    const a = store.analyses.find((x) => sameId(x.analysisId, analysisId))
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    requirePmOwnership(user, productForAnalysis(a))
    settleAnalysis(a)
    const v = viewAnalysis(a)
    if (v.status !== 'FAILED' || !a._plan?.error?.retryable) throw new ApiError({ status: 409, errorCode: 'ANALYSIS_NOT_RETRYABLE', message: '재시도할 수 없는 분석입니다.' })
    a.attemptCount += 1
    const recover = a._plan.recoverScenario || 'GUARANTEE_MISUNDERSTANDING_HIGH'
    planAnalysis(a, recover)
    a.status = 'RUNNING'
    return { analysisId, status: 'RUNNING', attemptCount: a.attemptCount, statusUrl: `/api/analyses/${analysisId}` }
  },

  async getAnalysisResult(auth, analysisId) {
    const user = requireAuth(auth)
    await wait(160)
    const a = store.analyses.find((x) => sameId(x.analysisId, analysisId))
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    if (user.role === 'PRODUCT_MANAGER') {
      requirePmOwnership(user, productForAnalysis(a))
    } else {
      requireRole(user, 'COMPLIANCE_REVIEWER')
      if (!store.reviews.some((review) => sameId(review.analysisId, analysisId))) {
        throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '검토가 요청된 분석 결과만 조회할 수 있습니다.' })
      }
    }
    settleAnalysis(a)
    const v = viewAnalysis(a)
    if (v.status !== 'COMPLETED') throw new ApiError({ status: 409, errorCode: 'ANALYSIS_NOT_COMPLETED', message: '분석이 완료되지 않았습니다.' })
    finalizeAnalysisSnapshot(a, a.completedAt || a.createdAt || iso())
    return clone(a.resultSnapshot)
  },

  async createReview(auth, body, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(240)
    const a = store.analyses.find((x) => sameId(x.analysisId, body.analysisId))
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    requirePmOwnership(user, productForAnalysis(a))
    if ((body.submissionComment || '').length > 500) {
      throw new ApiError({
        status: 400,
        errorCode: 'VALIDATION_ERROR',
        message: '입력값을 확인하세요.',
        fieldErrors: [{ field: 'submissionComment', message: '검토 요청 의견은 500자 이하여야 합니다.' }],
      })
    }
    const fingerprint = requestFingerprint(body)
    requireLatestAnalysis(a)
    return idempotent(user, 'CREATE_REVIEW', idemKey, fingerprint, () => {
      requireLatestAnalysis(a)
      settleAnalysis(a)
      if (viewAnalysis(a).status !== 'COMPLETED') throw new ApiError({ status: 409, errorCode: 'ANALYSIS_NOT_COMPLETED', message: '완료된 분석만 검토 요청할 수 있습니다.' })
      if (store.reviews.some((r) => sameId(r.analysisId, body.analysisId))) throw new ApiError({ status: 409, errorCode: 'REVIEW_ALREADY_EXISTS', message: '이미 검토가 요청된 분석입니다.' })
      const product = store.products.find((p) => sameId(p.productId, a.productId))
      const review = {
        reviewId: nextId('REV'), analysisId: a.analysisId, productId: a.productId,
        productName: product?.name || a.productId, maxSeverity: maxSeverity(a.findings || []),
        status: 'PENDING', submittedBy: user.id, ownerName: user.name,
        submittedAt: iso(), submissionComment: body.submissionComment || '',
        reviewerId: null, decidedAt: null, comment: null, selectedFindingIds: [],
      }
      store.reviews.push(review)
      pushAudit('REVIEW', review.reviewId, 'REVIEW_SUBMITTED', user.id)
      return { reviewId: review.reviewId, analysisId: review.analysisId, status: 'PENDING', submittedBy: user.id, submittedAt: review.submittedAt }
    })
  },

  async getReview(auth, reviewId) {
    const user = requireAuth(auth)
    await wait(90)
    const r = store.reviews.find((x) => sameId(x.reviewId, reviewId))
    if (!r) throw new ApiError({ status: 404, errorCode: 'REVIEW_NOT_FOUND', message: '검토를 찾을 수 없습니다.' })
    requirePmOwnership(user, productForReview(r))
    return clone(r)
  },

  async getReviewByAnalysis(auth, analysisId) {
    const user = requireAuth(auth)
    await wait(80)
    const analysis = store.analyses.find((item) => sameId(item.analysisId, analysisId))
    if (analysis) requirePmOwnership(user, productForAnalysis(analysis))
    const r = store.reviews.find((x) => sameId(x.analysisId, analysisId))
    if (!r) return null
    return clone(r)
  },

  async listReviews(auth, { status, page = 0, size = 20 } = {}) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(180)
    let items = clone(store.reviews)
    if (status) items = items.filter((r) => r.status === status)
    items.sort((a, b) => {
      if (a.status === 'PENDING' && b.status === 'PENDING') {
        const s = SEV_RANK[b.maxSeverity] - SEV_RANK[a.maxSeverity]
        return s !== 0 ? s : a.submittedAt.localeCompare(b.submittedAt)
      }
      return a.status === 'PENDING' ? -1 : b.status === 'PENDING' ? 1 : 0
    })
    return { items: items.slice(page * size, page * size + size), page, size, totalElements: items.length }
  },

  async decideReview(auth, reviewId, body) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(300)
    const review = store.reviews.find((r) => sameId(r.reviewId, reviewId))
    if (!review) throw new ApiError({ status: 404, errorCode: 'REVIEW_NOT_FOUND', message: '검토를 찾을 수 없습니다.' })
    const analysis = store.analyses.find((a) => sameId(a.analysisId, review.analysisId))
    requireLatestAnalysis(analysis)
    if (review.status !== 'PENDING') throw new ApiError({ status: 409, errorCode: 'REVIEW_ALREADY_DECIDED', message: '이미 결정된 검토입니다.' })
    if ((body.comment || '').length > 500) {
      throw new ApiError({
        status: 400,
        errorCode: 'VALIDATION_ERROR',
        message: '입력값을 확인하세요.',
        fieldErrors: [{ field: 'comment', message: '검토 의견은 500자 이하여야 합니다.' }],
      })
    }
    if (!['APPROVED', 'REJECTED'].includes(body.status)) throw new ApiError({ status: 400, errorCode: 'VALIDATION_ERROR', message: '결정 상태가 올바르지 않습니다.' })
    if (body.status === 'REJECTED' && !(body.comment || '').trim()) throw new ApiError({ status: 400, errorCode: 'COMMENT_REQUIRED', message: '반려 사유를 입력하세요.' })
    const findingIds = (analysis?.findings || []).map((f) => f.findingId)
    const selected = body.selectedFindingIds || []
    let riskPatternIds = []
    if (body.status === 'APPROVED') {
      const hasValidSelectionShape = Array.isArray(selected)
      const hasDuplicateSelections = hasValidSelectionShape && new Set(selected.map(String)).size !== selected.length
      const requiresSelection = !analysis || findingIds.length > 0
      if (!hasValidSelectionShape || hasDuplicateSelections || (requiresSelection && selected.length === 0) || !selected.every((id) => findingIds.some((findingId) => sameId(findingId, id)))) {
        throw new ApiError({ status: 400, errorCode: 'INVALID_FINDING_SELECTION', message: '승격할 Finding을 중복 없이 올바르게 선택하세요.' })
      }
      riskPatternIds = selected.map((fid) => {
        const f = analysis.findings.find((x) => sameId(x.findingId, fid))
        const rp = {
          riskPatternId: nextId('RISK'), title: seed.riskPatternTitle(f), findingStatement: f.statement, severity: f.severity,
          ruleCode: f.ruleCode || f.aiDetail?.ruleCode, affectedPersonaCodes: f.affectedPersonaCodes,
          sourceFindingId: f.findingId, sourceReviewId: review.reviewId, sourceAnalysisId: analysis.analysisId, status: 'ACTIVE', createdAt: iso(),
          sourceExcerpt: f.sourceReferences?.[0]?.excerpt || f.aiDetail?.sourceReferences?.[0]?.excerpt || '', recommendation: f.recommendation || '',
        }
        store.riskPatterns.push(rp)
        return rp.riskPatternId
      })
    }
    review.status = body.status
    review.comment = body.comment || null
    review.reviewerId = user.id
    review.decidedAt = iso()
    review.selectedFindingIds = selected
    review.riskPatternIds = riskPatternIds
    const product = store.products.find((p) => sameId(p.productId, analysis?.productId))
    if (product) product.status = body.status === 'APPROVED' ? 'APPROVED' : 'NEEDS_FIX'
    pushAudit('REVIEW', review.reviewId, body.status === 'APPROVED' ? 'REVIEW_APPROVED' : 'REVIEW_REJECTED', user.id)
    return { reviewId, status: review.status, reviewerId: user.id, riskPatternIds, decidedAt: review.decidedAt }
  },

  async listRiskPatterns(auth, filters = {}) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(160)
    let items = clone(store.riskPatterns)
    if (filters.severity) items = items.filter((r) => r.severity === filters.severity)
    if (filters.ruleCode) items = items.filter((r) => r.ruleCode === filters.ruleCode)
    if (filters.personaCode) items = items.filter((r) => r.affectedPersonaCodes.includes(filters.personaCode))
    items = items.map((pattern) => {
      const analysis = store.analyses.find((item) => sameId(item.analysisId, pattern.sourceAnalysisId))
      const suggestion = (analysis?.guardFitSuggestions || []).find((item) =>
        analysis.vulnerabilityPatterns?.some((candidate) => sameId(candidate.patternId, item.patternId) && candidate.findingIds.some((id) => sameId(id, pattern.sourceFindingId))))
      return { ...pattern, guardFitSuggestion: suggestion ? clone(suggestion) : null }
    })
    return { items }
  },

  async createGuardFitAction(auth, body, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(240)
    if (!['LABEL', 'WARNING', 'QUESTION', 'COMPARISON'].includes(body.actionType)) throw new ApiError({ status: 400, errorCode: 'INVALID_ACTION_TYPE', message: '조치 유형이 올바르지 않습니다.' })
    const fieldErrors = []
    if ((body.label || '').length > 100) fieldErrors.push({ field: 'label', message: '라벨은 100자 이하여야 합니다.' })
    if ((body.placement || '').length > 100) fieldErrors.push({ field: 'placement', message: '배치 위치는 100자 이하여야 합니다.' })
    if (fieldErrors.length) throw new ApiError({ status: 400, errorCode: 'VALIDATION_ERROR', message: '입력값을 확인하세요.', fieldErrors })
    const fingerprint = requestFingerprint(body)
    return idempotent(user, 'CREATE_GUARDFIT_ACTION', idemKey, fingerprint, () => {
      const rp = store.riskPatterns.find((r) => sameId(r.riskPatternId, body.riskPatternId))
      if (!rp || rp.status !== 'ACTIVE') throw new ApiError({ status: 409, errorCode: 'RISK_PATTERN_NOT_ACTIVE', message: 'ACTIVE 상태의 Risk Pattern에만 조치를 만들 수 있습니다.' })
      const action = {
        actionId: nextId('GFA'), riskPatternId: body.riskPatternId, actionType: body.actionType,
        label: body.label, placement: body.placement, required: !!body.required,
        status: 'DRAFT', createdBy: user.id, updatedBy: null, updatedAt: null,
      }
      store.guardfitActions.push(action)
      pushAudit('GUARDFIT_ACTION', action.actionId, 'ACTION_CREATED', user.id)
      return { actionId: action.actionId, riskPatternId: action.riskPatternId, status: 'DRAFT', createdBy: user.id }
    })
  },

  async listGuardFitActions(auth, { riskPatternId, status } = {}) {
    const user = requireAuth(auth)
    await wait(160)
    let items = clone(store.guardfitActions)
    if (user.role === 'PRODUCT_MANAGER') items = items.filter((a) => a.status === 'APPROVED')
    if (riskPatternId) items = items.filter((a) => sameId(a.riskPatternId, riskPatternId))
    if (status) items = items.filter((a) => a.status === status)
    // Join the source pattern so the PM Before/After guide has the original
    // risky expression without needing Risk Library access (RBAC).
    items = items.map((a) => {
      const rp = store.riskPatterns.find((r) => sameId(r.riskPatternId, a.riskPatternId))
      const review = rp ? store.reviews.find((item) => sameId(item.reviewId, rp.sourceReviewId)) : null
      const analysis = review ? store.analyses.find((item) => sameId(item.analysisId, review.analysisId)) : null
      const finding = analysis?.findings?.find((item) => sameId(item.findingId, rp.sourceFindingId))
      return {
        ...a,
        pattern: rp
          ? { title: rp.title, findingStatement: rp.findingStatement, severity: rp.severity, ruleCode: rp.ruleCode, sourceExcerpt: rp.sourceExcerpt || '', recommendation: rp.recommendation || '', affectedPersonaCodes: rp.affectedPersonaCodes || [] }
          : null,
        supportingContext: finding ? {
          findingId: finding.findingId,
          reviewId: review.reviewId,
          statement: finding.statement,
          sourceReferences: clone(finding.sourceReferences || finding.aiDetail?.sourceReferences || []),
          evidenceReferences: clone(finding.evidenceReferences || []),
          caseReferences: clone(finding.aiDetail?.caseReferences || []),
        } : null,
      }
    })
    return { items }
  },

  async updateGuardFitAction(auth, actionId, body) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(240)
    const action = store.guardfitActions.find((a) => sameId(a.actionId, actionId))
    if (!action) throw new ApiError({ status: 404, errorCode: 'ACTION_NOT_FOUND', message: '조치를 찾을 수 없습니다.' })
    if (action.status === 'APPROVED') throw new ApiError({ status: 409, errorCode: 'ACTION_ALREADY_FINALIZED', message: '확정된 조치는 수정할 수 없습니다. 새 버전을 만드세요.' })
    const fieldErrors = []
    if ((body.label || '').length > 100) fieldErrors.push({ field: 'label', message: '라벨은 100자 이하여야 합니다.' })
    if ((body.placement || '').length > 100) fieldErrors.push({ field: 'placement', message: '배치 위치는 100자 이하여야 합니다.' })
    if (fieldErrors.length) throw new ApiError({ status: 400, errorCode: 'VALIDATION_ERROR', message: '입력값을 확인하세요.', fieldErrors })
    if (body.label != null) action.label = body.label
    if (body.placement != null) action.placement = body.placement
    if (body.required != null) action.required = !!body.required
    if (body.status && ['APPROVED', 'DRAFT'].includes(body.status)) action.status = body.status
    action.updatedBy = user.id
    action.updatedAt = iso()
    if (body.status === 'APPROVED') pushAudit('GUARDFIT_ACTION', action.actionId, 'ACTION_APPROVED', user.id)
    return { actionId, status: action.status, updatedBy: user.id, updatedAt: action.updatedAt }
  },

  async listAuditLogs(auth, filters = {}) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(160)
    let items = clone(store.auditLogs).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    if (filters.resourceType) items = items.filter((a) => a.resourceType === filters.resourceType)
    items = items.map((log) => {
      if (log.resourceType === 'REVIEW') {
        const review = store.reviews.find((item) => sameId(item.reviewId, log.resourceId))
        const product = review ? store.products.find((item) => sameId(item.productId, review.productId)) : null
        return {
          ...log,
          resourceLabel: review?.productName || product?.name || null,
          analysisId: review?.analysisId || null,
        }
      }
      if (log.resourceType === 'GUARDFIT_ACTION') {
        const action = store.guardfitActions.find((item) => sameId(item.actionId, log.resourceId))
        const pattern = action ? store.riskPatterns.find((item) => sameId(item.riskPatternId, action.riskPatternId)) : null
        return {
          ...log,
          resourceLabel: pattern?.title || action?.label || null,
          analysisId: pattern?.sourceAnalysisId || null,
        }
      }
      return log
    })
    return { items }
  },
}

function pushAudit(resourceType, resourceId, action, actorId) {
  store.auditLogs.push({
    auditId: nextId('AUD'), resourceType, resourceId, action, actorId,
    traceId: traceId(), createdAt: iso(),
  })
}

// Persist store after every mutating call (browser only).
if (hasLS) {
  for (const key of Object.keys(mockServer)) {
    const fn = mockServer[key]
    mockServer[key] = async (...args) => {
      const r = await fn(...args)
      persist()
      return r
    }
  }
}
