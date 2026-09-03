// Headless E2E smoke test of the mock backend against the spec.
// Run: node scripts/smoke.mjs
import { mockServer, ingestExtraction } from '../src/api/mock/server.js'
// The client (api layer) performs real extraction in the browser; here we
// simulate that step by ingesting extracted text after upload.
const extract = (id, text = '원금 손실 가능성이 있습니다.') => ingestExtraction(id, { text, method: 'pdf-text' })

const PM = { userId: 'USER-PM-001', role: 'PRODUCT_MANAGER' }
const CR = { userId: 'USER-CR-001', role: 'COMPLIANCE_REVIEWER' }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0
let fail = 0
function ok(name, cond, extra = '') {
  if (cond) {
    pass++
    console.log(`  PASS  ${name}`)
  } else {
    fail++
    console.log(`  FAIL  ${name} ${extra}`)
  }
}
async function expectThrow(name, fn, code) {
  try {
    await fn()
    fail++
    console.log(`  FAIL  ${name} (no error thrown)`)
  } catch (e) {
    ok(name, e.errorCode === code, `got ${e.errorCode}/${e.status}`)
  }
}
async function pollUntil(fn, done, { max = 8000, step = 200 } = {}) {
  const start = Date.now()
  let last
  while (Date.now() - start < max) {
    last = await fn()
    if (done(last)) return last
    await sleep(step)
  }
  return last
}

console.log('\n== AUTH ==')
await expectThrow('CR role mismatch rejected', () => mockServer.createSession({ userId: 'USER-PM-001', role: 'COMPLIANCE_REVIEWER' }), 'DEMO_IDENTITY_MISMATCH')
const sess = await mockServer.createSession(PM)
ok('PM session valid', sess.userId === 'USER-PM-001' && sess.role === 'PRODUCT_MANAGER')

console.log('\n== GOLDEN PATH (PM) ==')
const prod = await mockServer.createProduct(PM, { name: '테스트 인컴 투자상품', productType: 'INVESTMENT', description: 'x' }, 'k-prod-1')
ok('product created', prod.status === 'DRAFT' && typeof prod.productId === 'number')

const prodDup = await mockServer.createProduct(PM, { name: '테스트 인컴 투자상품', productType: 'INVESTMENT', description: 'x' }, 'k-prod-1')
ok('idempotent product (same key -> same id)', prodDup.productId === prod.productId)

const up = await mockServer.uploadDocument(PM, prod.productId, { name: 'sample.pdf', type: 'application/pdf', size: 2000000 }, 'k-doc-1')
ok('upload accepted (UPLOADED)', up.status === 'UPLOADED')
extract(up.documentId)

const readyDoc = await pollUntil(() => mockServer.getDocument(PM, up.documentId), (d) => d.extractStatus === 'READY')
ok('extraction reached READY', readyDoc.extractStatus === 'READY')
ok('rawExtractedText present', !!readyDoc.rawExtractedText)

await expectThrow('analysis blocked before confirm', () =>
  mockServer.createAnalysis(PM, { productDocumentId: up.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-early'),
  'DOCUMENT_NOT_CONFIRMED')

const conf = await mockServer.patchDocumentText(PM, up.documentId, { verifiedText: '원금 손실 가능성이 있습니다.', confirmed: true })
ok('text confirmed', conf.confirmed === true && conf.confirmedBy === 'USER-PM-001')
const facts = await mockServer.listGroundTruthFacts(PM, up.documentId)
await mockServer.verifyGroundTruthFact(PM, facts.items[0].factId, { verificationStatus: 'VERIFIED' })

const ana = await mockServer.createAnalysis(PM, {
  productDocumentId: up.documentId,
  evidenceDocumentIds: [21, 22],
  personaIds: [41, 42],
  redTeamPackId: 51,
}, 'k-a-1', 'GUARANTEE_MISUNDERSTANDING_HIGH')
ok('analysis created (202/CREATED)', ana.status === 'CREATED')

const anaDup = await mockServer.createAnalysis(PM, { productDocumentId: up.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-1')
ok('idempotent analysis (same key -> same id)', anaDup.analysisId === ana.analysisId)
await expectThrow('same normalized input -> 409', () =>
  mockServer.createAnalysis(PM, { productDocumentId: up.documentId, evidenceDocumentIds: [22, 21], personaIds: [42, 41], redTeamPackId: 51 }, 'k-a-duplicate'),
  'DUPLICATE_ANALYSIS_INPUT')

await expectThrow('result 409 before COMPLETED', () => mockServer.getAnalysisResult(PM, ana.analysisId), 'ANALYSIS_NOT_COMPLETED')

const done = await pollUntil(() => mockServer.getAnalysis(PM, ana.analysisId), (a) => a.status === 'COMPLETED')
ok('analysis COMPLETED', done.status === 'COMPLETED')

const result = await mockServer.getAnalysisResult(PM, ana.analysisId)
ok('riskScore == 82', result.riskScore === 82, `got ${result.riskScore}`)
ok('2 findings', result.findings.length === 2, `got ${result.findings.length}`)
ok('FND-001 statement present', !!result.findings[0].statement)
ok('HIGH finding has evidence', result.findings[0].severity === 'HIGH' && result.findings[0].evidenceReferences[0]?.evidenceDocumentId === 22)
ok('sourceReferences bound to source doc', result.findings[0].aiDetail?.sourceReferences?.[0]?.documentId === up.documentId)

const rev = await mockServer.createReview(PM, { analysisId: ana.analysisId, submissionComment: '검토 요청' }, 'k-r-1')
ok('review PENDING', rev.status === 'PENDING' && !('decision' in rev))
await expectThrow('duplicate review 409', () => mockServer.createReview(PM, { analysisId: ana.analysisId }, 'k-r-2'), 'REVIEW_ALREADY_EXISTS')

console.log('\n== RBAC ==')
await expectThrow('reviewer cannot create analysis (403)', () =>
  mockServer.createAnalysis(CR, { productDocumentId: up.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-cr'),
  'FORBIDDEN')
await expectThrow('PM cannot list risk patterns (403)', () => mockServer.listRiskPatterns(PM, {}), 'FORBIDDEN')

console.log('\n== REVIEW DECISION (CR) ==')
const inbox = await mockServer.listReviews(CR, {})
ok('review appears in inbox', inbox.items.some((r) => r.reviewId === rev.reviewId))
await expectThrow('approve without findings -> 400', () => mockServer.decideReview(CR, rev.reviewId, { status: 'APPROVED', selectedFindingIds: [] }), 'INVALID_FINDING_SELECTION')
const decided = await mockServer.decideReview(CR, rev.reviewId, { status: 'APPROVED', comment: '원금 손실 명시', selectedFindingIds: [result.findings[0].findingId] })
ok('approved -> RiskPattern promoted', decided.status === 'APPROVED' && decided.riskPatternIds.length === 1)
await expectThrow('re-decide -> 409', () => mockServer.decideReview(CR, rev.reviewId, { status: 'REJECTED', comment: 'x' }), 'REVIEW_ALREADY_DECIDED')

console.log('\n== GUARDFIT ==')
const gfa = await mockServer.createGuardFitAction(CR, { riskPatternId: decided.riskPatternIds[0], actionType: 'WARNING', label: '원금 손실 가능', placement: '상단', required: true }, 'k-gfa-1')
ok('guardfit DRAFT created', gfa.status === 'DRAFT')
const upd = await mockServer.updateGuardFitAction(CR, gfa.actionId, { label: '원금 손실 가능', placement: '상단', required: true, status: 'APPROVED' })
ok('guardfit APPROVED', upd.status === 'APPROVED')
const pmView = await mockServer.listGuardFitActions(PM, {})
ok('PM sees only APPROVED', pmView.items.length > 0 && pmView.items.every((a) => a.status === 'APPROVED'))
ok('PM sees the approved action', pmView.items.some((a) => a.actionId === gfa.actionId))

console.log('\n== RETRYABLE FAILURE ==')
const prod2 = await mockServer.createProduct(PM, { name: '재시도 테스트', productType: 'LOAN' }, 'k-prod-2')
const up2 = await mockServer.uploadDocument(PM, prod2.productId, { name: 's2.pdf', type: 'application/pdf', size: 1000000 }, 'k-doc-2')
extract(up2.documentId)
await pollUntil(() => mockServer.getDocument(PM, up2.documentId), (d) => d.extractStatus === 'READY')
await mockServer.patchDocumentText(PM, up2.documentId, { verifiedText: 'text', confirmed: true })
const facts2 = await mockServer.listGroundTruthFacts(PM, up2.documentId)
await mockServer.verifyGroundTruthFact(PM, facts2.items[0].factId, { verificationStatus: 'VERIFIED' })
const ana2 = await mockServer.createAnalysis(PM, { productDocumentId: up2.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-2', 'RATE_LIMIT_THEN_SUCCESS')
const failed = await pollUntil(() => mockServer.getAnalysis(PM, ana2.analysisId), (a) => a.status === 'FAILED')
ok('analysis FAILED retryable', failed.status === 'FAILED' && failed.error?.retryable === true)
await mockServer.retryAnalysis(PM, ana2.analysisId)
const recovered = await pollUntil(() => mockServer.getAnalysis(PM, ana2.analysisId), (a) => a.status === 'COMPLETED')
ok('retry -> COMPLETED', recovered.status === 'COMPLETED')

console.log(`\n== RESULT: ${pass} passed, ${fail} failed ==`)
process.exit(fail ? 1 : 0)
