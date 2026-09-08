import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { ApiError, errorKind } from '../src/api/errors.js'
import { ingestAnalysis, ingestExtraction, mockServer, resetStore } from '../src/api/mock/server.js'

const pmA = { userId: 'USER-PM-001', role: 'PRODUCT_MANAGER' }
const pmB = { userId: 'USER-PM-002', role: 'PRODUCT_MANAGER' }
const reviewer = { userId: 'USER-CR-001', role: 'COMPLIANCE_REVIEWER' }

resetStore()
await Promise.all(Array.from({ length: 25 }, (_, index) => mockServer.createProduct(pmA, {
  name: `회귀 스마트 상품 ${String(index + 1).padStart(2, '0')}`,
  productType: index % 3 === 0 ? 'INVESTMENT' : index % 3 === 1 ? 'LOAN' : 'SAVINGS',
  description: '페이지네이션 회귀 데이터',
}, `pagination-${index}`)))

const first = await mockServer.listProducts(pmA, { page: 0, size: 12 })
const second = await mockServer.listProducts(pmA, { page: 1, size: 12 })
const third = await mockServer.listProducts(pmA, { page: 2, size: 12 })
assert.equal(first.totalElements, 26)
assert.equal(first.totalPages, 3)
assert.deepEqual([first.items.length, second.items.length, third.items.length], [12, 12, 2])
const traversedIds = [...first.items, ...second.items, ...third.items].map(({ productId }) => productId)
assert.equal(new Set(traversedIds).size, 26)
assert.ok(Number(first.items[0].productId) > Number(first.items[1].productId))

const combined = await mockServer.listProducts(pmA, {
  page: 0,
  size: 12,
  q: 'ㅅㅁㅌ',
  productType: 'INVESTMENT',
  status: 'DRAFT',
})
assert.equal(combined.totalElements, 9)
assert.ok(combined.items.every((product) =>
  product.name.includes('스마트')
  && product.productType === 'INVESTMENT'
  && product.status === 'DRAFT'))

const ownerB = await mockServer.listProducts(pmB, { page: 0, size: 100 })
assert.equal(ownerB.totalElements, 1)
assert.ok(ownerB.items.every(({ ownerId }) => ownerId === pmB.userId))
const allAuthorized = await mockServer.listProducts(reviewer, { page: 0, size: 100 })
assert.equal(allAuthorized.totalElements, 27)

const mixedIdUpload = await mockServer.uploadDocument(pmB, 'PROD-PM-B-001', {
  name: 'PM_B_개정.pdf',
  type: 'application/pdf',
  size: 1024,
}, 'pagination-mixed-id-document')
ingestExtraction(mixedIdUpload.documentId, {
  text: '개정 상품은 시장 상황에 따라 원금 전액 손실이 발생할 수 있습니다.',
  method: 'pdf-text',
})
await mockServer.patchDocumentText(pmB, mixedIdUpload.documentId, {
  verifiedText: '개정 상품은 시장 상황에 따라 원금 전액 손실이 발생할 수 있습니다.',
  confirmed: true,
})
const mixedIdFacts = await mockServer.listGroundTruthFacts(pmB, mixedIdUpload.documentId)
await mockServer.verifyGroundTruthFact(pmB, mixedIdFacts.items[0].factId, {
  verificationStatus: 'VERIFIED',
})
const mixedIdAnalysis = await mockServer.createAnalysis(pmB, {
  productDocumentId: mixedIdUpload.documentId,
  evidenceDocumentIds: [21],
  personaIds: [41],
  redTeamPackId: 51,
}, 'pagination-mixed-id-analysis', 'NO_FINDING')
assert.equal(typeof mixedIdAnalysis.analysisId, 'number')
const mixedIdLifecycle = await mockServer.listProducts(pmB, { page: 0, size: 100 })
assert.equal(mixedIdLifecycle.items[0].latestAnalysis.analysisId, mixedIdAnalysis.analysisId)
assert.equal(mixedIdLifecycle.items[0].status, 'RUNNING')
assert.equal((await mockServer.listProducts(pmB, {
  page: 0,
  size: 100,
  status: 'RUNNING',
})).totalElements, 1)
assert.equal((await mockServer.listProducts(pmB, {
  page: 0,
  size: 100,
  status: 'IN_REVIEW',
})).totalElements, 0)

const productOne = (response) => response.items.find(({ productId }) => String(productId) === '1')
const lifecycleTotal = async (status) => (
  await mockServer.listProducts(pmA, { page: 0, size: 100, status })
).totalElements

assert.equal(productOne(allAuthorized).status, 'IN_REVIEW')
assert.equal(await lifecycleTotal('IN_REVIEW'), 1)

const rejected = await mockServer.decideReview(reviewer, 91, {
  status: 'REJECTED',
  comment: '최신 분석 전환 회귀 검증',
})
assert.equal(rejected.status, 'REJECTED')
const afterDecision = await mockServer.listProducts(pmA, { page: 0, size: 100 })
assert.equal(productOne(afterDecision).status, 'NEEDS_FIX')
assert.equal(await lifecycleTotal('NEEDS_FIX'), 1)

const upload = await mockServer.uploadDocument(pmA, 1, {
  name: '스마트인컴_개정.pdf',
  type: 'application/pdf',
  size: 1024,
}, 'pagination-lifecycle-document')
ingestExtraction(upload.documentId, {
  text: '개정 상품은 시장 상황에 따라 원금 전액 손실이 발생할 수 있습니다.',
  method: 'pdf-text',
})
await mockServer.patchDocumentText(pmA, upload.documentId, {
  verifiedText: '개정 상품은 시장 상황에 따라 원금 전액 손실이 발생할 수 있습니다.',
  confirmed: true,
})
const facts = await mockServer.listGroundTruthFacts(pmA, upload.documentId)
await mockServer.verifyGroundTruthFact(pmA, facts.items[0].factId, {
  verificationStatus: 'VERIFIED',
})

const createdAnalysis = await mockServer.createAnalysis(pmA, {
  productDocumentId: upload.documentId,
  evidenceDocumentIds: [21],
  personaIds: [41],
  redTeamPackId: 51,
}, 'pagination-lifecycle-analysis', 'NO_FINDING')
assert.equal(createdAnalysis.status, 'CREATED')
const whileCreated = await mockServer.listProducts(pmA, { page: 0, size: 100 })
assert.equal(productOne(whileCreated).status, 'RUNNING')
assert.equal(productOne(whileCreated).latestAnalysis.analysisId, createdAnalysis.analysisId)
assert.equal(await lifecycleTotal('RUNNING'), 1)
assert.equal(await lifecycleTotal('NEEDS_FIX'), 0)

ingestAnalysis(createdAnalysis.analysisId, {
  riskScore: 0,
  findings: [],
  grounding: [],
  providerType: 'MOCK',
  modelVersion: 'DETERMINISTIC_FIXTURE_V1',
})
const afterCompletion = await mockServer.listProducts(pmA, { page: 0, size: 100 })
assert.equal(productOne(afterCompletion).status, 'ANALYZED')
assert.equal(productOne(afterCompletion).latestAnalysis.status, 'COMPLETED')
assert.equal(await lifecycleTotal('ANALYZED'), 1)
assert.equal(await lifecycleTotal('RUNNING'), 0)

const pendingReview = await mockServer.createReview(pmA, {
  analysisId: createdAnalysis.analysisId,
  submissionComment: '개정 분석 검토 요청',
}, 'pagination-lifecycle-review')
assert.equal(pendingReview.status, 'PENDING')
const afterReview = await mockServer.listProducts(pmA, { page: 0, size: 100 })
assert.equal(productOne(afterReview).status, 'IN_REVIEW')
assert.equal(await lifecycleTotal('IN_REVIEW'), 1)
assert.equal(await lifecycleTotal('ANALYZED'), 0)

const apiSource = await readFile(new URL('../src/api/index.js', import.meta.url), 'utf8')
const requests = []
const context = vm.createContext({ URLSearchParams })
const deps = {
  './mock/server.js': { mockServer: {}, ingestExtraction() {}, ingestAnalysis() {} },
  './client.js': {
    http: {
      get: async (path) => {
        requests.push(path)
        return { items: [], page: 2, size: 12, totalElements: 0, totalPages: 0 }
      },
    },
    uuid: () => 'pagination-contract',
  },
  './auth-context.js': { getAuth: () => pmA },
  './errors.js': { ApiError, errorKind },
  '../lib/extract.js': { extractDocument: () => { throw new Error('Unexpected extraction') } },
  '../lib/analyze.js': { analyzeDocument: () => { throw new Error('Unexpected analysis') } },
}
const apiModule = new vm.SourceTextModule(apiSource, {
  context,
  initializeImportMeta(meta) { meta.env = { VITE_USE_MOCK: 'false' } },
})
await apiModule.link((specifier) => {
  const exports = deps[specifier]
  assert.ok(exports, `Unexpected dependency: ${specifier}`)
  return new vm.SyntheticModule(Object.keys(exports), function () {
    for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
  }, { context })
})
await apiModule.evaluate()
await apiModule.namespace.api.listProducts({
  page: 2,
  size: 12,
  q: 'ㅅㅁㅌ 상품',
  productType: 'INVESTMENT',
  status: 'DRAFT',
})
assert.deepEqual(requests, [
  '/products?page=2&size=12&q=%E3%85%85%E3%85%81%E3%85%8C+%EC%83%81%ED%92%88&productType=INVESTMENT&status=DRAFT',
])

console.log('Product pagination regression: traversal, lifecycle totals, mixed-ID latest-analysis review precedence, filters, ownership, and API forwarding passed')
