import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'

const frontendRoot = new URL('../', import.meta.url)

async function createReviewHarness({ findings, selectedFindingIds, decisionResult }) {
  let source = await fs.readFile(new URL('src/views/ReviewDetailView.vue', frontendRoot), 'utf8')
  source = source.match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .concat('\nexport { decide, review, result, selected, comment }\n')

  const calls = []
  const api = {
    decideReview: async (reviewId, body) => {
      calls.push(['decision', reviewId, body])
      return decisionResult
    },
  }
  const router = { push: (path) => calls.push(['route', path]) }
  const toast = {
    success: (...args) => calls.push(['success', ...args]),
    fromError: (...args) => calls.push(['error', ...args]),
    push: (...args) => calls.push(['toast', ...args]),
  }
  const ref = (value) => ({ value })
  const context = vm.createContext({
    api,
    ref,
    computed: (factory) => ({ get value() { return factory() } }),
    onMounted: () => {},
    onUnmounted: () => {},
    defineProps: () => ({ reviewId: 'review-clean' }),
    useRouter: () => router,
    useToastStore: () => toast,
    useSessionStore: () => ({ isReviewer: true }),
    setInterval: () => 1,
    clearInterval: () => {},
  })
  const module = new vm.SourceTextModule(source, { context })
  await module.link(() => { throw new Error('imports should have been removed') })
  await module.evaluate()
  module.namespace.review.value = { status: 'PENDING', analysisId: 'analysis-clean' }
  module.namespace.result.value = { findings }
  module.namespace.selected.value = selectedFindingIds
  return { calls, exports: module.namespace }
}

async function testCleanApprovalUsesEmptySelectionAndAnalysisRoute() {
  const { calls, exports } = await createReviewHarness({
    findings: [],
    selectedFindingIds: [],
    decisionResult: { status: 'APPROVED', riskPatternIds: [] },
  })

  await exports.decide('APPROVED')

  assert.equal(calls[0][0], 'decision')
  assert.equal(calls[0][1], 'review-clean')
  assert.equal(calls[0][2].status, 'APPROVED')
  assert.equal(calls[0][2].comment, '')
  assert.deepEqual(Array.from(calls[0][2].selectedFindingIds), [])
  assert.deepEqual(calls[1], [
    'success',
    '검토 완료',
    '현재 분석 범위에서 지원되는 Finding 없이 검토가 완료되었습니다.',
  ])
  assert.deepEqual(calls[2], ['route', '/analyses/analysis-clean'])
}

async function testFindingApprovalPreservesPromotionPath() {
  const { calls, exports } = await createReviewHarness({
    findings: [{ findingId: 17, severity: 'HIGH' }],
    selectedFindingIds: [17],
    decisionResult: { status: 'APPROVED', riskPatternIds: [31] },
  })

  await exports.decide('APPROVED')

  assert.deepEqual(Array.from(calls[0][2].selectedFindingIds), [17])
  assert.equal(calls[1][0], 'success')
  assert.match(calls[1][2], /1건이 Risk Pattern으로 승격/)
  assert.deepEqual(calls[2], ['route', '/risk-library'])
}

async function testAnalysisResultUsesScopedNonConclusionCopy() {
  const source = await fs.readFile(new URL('src/views/AnalysisResultView.vue', frontendRoot), 'utf8')
  assert.match(source, /현재 분석 범위에서 지원되는 Finding이 없습니다/)
  assert.match(source, /상품의 안전성 또는 법률·규제 준수를 확인하거나 결론 내린 것이 아닙니다/)
  assert.doesNotMatch(source, /label: '위험 없음'/)
  assert.match(source, /return \{ tone: 'ok', label: '0점' \}/)
  assert.match(source, /result\.score\?\.state !== 'NOT_SCORED'/)
  assert.match(source, /result\.score\?\.state === 'PENDING_REVIEW'/)
}

await testCleanApprovalUsesEmptySelectionAndAnalysisRoute()
await testFindingApprovalPreservesPromotionPath()
await testAnalysisResultUsesScopedNonConclusionCopy()
console.log('clean review regression: ok')
