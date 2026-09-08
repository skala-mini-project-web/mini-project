import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { ApiError, errorKind } from '../src/api/errors.js'

const source = await readFile(new URL('../src/api/index.js', import.meta.url), 'utf8')

async function loadApi(useMock, review) {
  const context = vm.createContext({ URLSearchParams })
  const requests = []
  const deps = {
    './mock/server.js': {
      mockServer: {
        getReview: async () => review,
        getReviewByAnalysis: async () => review,
      },
      ingestExtraction() {},
      ingestAnalysis() {},
    },
    './client.js': {
      http: { get: async (path) => { requests.push(path); return review } },
      uuid: () => 'contract-test',
    },
    './auth-context.js': { getAuth: () => ({ userId: '1', role: 'PRODUCT_MANAGER' }) },
    './errors.js': { ApiError, errorKind },
    '../lib/extract.js': { extractDocument: () => { throw new Error('Unexpected extraction') } },
    '../lib/analyze.js': { analyzeDocument: () => { throw new Error('Unexpected analysis') } },
  }
  const module = new vm.SourceTextModule(source, {
    context,
    initializeImportMeta(meta) { meta.env = { VITE_USE_MOCK: useMock ? 'true' : 'false' } },
  })
  await module.link((specifier) => {
    const exports = deps[specifier]
    assert.ok(exports, `Unexpected dependency: ${specifier}`)
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
    }, { context })
  })
  await module.evaluate()
  return { api: module.namespace.api, requests }
}

const mockReview = { reviewId: 'REV-1', submittedBy: 'USER-PM-001', status: 'APPROVED' }
const mock = await loadApi(true, mockReview)
for (const review of [await mock.api.getReview('REV-1'), await mock.api.getReviewByAnalysis('ANA-1')]) {
  assert.equal(review.ownerId, 'USER-PM-001')
  assert.equal(review.status, 'APPROVED')
  assert.equal(Object.hasOwn(review, 'submittedBy'), false)
}
assert.equal(mockReview.submittedBy, 'USER-PM-001', 'Normalization must not mutate stored mock data')
assert.equal(Object.hasOwn(mockReview, 'ownerId'), false)

const realReview = { reviewId: 7, ownerId: 1, status: 'APPROVED' }
const real = await loadApi(false, realReview)
assert.equal((await real.api.getReview(7)).ownerId, 1)
assert.equal((await real.api.getReviewByAnalysis(9)).ownerId, 1)
assert.deepEqual(real.requests, ['/reviews/7', '/analyses/9/review'])

const absent = await loadApi(true, null)
assert.equal(await absent.api.getReviewByAnalysis('ANA-1'), null)
console.log('API contract regression: mock/real review ownership and absent review passed')
