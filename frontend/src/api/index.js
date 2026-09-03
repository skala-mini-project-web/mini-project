// =============================================================================
// API facade. One interface, two backings (Interface First).
//   VITE_USE_MOCK=true  -> in-memory mock server (standalone frontend demo)
//   VITE_USE_MOCK=false -> real Spring backend via the /api proxy
// Paths, methods, status codes, IDs and enums follow the GuardLab API 명세서.
// =============================================================================
import { mockServer, ingestExtraction, ingestAnalysis } from './mock/server.js'
import { http, uuid } from './client.js'
import { getAuth } from './auth-context.js'
import { extractDocument } from '../lib/extract.js'
import { analyzeDocument } from '../lib/analyze.js'

// 로컬 AI(Ollama) 분석 사용 여부 (설정 토글, localStorage)
const AI_RULE_CODES = ['STABILITY_KEYWORD', 'RETURN_FRAMING', 'COST_OMISSION', 'LOSS_SOFTENING', 'FORMAL_CONFIRMATION', 'COGNITIVE_ACCESSIBILITY']
function localAiEnabled() {
  if (import.meta.env.VITE_DEBUG_AI_CONTROLS !== 'true') return false
  try { return localStorage.getItem('guardlab.ai.local') === '1' } catch { return false }
}
async function runLocalAnalysis(analysisId, documentId, personaIds) {
  try {
    const doc = await mockServer.getDocument(auth(), documentId)
    let personaCodes = personaIds
    try {
      const tpls = (await mockServer.listPersonaTemplates(auth())).items || []
      const map = Object.fromEntries(tpls.map((t) => [t.personaId, t.code || t.personaId]))
      personaCodes = personaIds.map((id) => map[id] || id)
    } catch {}
    const r = await analyzeDocument({ sourceText: doc.verifiedText || doc.rawExtractedText || '', personaCodes, ruleCodes: AI_RULE_CODES })
    ingestAnalysis(analysisId, { findings: r.findings, riskScore: r.riskScore, providerType: 'LOCAL_OLLAMA', grounding: r.grounding })
  } catch (e) {
    ingestAnalysis(analysisId, { failed: true, errorCode: 'PROVIDER_RESPONSE_INVALID', message: e?.message || '로컬 분석 오류', retryable: true })
  }
}

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'
const auth = () => getAuth()

// Mock mode: keep the real File so extraction (and retry) can read its bytes.
const fileCache = new Map()
function runExtraction(documentId, file) {
  if (import.meta.env.VITE_EXPERIMENTAL_CLIENT_EXTRACTION !== 'true') {
    ingestExtraction(documentId, {
      text: '최근 안정적인 수익률을 기대할 수 있는 투자상품입니다. 운용 보수가 부과됩니다. 시장 상황에 따라 원금 전액 손실이 발생할 수 있습니다.',
      method: 'MOCK_FIXTURE',
    })
    return
  }
  extractDocument(file)
    .then((r) => {
      const dense = (r.text || '').replace(/\s/g, '').length
      if (dense < 1)
        ingestExtraction(documentId, { failed: true, errorCode: 'OCR_REQUIRED', message: '문서에서 텍스트를 추출하지 못했습니다.', retryable: false })
      else ingestExtraction(documentId, { text: r.text, method: r.method })
    })
    .catch((e) =>
      ingestExtraction(documentId, { failed: true, errorCode: 'EXTRACTION_ERROR', message: e?.message || '추출 중 오류', retryable: true }),
    )
}
const qs = (params) => {
  const p = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v != null && v !== '') p.append(k, v)
  })
  const s = p.toString()
  return s ? `?${s}` : ''
}

export const api = {
  // ---- session / users ----
  createSession: (userId, role) =>
    USE_MOCK ? mockServer.createSession({ userId, role }) : http.post('/demo/session', { userId, role }),
  listUsers: () => (USE_MOCK ? mockServer.listUsers() : http.get('/demo/users')),

  // ---- dashboards ----
  getDashboardMe: () => (USE_MOCK ? mockServer.getDashboardMe(auth()) : http.get('/dashboard/me')),
  getDashboardCompliance: () =>
    USE_MOCK ? mockServer.getDashboardCompliance(auth()) : http.get('/dashboard/compliance'),

  // ---- products ----
  listProducts: () => (USE_MOCK ? mockServer.listProducts(auth()) : http.get('/products')),
  createProduct: (body) =>
    USE_MOCK
      ? mockServer.createProduct(auth(), body, uuid())
      : http.post('/products', body, { 'Idempotency-Key': uuid() }),
  getProduct: (id) => (USE_MOCK ? mockServer.getProduct(auth(), id) : http.get(`/products/${id}`)),

  // ---- documents ----
  uploadDocument: async (productId, file) => {
    if (!USE_MOCK) return http.upload(`/products/${productId}/documents`, file, { 'Idempotency-Key': uuid() })
    const res = await mockServer.uploadDocument(auth(), productId, file, uuid())
    fileCache.set(res.documentId, file)
    runExtraction(res.documentId, file) // real client-side extraction, resolves via polling
    return res
  },
  getDocument: (id) => (USE_MOCK ? mockServer.getDocument(auth(), id) : http.get(`/documents/${id}`)),
  patchDocumentText: (id, body) =>
    USE_MOCK ? mockServer.patchDocumentText(auth(), id, body) : http.patch(`/documents/${id}/text`, body),
  retryDocument: async (id) => {
    if (!USE_MOCK) return http.post(`/documents/${id}/retry`, { reason: 'USER_RETRY' })
    const res = await mockServer.retryDocument(auth(), id)
    const file = fileCache.get(id)
    if (file) runExtraction(id, file)
    else ingestExtraction(id, { failed: true, errorCode: 'FILE_UNAVAILABLE', message: '원본 파일을 다시 업로드하세요.', retryable: false })
    return res
  },

  // ---- reference data ----
  listEvidenceDocuments: (params) =>
    USE_MOCK ? mockServer.listEvidenceDocuments(auth(), params) : http.get(`/evidence-documents${qs(params)}`),
  listPersonaTemplates: () =>
    USE_MOCK ? mockServer.listPersonaTemplates(auth()) : http.get('/persona-templates'),
  listRedTeamPacks: () => (USE_MOCK ? mockServer.listRedTeamPacks(auth()) : http.get('/red-team-packs')),
  listGroundTruthFacts: (documentId) =>
    USE_MOCK ? mockServer.listGroundTruthFacts(auth(), documentId) : http.get(`/product-documents/${documentId}/ground-truth-facts`),
  verifyGroundTruthFact: (factId, body) =>
    USE_MOCK ? mockServer.verifyGroundTruthFact(auth(), factId, body) : http.put(`/ground-truth-facts/${factId}/verification`, body),

  // ---- analyses ----
  createAnalysis: async (body, scenario) => {
    if (!USE_MOCK) return http.post('/analyses', body, { 'Idempotency-Key': uuid(), ...(scenario ? { 'X-Demo-Scenario': scenario } : {}) })
    const local = localAiEnabled()
    const res = await mockServer.createAnalysis(auth(), body, uuid(), scenario, { local })
    if (local) runLocalAnalysis(res.analysisId, body.productDocumentId, body.personaIds || [])
    return res
  },
  getAnalysis: (id) => (USE_MOCK ? mockServer.getAnalysis(auth(), id) : http.get(`/analyses/${id}`)),
  retryAnalysis: (id) =>
    USE_MOCK ? mockServer.retryAnalysis(auth(), id) : http.post(`/analyses/${id}/retry`, { reason: 'USER_RETRY' }),
  getAnalysisResult: (id) =>
    USE_MOCK ? mockServer.getAnalysisResult(auth(), id) : http.get(`/analyses/${id}/result`),

  // ---- reviews ----
  createReview: (body) =>
    USE_MOCK
      ? mockServer.createReview(auth(), body, uuid())
      : http.post('/reviews', body, { 'Idempotency-Key': uuid() }),
  listReviews: (params) => (USE_MOCK ? mockServer.listReviews(auth(), params) : http.get(`/reviews${qs(params)}`)),
  getReview: (id) => (USE_MOCK ? mockServer.getReview(auth(), id) : http.get(`/reviews/${id}`)),
  getReviewByAnalysis: (analysisId) => (USE_MOCK ? mockServer.getReviewByAnalysis(auth(), analysisId) : http.get(`/analyses/${analysisId}/review`)),
  decideReview: (id, body) =>
    USE_MOCK ? mockServer.decideReview(auth(), id, body) : http.post(`/reviews/${id}/decision`, body),

  // ---- risk library ----
  listRiskPatterns: (params) =>
    USE_MOCK ? mockServer.listRiskPatterns(auth(), params) : http.get(`/risk-patterns${qs(params)}`),

  // ---- guardfit ----
  createGuardFitAction: (body) =>
    USE_MOCK
      ? mockServer.createGuardFitAction(auth(), body, uuid())
      : http.post('/guardfit/actions', body, { 'Idempotency-Key': uuid() }),
  listGuardFitActions: (params) =>
    USE_MOCK ? mockServer.listGuardFitActions(auth(), params) : http.get(`/guardfit/actions${qs(params)}`),
  updateGuardFitAction: (id, body) =>
    USE_MOCK ? mockServer.updateGuardFitAction(auth(), id, body) : http.put(`/guardfit/actions/${id}`, body),

  // ---- audit ----
  listAuditLogs: (params) =>
    USE_MOCK ? mockServer.listAuditLogs(auth(), params) : http.get(`/audit-logs${qs(params)}`),
}

export { ApiError, errorKind } from './errors.js'
