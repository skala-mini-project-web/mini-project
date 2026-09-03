// Headless E2E smoke test of the mock backend against the spec.
// Run: node scripts/smoke.mjs
import { mockServer, ingestAnalysis, ingestExtraction } from '../src/api/mock/server.js'
// The client (api layer) performs real extraction in the browser; here we
// simulate that step by ingesting extracted text after upload.
const extract = (id, text = '원금 손실 가능성이 있습니다.') => ingestExtraction(id, { text, method: 'pdf-text' })

const PM = { userId: 'USER-PM-001', role: 'PRODUCT_MANAGER' }
const PM_B = { userId: 'USER-PM-002', role: 'PRODUCT_MANAGER' }
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
async function expectValidationError(name, fn, field) {
  try {
    await fn()
    fail++
    console.log(`  FAIL  ${name} (no error thrown)`)
  } catch (e) {
    ok(name, e.status === 400 && e.errorCode === 'VALIDATION_ERROR' && e.fieldErrors?.some((item) => item.field === field), `got ${e.errorCode}/${e.status}`)
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
await expectThrow('changed product payload with same key -> conflict', () =>
  mockServer.createProduct(PM, { name: '변경된 투자상품', productType: 'INVESTMENT', description: 'x' }, 'k-prod-1'),
  'IDEMPOTENCY_KEY_REUSED')
const pmBProductWithSharedKey = await mockServer.createProduct(PM_B, { name: 'PM B 투자상품', productType: 'INVESTMENT', description: 'x' }, 'k-prod-1')
ok('same operation key is isolated by user', pmBProductWithSharedKey.productId !== prod.productId)

const up = await mockServer.uploadDocument(PM, prod.productId, { name: 'sample.pdf', type: 'application/pdf', size: 2000000 }, 'k-prod-1')
ok('cross-operation key reuse creates the correct operation response', up.status === 'UPLOADED' && !!up.documentId)
const upDup = await mockServer.uploadDocument(PM, prod.productId, { name: 'sample.pdf', type: 'application/pdf', size: 2000000 }, 'k-prod-1')
ok('idempotent upload returns original response', JSON.stringify(upDup) === JSON.stringify(up))
const productAfterUploadRetry = await mockServer.getProduct(PM, prod.productId)
ok('upload retry creates no duplicate document', productAfterUploadRetry.documents.filter((document) => document.documentId === up.documentId).length === 1)
extract(up.documentId)

const readyDoc = await pollUntil(() => mockServer.getDocument(PM, up.documentId), (d) => d.extractStatus === 'READY')
ok('extraction reached READY', readyDoc.extractStatus === 'READY')
ok('rawExtractedText present', !!readyDoc.rawExtractedText)

await expectThrow('analysis blocked before confirm', () =>
  mockServer.createAnalysis(PM, { productDocumentId: up.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-early'),
  'DOCUMENT_NOT_CONFIRMED')

await expectValidationError('over-limit confirmed text rejected', () =>
  mockServer.patchDocumentText(PM, up.documentId, { verifiedText: '가'.repeat(10001), confirmed: true }),
  'verifiedText')
const unchangedDoc = await mockServer.getDocument(PM, up.documentId)
ok('over-limit confirmed text does not mutate document', unchangedDoc.verifiedText === readyDoc.verifiedText && unchangedDoc.confirmed === readyDoc.confirmed)

const conf = await mockServer.patchDocumentText(PM, up.documentId, { verifiedText: '원금 손실 가능성이 있습니다.', confirmed: true })
ok('text confirmed', conf.confirmed === true && conf.confirmedBy === 'USER-PM-001')
const facts = await mockServer.listGroundTruthFacts(PM, up.documentId)
await mockServer.verifyGroundTruthFact(PM, facts.items[0].factId, { verificationStatus: 'VERIFIED' })

const localAnalysis = await mockServer.createAnalysis(PM, {
  productDocumentId: up.documentId,
  evidenceDocumentIds: [21, 22],
  personaIds: [41, 42],
  redTeamPackId: 51,
}, 'k-a-local-provenance', 'GUARANTEE_MISUNDERSTANDING_HIGH', { local: true })
ingestAnalysis(localAnalysis.analysisId, {
  findings: [],
  riskScore: 0,
  providerType: 'LOCAL_OLLAMA',
  modelVersion: 'qwen2.5:7b-instruct',
  grounding: [],
})
const localResult = await mockServer.getAnalysisResult(PM, localAnalysis.analysisId)
ok('local result identifies its actual provider and model', localResult.provenance.providerType === 'LOCAL_OLLAMA' && localResult.provenance.modelVersion === 'qwen2.5:7b-instruct')
ok('local result cannot claim the deterministic fixture model', localResult.provenance.modelVersion !== 'DETERMINISTIC_FIXTURE_V1')

const ana = await mockServer.createAnalysis(PM, {
  productDocumentId: up.documentId,
  evidenceDocumentIds: [21, 22],
  personaIds: [41, 42],
  redTeamPackId: 51,
}, 'k-a-1', 'GUARANTEE_MISUNDERSTANDING_HIGH')
ok('analysis created (202/CREATED)', ana.status === 'CREATED')

const anaDup = await mockServer.createAnalysis(PM, {
  productDocumentId: up.documentId,
  evidenceDocumentIds: [21, 22],
  personaIds: [41, 42],
  redTeamPackId: 51,
}, 'k-a-1', 'GUARANTEE_MISUNDERSTANDING_HIGH')
ok('idempotent analysis (same key -> same id)', anaDup.analysisId === ana.analysisId)
await expectThrow('changed analysis payload with same key -> conflict', () =>
  mockServer.createAnalysis(PM, { productDocumentId: up.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-1', 'GUARANTEE_MISUNDERSTANDING_HIGH'),
  'IDEMPOTENCY_KEY_REUSED')
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
ok('completed analysis exposes its input fingerprint', typeof result.inputHash === 'string' && result.inputHash.length === 64)

const originalProvenance = JSON.stringify(result.provenance)
const originalFacts = JSON.stringify(result.groundTruthFacts)
await mockServer.verifyGroundTruthFact(PM, facts.items[0].factId, {
  verificationStatus: 'VERIFIED',
  value: '사후 변경된 원금 손실 사실',
})
const resultAfterFactEdit = await mockServer.getAnalysisResult(PM, ana.analysisId)
ok('fact edit does not rewrite completed facts', JSON.stringify(resultAfterFactEdit.groundTruthFacts) === originalFacts)
ok('fact edit does not rewrite completed input hash', resultAfterFactEdit.inputHash === result.inputHash)
ok('fact edit does not rewrite completion time or generator identity', JSON.stringify(resultAfterFactEdit.provenance) === originalProvenance)

await mockServer.verifyGroundTruthFact(PM, facts.items[0].factId, { verificationStatus: 'REJECTED' })
const resultAfterFactRejection = await mockServer.getAnalysisResult(PM, ana.analysisId)
const localResultAfterFactRejection = await mockServer.getAnalysisResult(PM, localAnalysis.analysisId)
ok('fact rejection does not rewrite completed facts', JSON.stringify(resultAfterFactRejection.groundTruthFacts) === originalFacts)
ok('repeated reads preserve old input hash and generatedAt', resultAfterFactRejection.inputHash === result.inputHash && resultAfterFactRejection.provenance.generatedAt === result.provenance.generatedAt)
ok('repeated reads preserve Mock provider/model provenance', JSON.stringify(resultAfterFactRejection.provenance) === originalProvenance)
ok('repeated reads preserve local provider/model provenance', JSON.stringify(localResultAfterFactRejection.provenance) === JSON.stringify(localResult.provenance))

await expectValidationError('over-limit review submission comment rejected', () =>
  mockServer.createReview(PM, { analysisId: ana.analysisId, submissionComment: '가'.repeat(501) }, 'k-r-over-limit'),
  'submissionComment')
const reviewAfterRejectedSubmission = await mockServer.getReviewByAnalysis(PM, ana.analysisId)
ok('over-limit review submission does not create review', reviewAfterRejectedSubmission === null)

const reviewCountBeforeCreate = (await mockServer.listReviews(CR, {})).totalElements
const auditCountBeforeReviewCreate = (await mockServer.listAuditLogs(CR, {})).items.length
const rev = await mockServer.createReview(PM, { analysisId: ana.analysisId, submissionComment: '검토 요청' }, 'k-r-1')
ok('review PENDING', rev.status === 'PENDING' && !('decision' in rev))
const revRetry = await mockServer.createReview(PM, { analysisId: ana.analysisId, submissionComment: '검토 요청' }, 'k-r-1')
ok('lost-response review retry returns original response', JSON.stringify(revRetry) === JSON.stringify(rev))
await expectThrow('changed review payload with same key -> conflict', () =>
  mockServer.createReview(PM, { analysisId: ana.analysisId, submissionComment: '변경된 검토 요청' }, 'k-r-1'),
  'IDEMPOTENCY_KEY_REUSED')
const reviewCountAfterRetries = (await mockServer.listReviews(CR, {})).totalElements
const auditCountAfterReviewRetries = (await mockServer.listAuditLogs(CR, {})).items.length
ok('review retry and conflict create no duplicate mutation', reviewCountAfterRetries === reviewCountBeforeCreate + 1)
ok('review retry and conflict create no duplicate audit', auditCountAfterReviewRetries === auditCountBeforeReviewCreate + 1)
await expectThrow('duplicate review 409', () => mockServer.createReview(PM, { analysisId: ana.analysisId }, 'k-r-2'), 'REVIEW_ALREADY_EXISTS')

console.log('\n== RBAC ==')
await expectThrow('reviewer cannot create analysis (403)', () =>
  mockServer.createAnalysis(CR, { productDocumentId: up.documentId, evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-cr'),
  'FORBIDDEN')
await expectThrow('PM cannot list risk patterns (403)', () => mockServer.listRiskPatterns(PM, {}), 'FORBIDDEN')

console.log('\n== PM HORIZONTAL AUTHORIZATION ==')
const pmBDocumentBefore = await mockServer.getDocument(PM_B, 'PDOC-PM-B-001')
const pmBFactsBefore = await mockServer.listGroundTruthFacts(PM_B, 'PDOC-PM-B-001')
const pmBAnalysisStatusBefore = await mockServer.getAnalysis(PM_B, 'ANL-PM-B-001')
const pmBAnalysisBefore = await mockServer.getAnalysisResult(PM_B, 'ANL-PM-B-001')
const pmBReviewBefore = await mockServer.getReview(PM_B, 'REV-PM-B-001')
const pmBProductBefore = await mockServer.getProduct(PM_B, 'PROD-PM-B-001')
const reviewCountBeforeDeniedCalls = (await mockServer.listReviews(CR, {})).totalElements
const auditCountBeforeDeniedCalls = (await mockServer.listAuditLogs(CR, {})).items.length

await expectThrow('PM A cannot read PM B document', () => mockServer.getDocument(PM, 'PDOC-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot patch PM B document', () =>
  mockServer.patchDocumentText(PM, 'PDOC-PM-B-001', { verifiedText: '침범한 텍스트', confirmed: true }),
  'FORBIDDEN')
await expectThrow('PM A cannot retry PM B document', () => mockServer.retryDocument(PM, 'PDOC-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot list PM B facts', () => mockServer.listGroundTruthFacts(PM, 'PDOC-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot verify PM B fact', () =>
  mockServer.verifyGroundTruthFact(PM, 'FACT-PM-B-001', { verificationStatus: 'REJECTED', value: '침범한 사실' }),
  'FORBIDDEN')
await expectThrow('PM A cannot create analysis for PM B document', () =>
  mockServer.createAnalysis(PM, { productDocumentId: 'PDOC-PM-B-001', evidenceDocumentIds: [21], personaIds: [41], redTeamPackId: 51 }, 'k-a-foreign'),
  'FORBIDDEN')
await expectThrow('PM A cannot read PM B analysis', () => mockServer.getAnalysis(PM, 'ANL-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot read PM B analysis result', () => mockServer.getAnalysisResult(PM, 'ANL-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot retry PM B analysis', () => mockServer.retryAnalysis(PM, 'ANL-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot create review for PM B analysis', () =>
  mockServer.createReview(PM, { analysisId: 'ANL-PM-B-001', submissionComment: '침범한 검토 요청' }, 'k-r-foreign'),
  'FORBIDDEN')
await expectThrow('PM A cannot read PM B review', () => mockServer.getReview(PM, 'REV-PM-B-001'), 'FORBIDDEN')
await expectThrow('PM A cannot read PM B review by analysis', () => mockServer.getReviewByAnalysis(PM, 'ANL-PM-B-001'), 'FORBIDDEN')

const pmBDocumentAfter = await mockServer.getDocument(PM_B, 'PDOC-PM-B-001')
const pmBFactsAfter = await mockServer.listGroundTruthFacts(PM_B, 'PDOC-PM-B-001')
const pmBAnalysisStatusAfter = await mockServer.getAnalysis(PM_B, 'ANL-PM-B-001')
const pmBAnalysisAfter = await mockServer.getAnalysisResult(PM_B, 'ANL-PM-B-001')
const pmBReviewAfter = await mockServer.getReview(PM_B, 'REV-PM-B-001')
const pmBProductAfter = await mockServer.getProduct(PM_B, 'PROD-PM-B-001')
const reviewCountAfterDeniedCalls = (await mockServer.listReviews(CR, {})).totalElements
const auditCountAfterDeniedCalls = (await mockServer.listAuditLogs(CR, {})).items.length
ok('denied document calls do not mutate PM B document', JSON.stringify(pmBDocumentAfter) === JSON.stringify(pmBDocumentBefore))
ok('denied fact calls do not mutate PM B facts', JSON.stringify(pmBFactsAfter) === JSON.stringify(pmBFactsBefore))
ok('denied analysis calls do not mutate or create analysis', pmBAnalysisAfter.riskScore === pmBAnalysisBefore.riskScore && pmBAnalysisStatusAfter.attemptCount === pmBAnalysisStatusBefore.attemptCount && pmBProductAfter.analyses.length === pmBProductBefore.analyses.length)
ok('denied review calls do not mutate or create review', JSON.stringify(pmBReviewAfter) === JSON.stringify(pmBReviewBefore) && reviewCountAfterDeniedCalls === reviewCountBeforeDeniedCalls)
ok('denied calls do not create audit events', auditCountAfterDeniedCalls === auditCountBeforeDeniedCalls)
ok('reviewer can read PM B review', (await mockServer.getReview(CR, 'REV-PM-B-001')).reviewId === 'REV-PM-B-001')
ok('reviewer can read linked PM B analysis result', (await mockServer.getAnalysisResult(CR, 'ANL-PM-B-001')).analysisId === 'ANL-PM-B-001')
await expectThrow('reviewer cannot read PM document', () => mockServer.getDocument(CR, 'PDOC-PM-B-001'), 'FORBIDDEN')
await expectThrow('reviewer cannot read PM analysis status', () => mockServer.getAnalysis(CR, 'ANL-PM-B-001'), 'FORBIDDEN')

console.log('\n== REVIEW DECISION (CR) ==')
const inbox = await mockServer.listReviews(CR, {})
ok('review appears in inbox', inbox.items.some((r) => r.reviewId === rev.reviewId))
await expectValidationError('over-limit review decision comment rejected', () =>
  mockServer.decideReview(CR, rev.reviewId, { status: 'APPROVED', comment: '가'.repeat(501), selectedFindingIds: [result.findings[0].findingId] }),
  'comment')
const reviewAfterRejectedDecision = await mockServer.getReview(CR, rev.reviewId)
ok('over-limit review decision does not mutate review', reviewAfterRejectedDecision.status === 'PENDING' && reviewAfterRejectedDecision.comment === null && reviewAfterRejectedDecision.reviewerId === null)
await expectThrow('approve without findings -> 400', () => mockServer.decideReview(CR, rev.reviewId, { status: 'APPROVED', selectedFindingIds: [] }), 'INVALID_FINDING_SELECTION')
const reviewBeforeDuplicateSelection = await mockServer.getReview(CR, rev.reviewId)
const productBeforeDuplicateSelection = await mockServer.getProduct(PM, prod.productId)
const riskPatternCountBeforeDuplicateSelection = (await mockServer.listRiskPatterns(CR, {})).items.length
const auditCountBeforeDuplicateSelection = (await mockServer.listAuditLogs(CR, {})).items.length
await expectThrow('duplicate selected findings -> 400', () =>
  mockServer.decideReview(CR, rev.reviewId, {
    status: 'APPROVED',
    selectedFindingIds: [result.findings[0].findingId, result.findings[0].findingId],
  }),
  'INVALID_FINDING_SELECTION')
const reviewAfterDuplicateSelection = await mockServer.getReview(CR, rev.reviewId)
const productAfterDuplicateSelection = await mockServer.getProduct(PM, prod.productId)
const riskPatternCountAfterDuplicateSelection = (await mockServer.listRiskPatterns(CR, {})).items.length
const auditCountAfterDuplicateSelection = (await mockServer.listAuditLogs(CR, {})).items.length
ok('duplicate selection does not mutate review', JSON.stringify(reviewAfterDuplicateSelection) === JSON.stringify(reviewBeforeDuplicateSelection))
ok('duplicate selection does not mutate product', productAfterDuplicateSelection.status === productBeforeDuplicateSelection.status)
ok('duplicate selection does not create Risk Patterns', riskPatternCountAfterDuplicateSelection === riskPatternCountBeforeDuplicateSelection)
ok('duplicate selection does not create audit events', auditCountAfterDuplicateSelection === auditCountBeforeDuplicateSelection)
const decided = await mockServer.decideReview(CR, rev.reviewId, { status: 'APPROVED', comment: '원금 손실 명시', selectedFindingIds: [result.findings[0].findingId] })
ok('approved -> RiskPattern promoted', decided.status === 'APPROVED' && decided.riskPatternIds.length === 1)
const promotedPatterns = await mockServer.listRiskPatterns(CR, {})
const promoted = promotedPatterns.items.find((item) => item.riskPatternId === decided.riskPatternIds[0])
ok('promoted RiskPattern has concise title', !!promoted?.title?.trim() && promoted.title !== result.findings[0].statement && promoted.title.length <= 40)
ok('promoted RiskPattern keeps full Finding statement', promoted?.findingStatement === result.findings[0].statement)
ok('promoted RiskPattern trace relationship', promoted?.sourceFindingId === result.findings[0].findingId && promoted.sourceReviewId === rev.reviewId && promoted.sourceAnalysisId === ana.analysisId)
await expectThrow('re-decide -> 409', () => mockServer.decideReview(CR, rev.reviewId, { status: 'REJECTED', comment: 'x' }), 'REVIEW_ALREADY_DECIDED')

console.log('\n== LATEST ANALYSIS REVIEW GUARD ==')
const revisionProduct = await mockServer.createProduct(PM, { name: '분석 개정 경쟁 상품', productType: 'INVESTMENT' }, 'k-prod-revision')
const revisionUpload = await mockServer.uploadDocument(PM, revisionProduct.productId, { name: 'revision.pdf', type: 'application/pdf', size: 100000 }, 'k-doc-revision')
extract(revisionUpload.documentId)
await pollUntil(() => mockServer.getDocument(PM, revisionUpload.documentId), (document) => document.extractStatus === 'READY')
await mockServer.patchDocumentText(PM, revisionUpload.documentId, { verifiedText: '원금 손실 가능성이 있습니다.', confirmed: true })
const revisionFacts = await mockServer.listGroundTruthFacts(PM, revisionUpload.documentId)
await mockServer.verifyGroundTruthFact(PM, revisionFacts.items[0].factId, { verificationStatus: 'VERIFIED' })

const olderAnalysis = await mockServer.createAnalysis(PM, {
  productDocumentId: revisionUpload.documentId,
  evidenceDocumentIds: [21],
  personaIds: [41],
  redTeamPackId: 51,
}, 'k-a-revision-old', 'GUARANTEE_MISUNDERSTANDING_HIGH')
await pollUntil(() => mockServer.getAnalysis(PM, olderAnalysis.analysisId), (analysis) => analysis.status === 'COMPLETED')
const olderReview = await mockServer.createReview(PM, { analysisId: olderAnalysis.analysisId, submissionComment: '초기 분석 검토' }, 'k-r-revision-old')

const newerAnalysis = await mockServer.createAnalysis(PM, {
  productDocumentId: revisionUpload.documentId,
  evidenceDocumentIds: [21, 22],
  personaIds: [41],
  redTeamPackId: 51,
}, 'k-a-revision-new', 'GUARANTEE_MISUNDERSTANDING_HIGH')
await pollUntil(() => mockServer.getAnalysis(PM, newerAnalysis.analysisId), (analysis) => analysis.status === 'COMPLETED')
const newerResult = await mockServer.getAnalysisResult(PM, newerAnalysis.analysisId)
const newerReview = await mockServer.createReview(PM, { analysisId: newerAnalysis.analysisId, submissionComment: '개정 분석 검토' }, 'k-r-revision-new')
ok('latest analysis can be resubmitted while an earlier review exists', newerReview.status === 'PENDING')
await mockServer.decideReview(CR, newerReview.reviewId, {
  status: 'APPROVED',
  comment: '최신 분석 승인',
  selectedFindingIds: [newerResult.findings[0].findingId],
})
const newerReviewRetry = await mockServer.createReview(PM, { analysisId: newerAnalysis.analysisId, submissionComment: '개정 분석 검토' }, 'k-r-revision-new')
ok('latest review submission remains idempotent after its terminal decision', JSON.stringify(newerReviewRetry) === JSON.stringify(newerReview))

const revisionProductBeforeStaleCalls = await mockServer.getProduct(PM, revisionProduct.productId)
const olderReviewBeforeStaleCalls = await mockServer.getReview(CR, olderReview.reviewId)
const newerReviewBeforeStaleCalls = await mockServer.getReview(CR, newerReview.reviewId)
const revisionReviewCountBeforeStaleCalls = (await mockServer.listReviews(CR, {})).totalElements
const revisionPatternsBeforeStaleCalls = (await mockServer.listRiskPatterns(CR, {})).items
const revisionAuditsBeforeStaleCalls = (await mockServer.listAuditLogs(CR, {})).items
await expectThrow('stale analysis review submission -> deterministic 409', () =>
  mockServer.createReview(PM, { analysisId: olderAnalysis.analysisId, submissionComment: '초기 분석 검토' }, 'k-r-revision-old'),
  'STALE_ANALYSIS_REVISION')
await expectThrow('stale review decision -> deterministic 409', () =>
  mockServer.decideReview(CR, olderReview.reviewId, { status: 'REJECTED', comment: '오래된 분석 반려' }),
  'STALE_ANALYSIS_REVISION')
const revisionProductAfterStaleCalls = await mockServer.getProduct(PM, revisionProduct.productId)
const olderReviewAfterStaleCalls = await mockServer.getReview(CR, olderReview.reviewId)
const newerReviewAfterStaleCalls = await mockServer.getReview(CR, newerReview.reviewId)
const revisionReviewCountAfterStaleCalls = (await mockServer.listReviews(CR, {})).totalElements
const revisionPatternsAfterStaleCalls = (await mockServer.listRiskPatterns(CR, {})).items
const revisionAuditsAfterStaleCalls = (await mockServer.listAuditLogs(CR, {})).items
ok('stale review cannot overwrite the latest approved product decision', revisionProductBeforeStaleCalls.status === 'APPROVED' && revisionProductAfterStaleCalls.status === 'APPROVED')
ok('stale operations leave old and new reviews unchanged', JSON.stringify(olderReviewAfterStaleCalls) === JSON.stringify(olderReviewBeforeStaleCalls) && JSON.stringify(newerReviewAfterStaleCalls) === JSON.stringify(newerReviewBeforeStaleCalls))
ok('stale operations create no review', revisionReviewCountAfterStaleCalls === revisionReviewCountBeforeStaleCalls)
ok('stale operations promote no Risk Patterns', JSON.stringify(revisionPatternsAfterStaleCalls) === JSON.stringify(revisionPatternsBeforeStaleCalls))
ok('stale operations create no audit events', JSON.stringify(revisionAuditsAfterStaleCalls) === JSON.stringify(revisionAuditsBeforeStaleCalls))

console.log('\n== NO-FINDING REVIEW APPROVAL ==')
const cleanProduct = await mockServer.createProduct(PM, { name: '정상 저위험 상품', productType: 'SAVINGS' }, 'k-prod-clean')
const cleanUpload = await mockServer.uploadDocument(PM, cleanProduct.productId, { name: 'clean.pdf', type: 'application/pdf', size: 100000 }, 'k-doc-clean')
extract(cleanUpload.documentId, '위험과 비용을 명확히 안내합니다.')
await pollUntil(() => mockServer.getDocument(PM, cleanUpload.documentId), (document) => document.extractStatus === 'READY')
await mockServer.patchDocumentText(PM, cleanUpload.documentId, { verifiedText: '위험과 비용을 명확히 안내합니다.', confirmed: true })
const cleanFacts = await mockServer.listGroundTruthFacts(PM, cleanUpload.documentId)
await mockServer.verifyGroundTruthFact(PM, cleanFacts.items[0].factId, { verificationStatus: 'VERIFIED' })
const cleanAnalysis = await mockServer.createAnalysis(PM, {
  productDocumentId: cleanUpload.documentId,
  evidenceDocumentIds: [21],
  personaIds: [41],
  redTeamPackId: 51,
}, 'k-a-clean', 'NO_FINDING')
await pollUntil(() => mockServer.getAnalysis(PM, cleanAnalysis.analysisId), (analysis) => analysis.status === 'COMPLETED')
const cleanResult = await mockServer.getAnalysisResult(PM, cleanAnalysis.analysisId)
ok('clean analysis has zero findings', cleanResult.findings.length === 0)
const cleanReview = await mockServer.createReview(PM, { analysisId: cleanAnalysis.analysisId, submissionComment: '정상 분석 검토 요청' }, 'k-r-clean')
const cleanRiskPatternCountBefore = (await mockServer.listRiskPatterns(CR, {})).items.length
const cleanAuditCountBefore = (await mockServer.listAuditLogs(CR, {})).items.length
const cleanDecision = await mockServer.decideReview(CR, cleanReview.reviewId, { status: 'APPROVED', comment: '이상 없음', selectedFindingIds: [] })
const approvedCleanReview = await mockServer.getReview(CR, cleanReview.reviewId)
const approvedCleanProduct = await mockServer.getProduct(PM, cleanProduct.productId)
const cleanRiskPatternCountAfter = (await mockServer.listRiskPatterns(CR, {})).items.length
const cleanAuditLogsAfter = (await mockServer.listAuditLogs(CR, {})).items
ok('clean review approves with zero promoted patterns', cleanDecision.status === 'APPROVED' && cleanDecision.riskPatternIds.length === 0)
ok('clean review and product are approved', approvedCleanReview.status === 'APPROVED' && approvedCleanReview.selectedFindingIds.length === 0 && approvedCleanProduct.status === 'APPROVED')
ok('clean approval creates no Risk Patterns', cleanRiskPatternCountAfter === cleanRiskPatternCountBefore)
ok('clean approval creates normal review audit event', cleanAuditLogsAfter.length === cleanAuditCountBefore + 1 && cleanAuditLogsAfter.some((log) => log.resourceType === 'REVIEW' && log.resourceId === cleanReview.reviewId && log.action === 'REVIEW_APPROVED'))

console.log('\n== GUARDFIT ==')
const guardFitCountBeforeRejectedCreates = (await mockServer.listGuardFitActions(CR, {})).items.length
await expectValidationError('over-limit GuardFit label rejected on create', () =>
  mockServer.createGuardFitAction(CR, { riskPatternId: decided.riskPatternIds[0], actionType: 'WARNING', label: '가'.repeat(101), placement: '상단', required: true }, 'k-gfa-label-over-limit'),
  'label')
const guardFitCountAfterRejectedLabelCreate = (await mockServer.listGuardFitActions(CR, {})).items.length
ok('over-limit GuardFit label does not create action', guardFitCountAfterRejectedLabelCreate === guardFitCountBeforeRejectedCreates)
await expectValidationError('over-limit GuardFit placement rejected on create', () =>
  mockServer.createGuardFitAction(CR, { riskPatternId: decided.riskPatternIds[0], actionType: 'WARNING', label: '원금 손실 가능', placement: '가'.repeat(101), required: true }, 'k-gfa-placement-over-limit'),
  'placement')
const guardFitCountAfterRejectedPlacementCreate = (await mockServer.listGuardFitActions(CR, {})).items.length
ok('over-limit GuardFit placement does not create action', guardFitCountAfterRejectedPlacementCreate === guardFitCountBeforeRejectedCreates)

const guardFitAuditCountBeforeCreate = (await mockServer.listAuditLogs(CR, {})).items.length
const gfa = await mockServer.createGuardFitAction(CR, { riskPatternId: decided.riskPatternIds[0], actionType: 'WARNING', label: '원금 손실 가능', placement: '상단', required: true }, 'k-gfa-1')
ok('guardfit DRAFT created', gfa.status === 'DRAFT')
const gfaRetry = await mockServer.createGuardFitAction(CR, { riskPatternId: decided.riskPatternIds[0], actionType: 'WARNING', label: '원금 손실 가능', placement: '상단', required: true }, 'k-gfa-1')
ok('idempotent GuardFit action returns original response', JSON.stringify(gfaRetry) === JSON.stringify(gfa))
await expectThrow('changed GuardFit payload with same key -> conflict', () =>
  mockServer.createGuardFitAction(CR, { riskPatternId: decided.riskPatternIds[0], actionType: 'WARNING', label: '변경된 경고', placement: '상단', required: true }, 'k-gfa-1'),
  'IDEMPOTENCY_KEY_REUSED')
const guardFitCountAfterRetries = (await mockServer.listGuardFitActions(CR, {})).items.length
const guardFitAuditCountAfterRetries = (await mockServer.listAuditLogs(CR, {})).items.length
ok('GuardFit retry and conflict create no duplicate mutation', guardFitCountAfterRetries === guardFitCountBeforeRejectedCreates + 1)
ok('GuardFit retry and conflict create no duplicate audit', guardFitAuditCountAfterRetries === guardFitAuditCountBeforeCreate + 1)
await expectValidationError('over-limit GuardFit label rejected on update', () =>
  mockServer.updateGuardFitAction(CR, gfa.actionId, { label: '가'.repeat(101), placement: '하단', required: false, status: 'APPROVED' }),
  'label')
const guardFitAfterRejectedLabelUpdate = (await mockServer.listGuardFitActions(CR, {})).items.find((a) => a.actionId === gfa.actionId)
ok('over-limit GuardFit label does not mutate action', guardFitAfterRejectedLabelUpdate?.label === '원금 손실 가능' && guardFitAfterRejectedLabelUpdate?.placement === '상단' && guardFitAfterRejectedLabelUpdate?.required === true && guardFitAfterRejectedLabelUpdate?.status === 'DRAFT')
await expectValidationError('over-limit GuardFit placement rejected on update', () =>
  mockServer.updateGuardFitAction(CR, gfa.actionId, { label: '변경 라벨', placement: '가'.repeat(101), required: false, status: 'APPROVED' }),
  'placement')
const guardFitAfterRejectedPlacementUpdate = (await mockServer.listGuardFitActions(CR, {})).items.find((a) => a.actionId === gfa.actionId)
ok('over-limit GuardFit placement does not mutate action', guardFitAfterRejectedPlacementUpdate?.label === '원금 손실 가능' && guardFitAfterRejectedPlacementUpdate?.placement === '상단' && guardFitAfterRejectedPlacementUpdate?.required === true && guardFitAfterRejectedPlacementUpdate?.status === 'DRAFT')
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
