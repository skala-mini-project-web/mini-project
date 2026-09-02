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

const SEV_RANK = { HIGH: 3, MEDIUM: 2, LOW: 1 }
const clone = (v) => JSON.parse(JSON.stringify(v))
const nowMs = () => Date.now()
const iso = (ms = Date.now()) => new Date(ms).toISOString()
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

let seq = 1000
const nextId = (prefix) => `${prefix}-${++seq}`
const traceId = () => `trc-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-${String(++seq).slice(-4)}`

function createStore() {
  return {
    users: clone(seed.USERS),
    personaTemplates: clone(seed.PERSONA_TEMPLATES),
    redTeamPacks: clone(seed.RED_TEAM_PACKS),
    evidenceDocuments: clone(seed.EVIDENCE_DOCUMENTS),
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
const hasLS = typeof localStorage !== 'undefined'
function persist() {
  if (!hasLS) return
  try {
    const { idempotency, ...rest } = store
    localStorage.setItem(LS_KEY, JSON.stringify({ ...rest, __seq: seq }))
  } catch {}
}
function loadStore() {
  if (hasLS) {
    try {
      const raw = localStorage.getItem(LS_KEY)
      if (raw) {
        const d = JSON.parse(raw)
        if (typeof d.__seq === 'number') seq = d.__seq
        const { __seq, ...rest } = d
        return { ...rest, idempotency: new Map() }
      }
    } catch {}
  }
  return createStore()
}
let store = loadStore()
export function resetStore() {
  store = createStore()
  seq = 1000
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
function idempotent(key, produce) {
  if (key && store.idempotency.has(key)) return clone(store.idempotency.get(key))
  const res = produce()
  if (key) store.idempotency.set(key, clone(res))
  return res
}

// ---- document extraction ----------------------------------------------------
// Live-uploaded docs go UPLOADED -> EXTRACTING immediately; the client runs the
// real pdf.js / JSZip / tesseract extraction and calls ingestExtraction() to
// resolve READY (with text + method) or FAILED (with error). GET is read-only.
function viewDocument(doc) {
  const d = clone(doc)
  delete d._plan
  return d
}
export function ingestExtraction(documentId, result) {
  const doc = store.documents.find((x) => x.documentId === documentId)
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
    const findings = s.findings(a.productDocumentId)
    a._plan = {
      createdAt: start,
      runningAt: start + s.schedule.createdToRunningMs,
      doneAt: start + s.schedule.createdToRunningMs + s.schedule.runningToCompletedMs,
      terminal: 'COMPLETED',
      findings,
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
    a.riskScore = p.riskScore
    a.error = null
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
      v.progress = 0
    } else if (t < p.doneAt) {
      v.status = 'RUNNING'
      const frac = (t - p.runningAt) / (p.doneAt - p.runningAt)
      v.progress = Math.min(95, Math.max(10, Math.round(frac * 100)))
      v.error = null
    } else {
      settleAnalysis(a)
      v.status = a.status
      v.riskScore = a.riskScore
      v.error = a.error
      v.progress = a.status === 'COMPLETED' ? 100 : v.progress
    }
  }
  delete v._plan
  delete v.findings
  return v
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
        .slice(0, 6)
        .map(productSummary),
    }
  },

  async getDashboardCompliance(auth) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(220)
    const highFindings = store.analyses
      .filter((a) => viewAnalysis(a).status === 'COMPLETED')
      .flatMap((a) => a.findings || [])
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
        .slice(0, 8)
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
    return idempotent(idemKey, () => {
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
    const p = store.products.find((x) => x.productId === productId)
    if (!p) throw new ApiError({ status: 404, errorCode: 'PRODUCT_NOT_FOUND', message: '상품을 찾을 수 없습니다.' })
    if (user.role === 'PRODUCT_MANAGER' && p.ownerId !== user.id) throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '소유한 상품만 조회할 수 있습니다.' })
    const summary = productSummary(p)
    return {
      ...summary,
      documents: store.documents.filter((d) => d.productId === productId).map(viewDocument),
      analyses: store.analyses.filter((a) => a.productId === productId).map(viewAnalysis),
    }
  },

  async uploadDocument(auth, productId, file, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    const p = store.products.find((x) => x.productId === productId)
    if (!p) throw new ApiError({ status: 404, errorCode: 'PRODUCT_NOT_FOUND', message: '상품을 찾을 수 없습니다.' })
    if (p.ownerId !== user.id) throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '소유한 상품에만 업로드할 수 있습니다.' })
    const okType = /\.(pdf|pptx)$/i.test(file?.name || '') || ['application/pdf', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'].includes(file?.type)
    if (!okType) throw new ApiError({ status: 400, errorCode: 'UNSUPPORTED_FILE', message: 'PDF 또는 PPTX만 업로드할 수 있습니다.' })
    if (file && file.size > 10 * 1024 * 1024) throw new ApiError({ status: 413, errorCode: 'FILE_TOO_LARGE', message: '파일은 최대 10MB까지 허용됩니다.' })
    await wait(500)
    return idempotent(idemKey, () => {
      const documentId = nextId('PDOC')
      const doc = {
        documentId, productId, fileName: file?.name || 'uploaded.pdf',
        mediaType: file?.type || 'application/pdf', fileSize: file?.size || 0,
        storageKey: `local://uploads/${documentId}/${crypto.randomUUID?.() || 'x'}.pdf`,
        checksumSha256: Array.from({ length: 64 }, () => '0123456789abcdef'[Math.floor(Math.random() * 16)]).join(''),
        extractStatus: 'EXTRACTING', rawExtractedText: null, verifiedText: null, extractMethod: null,
        confirmed: false, attemptCount: 1, error: null,
      }
      store.documents.push(doc)
      return { documentId, status: 'UPLOADED', statusUrl: `/api/documents/${documentId}`, fileName: doc.fileName, fileSize: doc.fileSize, checksumSha256: doc.checksumSha256 }
    })
  },

  async getDocument(auth, documentId) {
    requireAuth(auth)
    await wait(140)
    const doc = store.documents.find((d) => d.documentId === documentId)
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    return viewDocument(doc)
  },

  async patchDocumentText(auth, documentId, body) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(220)
    const doc = store.documents.find((d) => d.documentId === documentId)
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    const view = viewDocument(doc)
    if (view.extractStatus !== 'READY') throw new ApiError({ status: 409, errorCode: 'DOCUMENT_NOT_READY', message: '추출이 완료된 문서만 확정할 수 있습니다.' })
    if (!(body.verifiedText || '').trim()) throw new ApiError({ status: 400, errorCode: 'EMPTY_VERIFIED_TEXT', message: '확정 텍스트가 비어 있습니다.' })
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
    const doc = store.documents.find((d) => d.documentId === documentId)
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
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

  async createAnalysis(auth, body, idemKey, scenarioOverride) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(300)
    const doc = store.documents.find((d) => d.documentId === body.productDocumentId)
    if (!doc) throw new ApiError({ status: 404, errorCode: 'DOCUMENT_NOT_FOUND', message: '문서를 찾을 수 없습니다.' })
    if (!doc.confirmed) throw new ApiError({ status: 409, errorCode: 'DOCUMENT_NOT_CONFIRMED', message: '추출 텍스트 확인 후 분석을 요청하세요.' })
    const ev = body.evidenceDocumentIds || []
    const personas = body.personaIds || []
    if (ev.length < 1 || ev.length > 3) throw new ApiError({ status: 400, errorCode: 'INVALID_SELECTION_COUNT', message: '근거 문서는 1~3건 선택해야 합니다.' })
    if (personas.length < 1 || personas.length > 3) throw new ApiError({ status: 400, errorCode: 'INVALID_SELECTION_COUNT', message: 'Persona는 1~3개 선택해야 합니다.' })
    for (const id of ev) {
      const e = store.evidenceDocuments.find((x) => x.documentId === id)
      if (!e || !e.active) throw new ApiError({ status: 400, errorCode: 'INVALID_EVIDENCE_DOCUMENT', message: '비활성 근거 문서는 사용할 수 없습니다.' })
    }
    return idempotent(idemKey, () => {
      const analysisId = nextId('ANL')
      const a = {
        analysisId, productDocumentId: doc.documentId, productId: doc.productId,
        status: 'CREATED', riskScore: null, providerType: 'MOCK',
        evidenceDocumentIds: ev, personaIds: personas, redTeamPackCode: body.redTeamPackCode,
        findings: [], attemptCount: 1, error: null, createdAt: iso(),
      }
      planAnalysis(a, scenarioOverride || 'GUARANTEE_MISUNDERSTANDING_HIGH')
      store.analyses.push(a)
      return { analysisId, status: 'CREATED', statusUrl: `/api/analyses/${analysisId}`, resultUrl: `/api/analyses/${analysisId}/result` }
    })
  },

  async getAnalysis(auth, analysisId) {
    requireAuth(auth)
    await wait(120)
    const a = store.analyses.find((x) => x.analysisId === analysisId)
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    const v = viewAnalysis(a)
    return { analysisId, status: v.status, progress: v.progress ?? (v.status === 'COMPLETED' ? 100 : 0), attemptCount: a.attemptCount, updatedAt: iso(), error: v.error || null }
  },

  async retryAnalysis(auth, analysisId) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(260)
    const a = store.analyses.find((x) => x.analysisId === analysisId)
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
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
    requireAuth(auth)
    await wait(160)
    const a = store.analyses.find((x) => x.analysisId === analysisId)
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    settleAnalysis(a)
    const v = viewAnalysis(a)
    if (v.status !== 'COMPLETED') throw new ApiError({ status: 409, errorCode: 'ANALYSIS_NOT_COMPLETED', message: '분석이 완료되지 않았습니다.' })
    const doc = store.documents.find((d) => d.documentId === a.productDocumentId)
    const grounding = (a.evidenceDocumentIds || []).map((id) => {
      const e = store.evidenceDocuments.find((x) => x.documentId === id)
      return e ? { documentId: e.documentId, title: e.title } : { documentId: id, title: id }
    })
    return {
      analysisId, status: 'COMPLETED', riskScore: a.riskScore,
      sourceDocument: doc ? { documentId: doc.documentId, fileName: doc.fileName } : null,
      groundingDocuments: grounding,
      findings: clone(a.findings || []),
    }
  },

  async createReview(auth, body, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'PRODUCT_MANAGER')
    await wait(240)
    const a = store.analyses.find((x) => x.analysisId === body.analysisId)
    if (!a) throw new ApiError({ status: 404, errorCode: 'ANALYSIS_NOT_FOUND', message: '분석을 찾을 수 없습니다.' })
    settleAnalysis(a)
    if (viewAnalysis(a).status !== 'COMPLETED') throw new ApiError({ status: 409, errorCode: 'ANALYSIS_NOT_COMPLETED', message: '완료된 분석만 검토 요청할 수 있습니다.' })
    if (store.reviews.some((r) => r.analysisId === body.analysisId)) throw new ApiError({ status: 409, errorCode: 'REVIEW_ALREADY_EXISTS', message: '이미 검토가 요청된 분석입니다.' })
    return idempotent(idemKey, () => {
      const product = store.products.find((p) => p.productId === a.productId)
      const review = {
        reviewId: nextId('REV'), analysisId: a.analysisId, productId: a.productId,
        productName: product?.name || a.productId, maxSeverity: maxSeverity(a.findings || []),
        status: 'PENDING', decision: null, submittedBy: user.id, ownerName: user.name,
        submittedAt: iso(), submissionComment: body.submissionComment || '',
        reviewerId: null, decidedAt: null, comment: null, selectedFindingIds: [],
      }
      store.reviews.push(review)
      pushAudit('REVIEW', review.reviewId, 'REVIEW_SUBMITTED', user.id)
      return { reviewId: review.reviewId, analysisId: review.analysisId, status: 'PENDING', decision: null, submittedBy: user.id, submittedAt: review.submittedAt }
    })
  },

  async getReview(auth, reviewId) {
    const user = requireAuth(auth)
    await wait(90)
    const r = store.reviews.find((x) => x.reviewId === reviewId)
    if (!r) throw new ApiError({ status: 404, errorCode: 'REVIEW_NOT_FOUND', message: '검토를 찾을 수 없습니다.' })
    if (user.role === 'PRODUCT_MANAGER' && r.submittedBy !== user.id)
      throw new ApiError({ status: 403, errorCode: 'FORBIDDEN', message: '권한이 없습니다.' })
    return clone(r)
  },

  async getReviewByAnalysis(auth, analysisId) {
    const user = requireAuth(auth)
    await wait(80)
    const r = store.reviews.find((x) => x.analysisId === analysisId)
    if (!r) return null
    if (user.role === 'PRODUCT_MANAGER' && r.submittedBy !== user.id) return null
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
    const review = store.reviews.find((r) => r.reviewId === reviewId)
    if (!review) throw new ApiError({ status: 404, errorCode: 'REVIEW_NOT_FOUND', message: '검토를 찾을 수 없습니다.' })
    if (review.status !== 'PENDING') throw new ApiError({ status: 409, errorCode: 'REVIEW_ALREADY_DECIDED', message: '이미 결정된 검토입니다.' })
    if (!['APPROVED', 'REJECTED'].includes(body.decision)) throw new ApiError({ status: 400, errorCode: 'VALIDATION_ERROR', message: '결정 값이 올바르지 않습니다.' })
    if (body.decision === 'REJECTED' && !(body.comment || '').trim()) throw new ApiError({ status: 400, errorCode: 'COMMENT_REQUIRED', message: '반려 사유를 입력하세요.' })
    const analysis = store.analyses.find((a) => a.analysisId === review.analysisId)
    const findingIds = (analysis?.findings || []).map((f) => f.findingId)
    const selected = body.selectedFindingIds || []
    let riskPatternIds = []
    if (body.decision === 'APPROVED') {
      if (selected.length === 0 || !selected.every((id) => findingIds.includes(id))) {
        throw new ApiError({ status: 400, errorCode: 'INVALID_FINDING_SELECTION', message: '승격할 Finding을 1개 이상 올바르게 선택하세요.' })
      }
      riskPatternIds = selected.map((fid) => {
        const f = analysis.findings.find((x) => x.findingId === fid)
        const rp = {
          riskPatternId: nextId('RISK'), name: f.message.slice(0, 24), severity: f.severity,
          ruleCode: f.ruleCode, affectedPersonaCodes: f.affectedPersonaCodes,
          sourceFindingId: f.findingId, sourceReviewId: review.reviewId, status: 'ACTIVE', createdAt: iso(),
          sourceExcerpt: f.sourceReference?.excerpt || '', recommendation: f.recommendation || '',
        }
        store.riskPatterns.push(rp)
        return rp.riskPatternId
      })
    }
    review.status = body.decision
    review.decision = body.decision
    review.comment = body.comment || null
    review.reviewerId = user.id
    review.decidedAt = iso()
    review.selectedFindingIds = selected
    review.riskPatternIds = riskPatternIds
    const product = store.products.find((p) => p.productId === analysis?.productId)
    if (product) product.status = body.decision === 'APPROVED' ? 'APPROVED' : 'NEEDS_FIX'
    pushAudit('REVIEW', review.reviewId, body.decision === 'APPROVED' ? 'REVIEW_APPROVED' : 'REVIEW_REJECTED', user.id)
    return { reviewId, status: review.status, decision: review.decision, reviewerId: user.id, riskPatternIds, decidedAt: review.decidedAt }
  },

  async listRiskPatterns(auth, filters = {}) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(160)
    let items = clone(store.riskPatterns)
    if (filters.severity) items = items.filter((r) => r.severity === filters.severity)
    if (filters.ruleCode) items = items.filter((r) => r.ruleCode === filters.ruleCode)
    if (filters.personaCode) items = items.filter((r) => r.affectedPersonaCodes.includes(filters.personaCode))
    return { items }
  },

  async createGuardFitAction(auth, body, idemKey) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(240)
    const rp = store.riskPatterns.find((r) => r.riskPatternId === body.riskPatternId)
    if (!rp || rp.status !== 'ACTIVE') throw new ApiError({ status: 409, errorCode: 'RISK_PATTERN_NOT_ACTIVE', message: 'ACTIVE 상태의 Risk Pattern에만 조치를 만들 수 있습니다.' })
    return idempotent(idemKey, () => {
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
    if (riskPatternId) items = items.filter((a) => a.riskPatternId === riskPatternId)
    if (status) items = items.filter((a) => a.status === status)
    // Join the source pattern so the PM Before/After guide has the original
    // risky expression without needing Risk Library access (RBAC).
    items = items.map((a) => {
      const rp = store.riskPatterns.find((r) => r.riskPatternId === a.riskPatternId)
      return {
        ...a,
        pattern: rp
          ? { name: rp.name, severity: rp.severity, ruleCode: rp.ruleCode, sourceExcerpt: rp.sourceExcerpt || '', recommendation: rp.recommendation || '', affectedPersonaCodes: rp.affectedPersonaCodes || [] }
          : null,
      }
    })
    return { items }
  },

  async updateGuardFitAction(auth, actionId, body) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(240)
    const action = store.guardfitActions.find((a) => a.actionId === actionId)
    if (!action) throw new ApiError({ status: 404, errorCode: 'ACTION_NOT_FOUND', message: '조치를 찾을 수 없습니다.' })
    if (['APPROVED', 'DISCARDED'].includes(action.status)) throw new ApiError({ status: 409, errorCode: 'ACTION_ALREADY_FINALIZED', message: '확정된 조치는 수정할 수 없습니다. 새 버전을 만드세요.' })
    if (body.label != null) action.label = body.label
    if (body.placement != null) action.placement = body.placement
    if (body.required != null) action.required = !!body.required
    if (body.status && ['APPROVED', 'DISCARDED', 'DRAFT'].includes(body.status)) action.status = body.status
    action.updatedBy = user.id
    action.updatedAt = iso()
    if (body.status === 'APPROVED') pushAudit('GUARDFIT_ACTION', action.actionId, 'ACTION_APPROVED', user.id)
    if (body.status === 'DISCARDED') pushAudit('GUARDFIT_ACTION', action.actionId, 'ACTION_DISCARDED', user.id)
    return { actionId, status: action.status, updatedBy: user.id, updatedAt: action.updatedAt }
  },

  async listAuditLogs(auth, filters = {}) {
    const user = requireAuth(auth)
    requireRole(user, 'COMPLIANCE_REVIEWER')
    await wait(160)
    let items = clone(store.auditLogs).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    if (filters.resourceType) items = items.filter((a) => a.resourceType === filters.resourceType)
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
