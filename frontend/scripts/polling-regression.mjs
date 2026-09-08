import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'

const frontendRoot = new URL('../', import.meta.url)

function createJobsHarness() {
  const calls = []
  const session = { isAuthed: true, user: { userId: '42' } }
  const toast = {
    success: (...args) => calls.push(['success', ...args]),
    push: (...args) => calls.push(['push', ...args]),
  }
  const api = {}
  let storeOptions
  const modules = {
    pinia: { defineStore: (_name, options) => { storeOptions = options; return () => store } },
    '@/api': { api },
    './toast': { useToastStore: () => toast },
    './session': { useSessionStore: () => session },
  }
  const context = vm.createContext({ console, localStorage: { getItem: () => null, setItem: () => {} }, setInterval: () => 1, clearInterval: () => {} })
  const sourcePromise = fs.readFile(new URL('src/stores/jobs.js', frontendRoot), 'utf8')
  let store

  return sourcePromise.then(async (source) => {
    const module = new vm.SourceTextModule(source, { context })
    await module.link((specifier) => {
      const exports = modules[specifier]
      assert.ok(exports, `unexpected import: ${specifier}`)
      return new vm.SyntheticModule(Object.keys(exports), function () {
        for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
      }, { context })
    })
    await module.evaluate()
    store = { ...storeOptions.state() }
    for (const [name, action] of Object.entries(storeOptions.actions)) store[name] = action.bind(store)
    return { api, calls, session, store }
  })
}

async function testNumericOwnerAndInReview() {
  const { api, calls, store } = await createJobsHarness()
  api.getReview = async () => ({ status: 'APPROVED', ownerId: 42, productId: 7 })
  store.track({ kind: 'review', id: 'review-1', name: '상품' })
  await store.tick()
  assert.equal(store.tracked.length, 0)
  assert.equal(store.notifications.length, 1)
  assert.equal(calls.filter(([kind]) => kind === 'push').length, 1)

  api.getAnalysis = async () => ({ status: 'IN_REVIEW' })
  store.track({ kind: 'analysis', id: 'analysis-1', productId: 7, name: '상품' })
  await store.tick()
  assert.equal(store.tracked.length, 0, 'IN_REVIEW must terminate analysis polling')
  assert.equal(store.notifications.filter((note) => note.kind === 'analysis').length, 1)
}

async function testOverlapAndStaleTick() {
  const { api, calls, session, store } = await createJobsHarness()
  let resolve
  let requestCount = 0
  api.getAnalysis = () => {
    requestCount += 1
    return new Promise((done) => { resolve = done })
  }
  store.track({ kind: 'analysis', id: 'analysis-2', productId: 8, name: '중복 방지' })
  const first = store.tick()
  await Promise.resolve()
  await store.tick()
  assert.equal(requestCount, 1, 'overlapping ticks must share the in-flight poll')
  store.untrack('analysis', 'analysis-2')
  resolve({ status: 'COMPLETED' })
  await first
  assert.equal(store.notifications.length, 0, 'an untracked job must not publish from an old tick')
  assert.equal(calls.length, 0)

  store.track({ kind: 'analysis', id: 'analysis-3', productId: 8, name: '세션 변경' })
  const changedSessionTick = store.tick()
  await Promise.resolve()
  session.user = { userId: '99' }
  resolve({ status: 'COMPLETED' })
  await changedSessionTick
  assert.equal(store.notifications.length, 0, 'a tick from the previous session must not publish')
}

async function loadReviewRefreshHarness() {
  let source = await fs.readFile(new URL('src/views/AnalysisResultView.vue', frontendRoot), 'utf8')
  source = source.match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .concat('\nexport { refreshReview, reviewInfo, result, loadError }\n')

  const api = {}
  const ref = (value) => ({ value })
  let pollingOptions
  const context = vm.createContext({
    api,
    ref,
    computed: (factory) => ({ get value() { return factory() } }),
    onMounted: () => {},
    onUnmounted: () => {},
    defineProps: () => ({ analysisId: 'analysis-review' }),
    useRouter: () => ({ back: () => {} }),
    useSessionStore: () => ({ isPM: true }),
    useToastStore: () => ({ fromError: () => {}, info: () => {}, success: () => {}, push: () => {} }),
    useJobsStore: () => ({ track: () => {} }),
    usePolling: (_request, options) => {
      pollingOptions = options
      return { polling: ref(false), timedOut: ref(false), start: () => {} }
    },
    setInterval: () => 1,
    clearInterval: () => {},
  })
  const module = new vm.SourceTextModule(source, { context })
  await module.link(() => { throw new Error('imports should have been removed') })
  await module.evaluate()
  return { api, exports: module.namespace, pollingOptions }
}

async function testTransientReviewRefreshIsAtomicAndRetryable() {
  const { api, exports, pollingOptions } = await loadReviewRefreshHarness()
  const inReviewResult = { score: { value: 5 } }
  api.getAnalysisResult = async () => inReviewResult
  api.getReviewByAnalysis = async () => {
    throw Object.assign(new Error('not found'), { status: 404, errorCode: 'REVIEW_NOT_FOUND' })
  }
  await pollingOptions.onResult({ status: 'IN_REVIEW' })
  assert.equal(exports.result.value, inReviewResult, 'IN_REVIEW must load the analysis result in the view')

  const oldResult = { score: { value: 10 } }
  exports.reviewInfo.value = { status: 'PENDING' }
  exports.result.value = oldResult
  api.getReviewByAnalysis = async () => ({ status: 'APPROVED', reviewerId: 'pm' })
  api.getAnalysisResult = async () => { throw Object.assign(new Error('temporary'), { status: 503 }) }

  await exports.refreshReview()
  assert.equal(exports.reviewInfo.value.status, 'PENDING', 'decision and refreshed result must publish atomically')
  assert.equal(exports.result.value, oldResult)
  assert.equal(exports.loadError.value.status, 503, 'refresh failures must be exposed for retry UI')

  const freshResult = { score: { value: 20 } }
  api.getAnalysisResult = async () => freshResult
  await exports.refreshReview()
  assert.equal(exports.reviewInfo.value.status, 'APPROVED')
  assert.equal(exports.result.value, freshResult)
  assert.equal(exports.loadError.value, null)
}

await testNumericOwnerAndInReview()
await testOverlapAndStaleTick()
await testTransientReviewRefreshIsAtomicAndRetryable()
console.log('polling regression: ok')
