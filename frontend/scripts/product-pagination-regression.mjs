import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { ApiError, errorKind } from '../src/api/errors.js'
import { mockServer, resetStore } from '../src/api/mock/server.js'

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

console.log('Product pagination regression: traversal, totals, combined filters, Hangul, ownership, and API forwarding passed')
