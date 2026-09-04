#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.RAG_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.RAG_BACKEND_URL || process.env.VITE_API_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '')
const AI_URL = (process.env.RAG_AI_URL || process.env.AI_SERVICE_URL || 'http://127.0.0.1:8000').replace(/\/$/, '')
const PDF_PATH = fileURLToPath(new URL('../../data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf', import.meta.url))
const EXPECTED_PDF_SHA256 = 'f7d1b5887dc9e55b58ac7c244e65c23daeeac7be6d017d23e61ec0e4804be0c9'
const CANONICAL_TEXT = '매월 수익을 보장하는 안정형 선택'
const REQUEST_TIMEOUT_MS = 8_000
const UI_TIMEOUT_MS = 15_000
const EXTRACTION_TIMEOUT_MS = 120_000
const ANALYSIS_TIMEOUT_MS = 300_000

async function loadChromium() {
  try {
    return (await import('playwright')).chromium
  } catch (playwrightError) {
    try {
      return (await import('playwright-core')).chromium
    } catch {
      throw new Error('Playwright is required. Install playwright or playwright-core before running this regression.', { cause: playwrightError })
    }
  }
}

async function fetchBounded(url, label) {
  let response
  try {
    response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) })
  } catch (error) {
    throw new Error(`${label} unavailable at ${url}: ${error.message}`)
  }
  assert(response.ok, `${label} health failed at ${url}: HTTP ${response.status}`)
  return response
}

async function preflight() {
  const file = await stat(PDF_PATH).catch((error) => {
    throw new Error(`Canonical product PDF is unavailable at ${PDF_PATH}: ${error.message}`)
  })
  assert(file.isFile() && file.size > 0, `Canonical product PDF is empty: ${PDF_PATH}`)
  const pdfSha256 = createHash('sha256').update(await readFile(PDF_PATH)).digest('hex')
  assert.equal(pdfSha256, EXPECTED_PDF_SHA256, 'Canonical product PDF checksum does not match corpus metadata')

  const [frontend, backend, ai] = await Promise.all([
    fetchBounded(FRONTEND_URL, 'Frontend'),
    fetchBounded(`${BACKEND_URL}/actuator/health`, 'Spring backend'),
    fetchBounded(`${AI_URL}/internal/v1/health`, 'AI service'),
  ])
  assert.match(frontend.headers.get('content-type') || '', /text\/html/i, 'Frontend health response was not HTML')
  for (const [label, response] of [['Spring backend', backend], ['AI service', ai]]) {
    const health = await response.json().catch(() => null)
    assert.equal(health?.status, 'UP', `${label} did not report status UP`)
  }
  return { pdfBytes: file.size, pdfSha256 }
}

const failures = {
  pageErrors: [],
  consoleErrors: [],
  requestFailures: [],
  apiResponses: [],
  unexpectedApiResponses: [],
}
const responseTasks = new Set()

function apiPath(url) {
  const pathname = new URL(url).pathname
  const index = pathname.indexOf('/api/')
  return index === -1 ? null : pathname.slice(index)
}

function monitorContext(context, label) {
  context.on('page', (page) => {
    page.on('pageerror', (error) => failures.pageErrors.push(`${label}: ${error.message}`))
    page.on('console', (message) => {
      if (message.type() === 'error') failures.consoleErrors.push(`${label}: ${message.text()}`)
    })
    page.on('requestfailed', (request) => {
      failures.requestFailures.push(`${label}: ${request.method()} ${request.url()} (${request.failure()?.errorText || 'failed'})`)
    })
    page.on('response', (response) => {
      const path = apiPath(response.url())
      if (!path) return
      const task = (async () => {
        const request = response.request()
        let body = null
        try { body = await response.json() } catch {}
        failures.apiResponses.push({ label, method: request.method(), path, status: response.status(), body })
        const expectedMissingReview = request.method() === 'GET'
          && /^\/api\/analyses\/[^/]+\/review$/.test(path)
          && response.status() === 404
          && body?.errorCode === 'REVIEW_NOT_FOUND'
        if ((response.status() < 200 || response.status() >= 300) && !expectedMissingReview) {
          failures.unexpectedApiResponses.push(`${label}: ${request.method()} ${path} -> ${response.status()} ${body?.errorCode || ''}`.trim())
        }
      })()
      responseTasks.add(task)
      task.finally(() => responseTasks.delete(task))
    })
  })
}

async function waitForApi(page, method, pathPattern, action, timeout = UI_TIMEOUT_MS) {
  const responsePromise = page.waitForResponse((response) => {
    const path = apiPath(response.url())
    return response.request().method() === method && path != null && pathPattern.test(path)
  }, { timeout })
  await action()
  const response = await responsePromise
  const body = await response.json().catch(() => null)
  assert(response.ok(), `${method} ${apiPath(response.url())} failed: HTTP ${response.status()} ${body?.errorCode || ''}`)
  return { response, body, path: apiPath(response.url()) }
}

async function login(page, roleName) {
  await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded' })
  const role = page.locator('button.role', { hasText: roleName })
  await role.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  assert(await role.isEnabled(), `${roleName} is unavailable; real demo users were not seeded`)
  const session = await waitForApi(page, 'POST', /^\/api\/demo\/session$/, () => role.click())
  await page.waitForURL('**/dashboard', { timeout: UI_TIMEOUT_MS })
  return session.body
}

function assertTrace(trace) {
  assert(trace && typeof trace === 'object', 'Completed analysis did not include a retrieval trace')
  assert(typeof trace.retrievalVersion === 'string' && trace.retrievalVersion.length > 0, 'Retrieval version is missing')
  assert(typeof trace.embeddingModel === 'string' && trace.embeddingModel.length > 0, 'Embedding model is missing')
  assert(typeof trace.queryHash === 'string' && trace.queryHash.length > 0, 'Retrieval query hash is missing')
  assert(Array.isArray(trace.contexts) && trace.contexts.length > 0, 'Real RAG returned no retrieval contexts')
  for (const context of trace.contexts) {
    assert(context.chunkId != null, 'A retrieval context has no chunk ID')
    assert(context.evidenceDocumentId != null, 'A retrieval context has no evidence document ID')
    assert(typeof context.excerpt === 'string' && context.excerpt.trim(), 'A retrieval context has no excerpt')
  }
}

const normalizedText = (value) => String(value || '').replace(/\s+/g, ' ').trim()

async function waitForAnalysisCompletion(page) {
  const completed = page.getByRole('heading', { name: 'RAG 검색 근거' })
  const retryPolling = page.getByRole('button', { name: '상태 다시 확인' })
  const failed = page.getByText('분석 실패', { exact: true })
  const deadline = Date.now() + ANALYSIS_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (await completed.isVisible().catch(() => false)) return
    if (await failed.isVisible().catch(() => false)) {
      throw new Error(`Real analysis entered FAILED: ${(await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 800)}`)
    }
    if (await retryPolling.isVisible().catch(() => false)) {
      await retryPolling.click()
      continue
    }
    const remaining = Math.max(1, deadline - Date.now())
    await completed.waitFor({ state: 'visible', timeout: Math.min(5_000, remaining) }).catch(() => {})
  }
  throw new Error(`Real analysis did not complete within ${ANALYSIS_TIMEOUT_MS}ms`)
}

function assertNoBrowserFailures() {
  const expectedMissingReviewCount = failures.apiResponses.filter((entry) =>
    entry.method === 'GET'
      && /^\/api\/analyses\/[^/]+\/review$/.test(entry.path)
      && entry.status === 404
      && entry.body?.errorCode === 'REVIEW_NOT_FOUND').length
  const expectedMissingReviewConsoleErrors = failures.consoleErrors.filter((message) =>
    /Failed to load resource: the server responded with a status of 404/.test(message))
  const unexpectedConsoleErrors = failures.consoleErrors.filter((message) =>
    !/Failed to load resource: the server responded with a status of 404/.test(message))
  assert.deepEqual(failures.pageErrors, [], `Browser page errors:\n${failures.pageErrors.join('\n')}`)
  assert(
    expectedMissingReviewConsoleErrors.length <= expectedMissingReviewCount,
    `Unexpected 404 console errors:\n${expectedMissingReviewConsoleErrors.join('\n')}`,
  )
  assert.deepEqual(unexpectedConsoleErrors, [], `Browser console errors:\n${unexpectedConsoleErrors.join('\n')}`)
  assert.deepEqual(failures.requestFailures, [], `Browser request failures:\n${failures.requestFailures.join('\n')}`)
  assert.deepEqual(failures.unexpectedApiResponses, [], `Unexpected API responses:\n${failures.unexpectedApiResponses.join('\n')}`)
}

const preflightReceipt = await preflight()
const chromium = await loadChromium()
let browser
let pmContext
let reviewerContext

try {
  browser = await chromium.launch({
    headless: process.env.RAG_HEADLESS !== 'false',
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}),
  })
  pmContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  reviewerContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  monitorContext(pmContext, 'PM')
  monitorContext(reviewerContext, 'reviewer')
  const pmPage = await pmContext.newPage()
  const reviewerPage = await reviewerContext.newPage()
  const pmUser = await login(pmPage, '상품 담당자')
  const reviewerUser = await login(reviewerPage, '컴플라이언스 검토자')
  assert.notEqual(pmUser?.userId, reviewerUser?.userId, 'PM and reviewer contexts resolved to the same user')

  const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
  const productName = `RAG Full Flow ${runKey}`
  await pmPage.goto(`${FRONTEND_URL}/products`, { waitUntil: 'domcontentloaded' })
  await pmPage.getByRole('heading', { name: '상품', exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await pmPage.getByRole('button', { name: '상품 등록' }).first().click()
  const productDialog = pmPage.getByRole('dialog', { name: '상품 등록' })
  await productDialog.getByLabel('상품명').fill(productName)
  await productDialog.getByLabel('설명').fill('Canonical real-service RAG end-to-end regression')
  const productCreate = await waitForApi(pmPage, 'POST', /^\/api\/products$/, () => productDialog.getByRole('button', { name: '등록', exact: true }).click())
  const productId = productCreate.body?.productId
  assert(productId != null, 'Product creation response did not include productId')
  await pmPage.waitForURL(new RegExp(`/products/${String(productId).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`), { timeout: UI_TIMEOUT_MS })

  const readyResponsePromise = pmPage.waitForResponse(async (response) => {
    const path = apiPath(response.url())
    if (response.request().method() !== 'GET' || !path || !/^\/api\/documents\/[^/]+$/.test(path) || !response.ok()) return false
    const body = await response.json().catch(() => null)
    return body?.extractStatus === 'READY'
  }, { timeout: EXTRACTION_TIMEOUT_MS })
  const chooserPromise = pmPage.waitForEvent('filechooser', { timeout: UI_TIMEOUT_MS })
  await pmPage.getByRole('button', { name: /^(문서 업로드|업로드)$/ }).first().click()
  const chooser = await chooserPromise
  const uploadResponsePromise = pmPage.waitForResponse((response) => {
    const path = apiPath(response.url())
    return response.request().method() === 'POST' && path != null && /^\/api\/products\/[^/]+\/documents$/.test(path)
  }, { timeout: UI_TIMEOUT_MS })
  await chooser.setFiles(PDF_PATH)
  const uploadResponse = await uploadResponsePromise
  const upload = await uploadResponse.json()
  assert(uploadResponse.ok(), `PDF upload failed: HTTP ${uploadResponse.status()}`)
  const documentId = upload.documentId
  assert(documentId != null, 'Upload response did not include documentId')
  const readyResponse = await readyResponsePromise
  const readyDocument = await readyResponse.json()
  assert.equal(readyDocument.extractStatus, 'READY', 'Canonical PDF did not reach READY')
  assert.equal(readyDocument.checksum?.toLowerCase(), EXPECTED_PDF_SHA256, 'Backend document checksum does not identify the canonical PDF')
  assert.match(readyDocument.extractedText || '', new RegExp(CANONICAL_TEXT), 'Server extraction did not contain canonical PDF text')
  await pmPage.waitForURL(`**/documents/${documentId}`, { timeout: UI_TIMEOUT_MS })
  const rawText = pmPage.locator('.raw')
  await rawText.waitFor({ state: 'visible', timeout: EXTRACTION_TIMEOUT_MS })
  await assert.doesNotReject(() => pmPage.getByText(CANONICAL_TEXT, { exact: false }).first().waitFor({ timeout: UI_TIMEOUT_MS }))

  const confirm = await waitForApi(pmPage, 'PATCH', new RegExp(`^/api/documents/${String(documentId).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/text$`), () => pmPage.getByRole('button', { name: '텍스트 확정' }).click())
  assert.equal(confirm.body?.confirmed, true, 'Document text confirmation was not persisted')
  assert.match(confirm.body?.extractedText || '', new RegExp(CANONICAL_TEXT), 'Confirmed text lost the canonical PDF content')
  await pmPage.getByText('확정됨', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await pmPage.getByRole('button', { name: '분석으로 이동' }).click()
  await pmPage.waitForURL(`**/products/${productId}/analyze`, { timeout: UI_TIMEOUT_MS })
  await pmPage.getByRole('heading', { name: '공식 상품 사실 확인' }).waitFor({ timeout: UI_TIMEOUT_MS })

  const candidateFact = pmPage.locator('.fact').filter({ has: pmPage.getByRole('button', { name: '확인', exact: true }) }).first()
  await candidateFact.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  const factVerify = await waitForApi(pmPage, 'PUT', /^\/api\/ground-truth-facts\/[^/]+\/verification$/, () => candidateFact.getByRole('button', { name: '확인', exact: true }).click())
  assert.equal(factVerify.body?.verificationStatus, 'VERIFIED', 'Ground-truth fact was not verified')
  assert.equal(factVerify.body?.extractionSource, 'CONFIRMED_DOCUMENT', 'Verified fact was not derived from confirmed document text')
  const factId = factVerify.body?.factId ?? factVerify.path.split('/').at(-2)
  await pmPage.getByText('VERIFIED', { exact: true }).first().waitFor({ timeout: UI_TIMEOUT_MS })

  const analysisCreate = await waitForApi(pmPage, 'POST', /^\/api\/analyses$/, () => pmPage.getByRole('button', { name: 'Persona + Red Team 분석 시작' }).click())
  const analysisId = analysisCreate.body?.analysisId
  assert(analysisId != null, 'Analysis creation response did not include analysisId')
  await pmPage.waitForURL(`**/analyses/${analysisId}`, { timeout: UI_TIMEOUT_MS })
  await waitForAnalysisCompletion(pmPage)

  const resultReload = pmPage.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === `/api/analyses/${analysisId}/result`, { timeout: UI_TIMEOUT_MS })
  await pmPage.reload({ waitUntil: 'domcontentloaded' })
  const resultResponse = await resultReload
  const analysisResult = await resultResponse.json()
  assert(resultResponse.ok(), `Analysis result failed: HTTP ${resultResponse.status()}`)
  assert(Array.isArray(analysisResult.findings) && analysisResult.findings.length > 0, 'Canonical real analysis returned no findings')
  assertTrace(analysisResult.retrievalTrace)
  assert(Array.isArray(analysisResult.groundTruthFacts) && analysisResult.groundTruthFacts.some((fact) => String(fact.factId) === String(factId)), 'Completed analysis did not retain the verified fact provenance')
  assert(analysisResult.findings.some((finding) => Array.isArray(finding.evidenceReferences) && finding.evidenceReferences.length > 0), 'Real analysis findings contain no cited evidence')
  assert.equal(
    normalizedText(await pmPage.locator('.retrieval-trace .trace-excerpt').first().innerText()),
    normalizedText(analysisResult.retrievalTrace.contexts[0].excerpt),
    'PM retrieval trace did not display the persisted top-ranked excerpt',
  )

  await pmPage.getByRole('button', { name: '검토 요청', exact: true }).click()
  const reviewDialog = pmPage.getByRole('dialog', { name: '검토 요청' })
  const submissionComment = `Real RAG evidence ${runKey}`
  await reviewDialog.getByLabel('제출 의견').fill(submissionComment)
  const reviewCreate = await waitForApi(pmPage, 'POST', /^\/api\/reviews$/, () => reviewDialog.getByRole('button', { name: '검토 요청', exact: true }).click())
  const reviewId = reviewCreate.body?.reviewId
  assert(reviewId != null, 'Review creation response did not include reviewId')
  await pmPage.getByText('검토 대기 중', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })

  await reviewerPage.goto(`${FRONTEND_URL}/reviews`, { waitUntil: 'domcontentloaded' })
  await reviewerPage.getByRole('heading', { name: '검토함' }).waitFor({ timeout: UI_TIMEOUT_MS })
  const queueRow = reviewerPage.locator('li.row').filter({ hasText: productName }).filter({ hasText: String(analysisId) })
  await queueRow.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await queueRow.click()
  await reviewerPage.waitForURL(`**/reviews/${reviewId}`, { timeout: UI_TIMEOUT_MS })
  await reviewerPage.getByText(String(reviewId), { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await reviewerPage.locator('.submit-note').filter({ hasText: submissionComment }).waitFor({ timeout: UI_TIMEOUT_MS })
  await reviewerPage.getByRole('heading', { name: 'RAG 검색 근거' }).waitFor({ timeout: UI_TIMEOUT_MS })
  await reviewerPage.getByText(analysisResult.retrievalTrace.retrievalVersion, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await reviewerPage.getByText(analysisResult.retrievalTrace.queryHash, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  assert.equal(
    normalizedText(await reviewerPage.locator('.retrieval-trace .trace-excerpt').first().innerText()),
    normalizedText(analysisResult.retrievalTrace.contexts[0].excerpt),
    'Reviewer retrieval trace did not display the persisted top-ranked excerpt',
  )
  const approvalButton = reviewerPage.getByRole('button', { name: /^승인 · \d+건 승격$/ })
  if ((await approvalButton.innerText()).includes('0건')) {
    await reviewerPage.locator('button.pick').first().click()
  }
  await reviewerPage.getByLabel('의견').fill(`Real RAG approved ${runKey}`)
  const decision = await waitForApi(reviewerPage, 'POST', new RegExp(`^/api/reviews/${String(reviewId).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/decision$`), () => approvalButton.click())
  assert.equal(decision.body?.status, 'APPROVED', 'Review decision did not persist APPROVED')
  const riskPatternIds = decision.body?.riskPatternIds
  assert(Array.isArray(riskPatternIds) && riskPatternIds.length > 0, 'Approved review did not create Risk Patterns')
  const riskPatternId = riskPatternIds[0]

  await reviewerPage.waitForURL('**/risk-library', { timeout: UI_TIMEOUT_MS })
  const riskRow = reviewerPage.locator('li.row').filter({ hasText: /초안|DRAFT/ }).first()
  await riskRow.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await riskRow.click()
  const riskDialog = reviewerPage.getByRole('dialog').filter({ hasText: String(riskPatternId) })
  await riskDialog.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  const activation = await waitForApi(reviewerPage, 'PATCH', new RegExp(`^/api/risk-patterns/${String(riskPatternId).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`), () => riskDialog.getByRole('button', { name: '활성화' }).click())
  assert.equal(activation.body?.status, 'ACTIVE', 'Risk Pattern activation did not persist ACTIVE')
  await riskDialog.getByRole('button', { name: '보호조치 초안 만들기' }).click()
  const guardCreateDialog = reviewerPage.getByRole('dialog', { name: /GuardFit 후보/ })
  const guardLabel = `원금 손실 가능성 확인 ${runKey}`.slice(0, 100)
  const guardPlacement = '상품 상세 상단'
  await guardCreateDialog.getByLabel('라벨 문구').fill(guardLabel)
  await guardCreateDialog.getByLabel('배치 위치').fill(guardPlacement)
  const guardCreate = await waitForApi(reviewerPage, 'POST', /^\/api\/guardfit\/actions$/, () => guardCreateDialog.getByRole('button', { name: 'DRAFT 생성' }).click())
  const actionId = guardCreate.body?.actionId
  assert(actionId != null, 'GuardFit creation response did not include actionId')

  await reviewerPage.waitForURL('**/guardfit', { timeout: UI_TIMEOUT_MS })
  const guardRow = reviewerPage.locator('li.row').filter({ hasText: String(riskPatternId) }).filter({ hasText: guardLabel })
  await guardRow.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await guardRow.getByRole('button', { name: '편집·결정' }).click()
  const guardEditDialog = reviewerPage.getByRole('dialog', { name: 'GuardFit 조치 편집' })
  const guardApproval = await waitForApi(reviewerPage, 'PUT', new RegExp(`^/api/guardfit/actions/${String(actionId).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`), () => guardEditDialog.getByRole('button', { name: '승인', exact: true }).click())
  assert.equal(guardApproval.body?.status, 'APPROVED', 'GuardFit approval did not persist APPROVED')

  await pmPage.goto(`${FRONTEND_URL}/analyses/${analysisId}`, { waitUntil: 'domcontentloaded' })
  await pmPage.getByText('승인됨', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await pmPage.goto(`${FRONTEND_URL}/guardfit`, { waitUntil: 'domcontentloaded' })
  await pmPage.getByRole('heading', { name: '적용 가이드' }).waitFor({ timeout: UI_TIMEOUT_MS })
  await pmPage.getByText(guardLabel, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await pmPage.locator('.ba-place').filter({ hasText: `위치: ${guardPlacement}` }).first().waitFor({ timeout: UI_TIMEOUT_MS })

  await Promise.all([...responseTasks])
  assertNoBrowserFailures()

  const receipt = {
    outcome: 'PASS',
    mode: 'real-services-browser',
    services: { frontend: FRONTEND_URL, backend: BACKEND_URL, ai: AI_URL },
    actors: {
      productManager: { userId: pmUser.userId, role: pmUser.role },
      reviewer: { userId: reviewerUser.userId, role: reviewerUser.role },
      isolatedBrowserContexts: true,
    },
    ids: { productId, documentId, factId, analysisId, reviewId, riskPatternIds, actionId },
    extraction: {
      fileName: 'SMART-INCOME-SALES-v1.pdf',
      bytes: preflightReceipt.pdfBytes,
      checksumSha256: preflightReceipt.pdfSha256,
      status: readyDocument.extractStatus,
      confirmed: confirm.body.confirmed,
      canonicalTextObserved: CANONICAL_TEXT,
      factExtractionSource: factVerify.body.extractionSource,
    },
    rag: {
      retrievalVersion: analysisResult.retrievalTrace.retrievalVersion,
      embeddingModel: analysisResult.retrievalTrace.embeddingModel,
      retrievedAt: analysisResult.retrievalTrace.retrievedAt,
      queryHash: analysisResult.retrievalTrace.queryHash,
      contexts: analysisResult.retrievalTrace.contexts.map(({ rank, chunkId, evidenceDocumentId, sourceType, similarity, excerpt }) => ({ rank, chunkId, evidenceDocumentId, sourceType, similarity, excerpt })),
    },
    provenance: {
      verifiedFacts: analysisResult.groundTruthFacts.map(({ factId: id, label, value }) => ({ factId: id, label, value })),
      findingCitations: analysisResult.findings.map(({ findingId, evidenceReferences }) => ({
        findingId,
        evidenceReferences: (evidenceReferences || []).map(({ evidenceDocumentId, sourceType, excerpt }) => ({ evidenceDocumentId, sourceType, excerpt })),
      })),
    },
    findings: { count: analysisResult.findings.length, promotedRiskPatternIds: riskPatternIds },
    terminalState: { review: decision.body.status, riskPattern: activation.body.status, guardFit: guardApproval.body.status, pmVisible: true },
    browserSignals: {
      apiResponseCount: failures.apiResponses.length,
      expectedNon2xx: failures.apiResponses
        .filter(({ status }) => status < 200 || status >= 300)
        .map(({ label, method, path, status, body }) => ({ label, method, path, status, errorCode: body?.errorCode })),
      pageErrors: failures.pageErrors,
      consoleErrors: failures.consoleErrors,
      requestFailures: failures.requestFailures,
      unexpectedApiResponses: failures.unexpectedApiResponses,
    },
  }
  console.log(JSON.stringify(receipt, null, 2))
} finally {
  await Promise.allSettled([...responseTasks])
  await pmContext?.close()
  await reviewerContext?.close()
  await browser?.close()
}
