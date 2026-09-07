#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.AUTHZ_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.AUTHZ_BACKEND_URL || process.env.VITE_API_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '')
const AI_URL = (process.env.AUTHZ_AI_URL || process.env.AI_SERVICE_URL || 'http://127.0.0.1:8000').replace(/\/$/, '')
const SYNTHETIC_PDF_PATH = fileURLToPath(new URL('../../data/synthetic-financial-corpus/documents/river-flex-savings/product-overview.pdf', import.meta.url))
const REPORT_DIRECTORY = fileURLToPath(new URL('../../docs/reports/runtime', import.meta.url))
const REQUEST_TIMEOUT_MS = 8_000
const UI_TIMEOUT_MS = 15_000
const WORKFLOW_TIMEOUT_MS = 300_000
const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
const receiptPath = process.env.CROSS_ROLE_AUTHZ_E2E_RECEIPT || `${REPORT_DIRECTORY}/cross-role-authz-e2e-${runKey}.json`
const syntheticFileName = `cross-role-authz-${runKey}.pdf`

async function loadChromium() {
  try {
    return (await import('playwright')).chromium
  } catch (playwrightError) {
    try {
      return (await import('playwright-core')).chromium
    } catch {
      throw new Error('Playwright is required. Install playwright or playwright-core before running this E2E.', { cause: playwrightError })
    }
  }
}

async function fetchBounded(url, label) {
  let response
  try {
    response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) })
  } catch (error) {
    throw new Error(`${label} is unavailable at ${url}: ${error.message}`)
  }
  assert(response.ok, `${label} health check failed at ${url}: HTTP ${response.status}`)
  return response
}

async function preflight() {
  const pdf = await readFile(SYNTHETIC_PDF_PATH)
  assert.equal(pdf.subarray(0, 4).toString('ascii'), '%PDF', 'Synthetic fixture is not a PDF')
  const [frontend, backend, ai] = await Promise.all([
    fetchBounded(FRONTEND_URL, 'Frontend'),
    fetchBounded(`${BACKEND_URL}/actuator/health`, 'Spring backend'),
    fetchBounded(`${AI_URL}/internal/v1/health`, 'AI service'),
  ])
  assert.match(frontend.headers.get('content-type') || '', /text\/html/i, 'Frontend root did not return HTML')
  for (const [label, response] of [['Spring backend', backend], ['AI service', ai]]) {
    const health = await response.json().catch(() => null)
    assert.equal(health?.status, 'UP', `${label} did not report UP`)
  }
  return {
    pdf,
    bytes: pdf.length,
    sha256: createHash('sha256').update(pdf).digest('hex'),
  }
}

const browserSignals = {
  pageErrors: [],
  consoleErrors: [],
  requestFailures: [],
  apiResponses: [],
  unexpectedApiResponses: [],
}
const expectedResponses = []
const responseTasks = new Set()

function apiPath(url) {
  const parsed = new URL(url)
  const index = parsed.pathname.indexOf('/api/')
  return index === -1 ? null : `${parsed.pathname.slice(index)}${parsed.search}`
}

function expectedResponse(method, path, status, errorCode) {
  expectedResponses.push({ method, path, status, errorCode })
}

function monitorContext(context, actor) {
  context.on('page', (page) => {
    page.on('pageerror', (error) => browserSignals.pageErrors.push(`${actor}: ${error.message}`))
    page.on('console', (message) => {
      if (message.type() === 'error') browserSignals.consoleErrors.push(`${actor}: ${message.text()}`)
    })
    page.on('requestfailed', (request) => {
      browserSignals.requestFailures.push(`${actor}: ${request.method()} ${request.url()} (${request.failure()?.errorText || 'failed'})`)
    })
    page.on('response', (response) => {
      const path = apiPath(response.url())
      if (!path) return
      const task = (async () => {
        const method = response.request().method()
        const status = response.status()
        let body = null
        try { body = await response.json() } catch {}
        const entry = { actor, method, path, status, errorCode: body?.errorCode || null }
        browserSignals.apiResponses.push(entry)
        const expected = expectedResponses.some((candidate) =>
          candidate.method === method
            && candidate.path === path
            && candidate.status === status
            && (candidate.errorCode == null || candidate.errorCode === body?.errorCode))
        if ((status < 200 || status >= 300) && !expected) browserSignals.unexpectedApiResponses.push(entry)
      })()
      responseTasks.add(task)
      task.finally(() => responseTasks.delete(task))
    })
  })
}

async function login(page, roleName) {
  await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded' })
  const role = page.locator('button.role', { hasText: roleName })
  await role.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  assert(await role.isEnabled(), `${roleName} is unavailable; real demo users were not seeded`)
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && apiPath(response.url()) === '/api/demo/session',
  { timeout: UI_TIMEOUT_MS })
  await role.click()
  const response = await responsePromise
  const user = await response.json()
  assert(response.ok(), `${roleName} session failed: HTTP ${response.status()}`)
  await page.waitForURL('**/dashboard', { timeout: UI_TIMEOUT_MS })
  return user
}

async function browserApi(page, user, method, path, body, extraHeaders = {}) {
  const result = await page.evaluate(async ({ method, path, body, user, extraHeaders }) => {
    const headers = {
      Accept: 'application/json',
      'X-Demo-User-Id': user.userId,
      'X-Demo-Role': user.role,
      ...extraHeaders,
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const contentType = response.headers.get('content-type') || ''
    const responseBody = /json/i.test(contentType) ? await response.json().catch(() => null) : await response.text()
    return { status: response.status, ok: response.ok, body: responseBody }
  }, { method, path, body, user, extraHeaders })
  return result
}

async function requireOk(page, user, method, path, body, extraHeaders = {}) {
  const result = await browserApi(page, user, method, path, body, extraHeaders)
  assert(result.ok, `${method} ${path} failed: HTTP ${result.status} ${JSON.stringify(result.body)}`)
  return result.body
}

async function uploadSyntheticBatch(page, user, productId, pdf) {
  return page.evaluate(async ({ user, productId, fileName, base64, runKey }) => {
    const bytes = Uint8Array.from(atob(base64), (character) => character.charCodeAt(0))
    const form = new FormData()
    form.append('files', new Blob([bytes], { type: 'application/pdf' }), fileName)
    const response = await fetch(`/api/products/${productId}/document-batches`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'X-Demo-User-Id': user.userId,
        'X-Demo-Role': user.role,
        'X-Demo-Scenario': 'GUARANTEE_MISUNDERSTANDING_HIGH',
        'Idempotency-Key': `cross-role-batch-${runKey}`,
      },
      body: form,
    })
    return { status: response.status, ok: response.ok, body: await response.json().catch(() => null) }
  }, { user, productId, fileName: syntheticFileName, base64: pdf.toString('base64'), runKey })
}

async function poll(page, user, path, predicate, label) {
  const deadline = Date.now() + WORKFLOW_TIMEOUT_MS
  let last
  while (Date.now() < deadline) {
    last = await requireOk(page, user, 'GET', path)
    if (predicate(last)) return last
    await page.waitForTimeout(1_000)
  }
  throw new Error(`${label} did not reach the required state; last response: ${JSON.stringify(last)}`)
}

async function assertForbidden(page, user, method, path, body, extraHeaders = {}) {
  expectedResponse(method, path, 403, null)
  const response = await browserApi(page, user, method, path, body, extraHeaders)
  assert.equal(response.status, 403, `${method} ${path} was not forbidden`)
  assert(
    ['FORBIDDEN', 'FORBIDDEN_OWNERSHIP'].includes(response.body?.errorCode),
    `${method} ${path} returned the wrong authorization error`,
  )
  return { method, path, status: response.status, errorCode: response.body.errorCode }
}

function stableDocument(document) {
  return {
    documentId: document.documentId,
    extractStatus: document.extractStatus,
    extractedText: document.extractedText,
    confirmed: document.confirmed,
    confirmedBy: document.confirmedBy,
    confirmedAt: document.confirmedAt,
    error: document.error,
  }
}

function stableBatch(batch) {
  return {
    batchId: batch.batchId,
    status: batch.status,
    requestedItemCount: batch.requestedItemCount,
    pendingItemCount: batch.pendingItemCount,
    leasedItemCount: batch.leasedItemCount,
    retryWaitingItemCount: batch.retryWaitingItemCount,
    succeededItemCount: batch.succeededItemCount,
    cancelledItemCount: batch.cancelledItemCount,
    quarantinedItemCount: batch.quarantinedItemCount,
    cancellationReason: batch.cancellationReason,
    quarantineReason: batch.quarantineReason,
    terminalAt: batch.terminalAt,
  }
}

function stableItem(item) {
  return {
    itemId: item.itemId,
    status: item.status,
    attemptCount: item.attemptCount,
    maxAttempts: item.maxAttempts,
    terminalAt: item.terminalAt,
    cancellationReason: item.cancellationReason,
    quarantineReason: item.quarantineReason,
  }
}

function assertNoBrowserFailures() {
  const expectedAuthorizationConsoleErrors = browserSignals.consoleErrors.filter((message) =>
    /Failed to load resource: the server responded with a status of 403/.test(message))
  const unexpectedConsoleErrors = browserSignals.consoleErrors.filter((message) =>
    !/Failed to load resource: the server responded with a status of 403/.test(message))
  const forbiddenResponseCount = browserSignals.apiResponses.filter((entry) => entry.status === 403).length
  browserSignals.expectedAuthorizationConsoleErrors = expectedAuthorizationConsoleErrors
  browserSignals.unexpectedConsoleErrors = unexpectedConsoleErrors
  assert.deepEqual(browserSignals.pageErrors, [], `Browser page errors:\n${browserSignals.pageErrors.join('\n')}`)
  assert(
    expectedAuthorizationConsoleErrors.length <= forbiddenResponseCount,
    `Authorization console errors exceeded observed 403 responses:\n${expectedAuthorizationConsoleErrors.join('\n')}`,
  )
  assert.deepEqual(unexpectedConsoleErrors, [], `Unexpected browser console errors:\n${unexpectedConsoleErrors.join('\n')}`)
  assert.deepEqual(browserSignals.requestFailures, [], `Browser request failures:\n${browserSignals.requestFailures.join('\n')}`)
  assert.deepEqual(browserSignals.unexpectedApiResponses, [], `Unexpected API responses:\n${JSON.stringify(browserSignals.unexpectedApiResponses, null, 2)}`)
}

let browser
let pmContext
let reviewerContext
let terminalError
let receipt = {
  outcome: 'FAIL',
  mode: 'real-localhost-browser-api',
  runKey,
  startedAt: new Date().toISOString(),
  services: { frontend: FRONTEND_URL, backend: BACKEND_URL, ai: AI_URL },
  receiptPath,
}

try {
  const fixture = await preflight()
  const chromium = await loadChromium()
  browser = await chromium.launch({
    headless: process.env.AUTHZ_HEADLESS !== 'false',
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
  assert.equal(pmUser.role, 'PRODUCT_MANAGER')
  assert.equal(reviewerUser.role, 'COMPLIANCE_REVIEWER')
  assert.notEqual(pmUser.userId, reviewerUser.userId, 'PM and reviewer sessions resolved to the same user')

  const product = await requireOk(pmPage, pmUser, 'POST', '/api/products', {
    name: `Synthetic cross-role authorization ${runKey}`,
    productType: 'INVESTMENT',
    description: 'Synthetic localhost cross-role authorization and rejection QA.',
  }, { 'Idempotency-Key': `cross-role-product-${runKey}` })
  const productId = product.productId
  assert(productId != null, 'Product creation did not return productId')

  const submittedBatch = await uploadSyntheticBatch(pmPage, pmUser, productId, fixture.pdf)
  assert(submittedBatch.ok, `Synthetic batch upload failed: HTTP ${submittedBatch.status} ${submittedBatch.body?.errorCode || ''}`)
  const batchId = submittedBatch.body?.batchId
  assert(batchId != null, 'Batch upload did not return batchId')
  const batch = await poll(pmPage, pmUser, `/api/document-batches/${batchId}`,
    (value) => ['SUCCEEDED', 'CANCELLED', 'QUARANTINED'].includes(value.status), `Batch ${batchId}`)
  assert.equal(batch.status, 'SUCCEEDED', 'Valid synthetic one-item batch did not succeed')
  const itemPage = await requireOk(pmPage, pmUser, 'GET', `/api/document-batches/${batchId}/items`)
  assert.equal(itemPage.items?.length, 1, 'Synthetic batch did not contain exactly one item')
  const item = itemPage.items[0]
  assert.equal(item.status, 'SUCCEEDED', 'Synthetic batch item did not succeed')
  const itemId = item.itemId
  const documentId = item.documentId
  const readyDocument = await poll(pmPage, pmUser, `/api/documents/${documentId}`,
    (value) => value.extractStatus === 'READY', `Document ${documentId}`)

  const confirmedText = [
    'Synthetic authorization QA investment product.',
    '원금 손실 가능성이 있으며 수익은 보장되지 않습니다.',
    `Synthetic run marker: ${runKey}`,
  ].join('\n')
  const editedDocument = await requireOk(pmPage, pmUser, 'PATCH', `/api/documents/${documentId}/text`, {
    extractedText: confirmedText,
    confirmed: false,
  })
  const confirmationHeaders = editedDocument.currentRunId == null ? {} : {
    'X-Expected-Extraction-Run-Id': String(editedDocument.currentRunId),
    'X-Expected-Text-Hash': editedDocument.currentTextHash,
  }
  const confirmedDocument = await requireOk(pmPage, pmUser, 'PATCH', `/api/documents/${documentId}/text`, {
    extractedText: confirmedText,
    confirmed: true,
  }, confirmationHeaders)
  assert.equal(confirmedDocument.confirmed, true, 'PM confirmation did not persist')
  assert.equal(confirmedDocument.extractedText, confirmedText, 'PM confirmed text did not persist')

  const beforeAuthz = {
    document: stableDocument(await requireOk(pmPage, pmUser, 'GET', `/api/documents/${documentId}`)),
    batch: stableBatch(await requireOk(pmPage, pmUser, 'GET', `/api/document-batches/${batchId}`)),
    item: stableItem(await requireOk(pmPage, pmUser, 'GET', `/api/document-batches/${batchId}/items/${itemId}`)),
    latestProductDocumentId: (await requireOk(pmPage, pmUser, 'GET', `/api/products/${productId}`)).latestDocument?.documentId,
  }
  const reviewerForbidden = []
  expectedResponse('POST', `/api/products/${productId}/document-batches`, 403, null)
  const forbiddenBatchSubmission = await uploadSyntheticBatch(reviewerPage, reviewerUser, productId, fixture.pdf)
  assert.equal(forbiddenBatchSubmission.status, 403, 'Reviewer batch submission was not forbidden')
  assert(
    ['FORBIDDEN', 'FORBIDDEN_OWNERSHIP'].includes(forbiddenBatchSubmission.body?.errorCode),
    'Reviewer batch submission returned the wrong authorization error',
  )
  reviewerForbidden.push({
    method: 'POST',
    path: `/api/products/${productId}/document-batches`,
    status: forbiddenBatchSubmission.status,
    errorCode: forbiddenBatchSubmission.body.errorCode,
  })
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'PATCH', `/api/documents/${documentId}/text`, {
    extractedText: `${confirmedText}\nunauthorized reviewer mutation`,
    confirmed: true,
  }))
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'POST', `/api/documents/${documentId}/retry`))
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'POST', `/api/document-batches/${batchId}/cancel`, { reason: 'unauthorized reviewer batch cancel' }))
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'POST', `/api/document-batches/${batchId}/quarantine`, { reason: 'unauthorized reviewer batch quarantine' }))
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'POST', `/api/document-batches/${batchId}/items/${itemId}/cancel`, { reason: 'unauthorized reviewer item cancel' }))
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'POST', `/api/document-batches/${batchId}/items/${itemId}/quarantine`, { reason: 'unauthorized reviewer item quarantine' }))
  reviewerForbidden.push(await assertForbidden(reviewerPage, reviewerUser, 'POST', `/api/document-batches/${batchId}/items/${itemId}/retry`))
  const afterAuthz = {
    document: stableDocument(await requireOk(pmPage, pmUser, 'GET', `/api/documents/${documentId}`)),
    batch: stableBatch(await requireOk(pmPage, pmUser, 'GET', `/api/document-batches/${batchId}`)),
    item: stableItem(await requireOk(pmPage, pmUser, 'GET', `/api/document-batches/${batchId}/items/${itemId}`)),
    latestProductDocumentId: (await requireOk(pmPage, pmUser, 'GET', `/api/products/${productId}`)).latestDocument?.documentId,
  }
  assert.deepEqual(afterAuthz, beforeAuthz, 'Forbidden reviewer requests changed persisted document or batch state')

  const facts = await requireOk(pmPage, pmUser, 'GET', `/api/product-documents/${documentId}/ground-truth-facts`)
  assert.equal(facts.items?.length, 1, 'Confirmed synthetic document did not create one ground-truth snapshot')
  const fact = facts.items[0]
  await requireOk(pmPage, pmUser, 'PUT', `/api/ground-truth-facts/${fact.factId}/verification`, {
    verificationStatus: 'VERIFIED',
    value: fact.value,
  })
  const [evidence, personas, packs] = await Promise.all([
    requireOk(pmPage, pmUser, 'GET', '/api/evidence-documents?active=true'),
    requireOk(pmPage, pmUser, 'GET', '/api/persona-templates'),
    requireOk(pmPage, pmUser, 'GET', '/api/red-team-packs'),
  ])
  assert(evidence.items?.length, 'No active synthetic evidence documents were available')
  const activePersonas = (personas.items || []).filter((persona) => persona.active)
  assert(activePersonas.length >= 2, 'At least two active persona templates are required for distinct analysis inputs')
  assert(packs.items?.length, 'No Red Team pack was available')
  const commonAnalysis = {
    productDocumentId: documentId,
    evidenceDocumentIds: evidence.items.slice(0, 2).map((entry) => entry.evidenceDocumentId),
    redTeamPackId: packs.items[0].redTeamPackId,
  }
  const createAnalysis = (personaIds, suffix) => requireOk(pmPage, pmUser, 'POST', '/api/analyses', {
    ...commonAnalysis,
    personaIds,
  }, {
    'Idempotency-Key': `cross-role-analysis-${suffix}-${runKey}`,
    'X-Demo-Scenario': 'GUARANTEE_MISUNDERSTANDING_HIGH',
  })
  const firstAccepted = await createAnalysis([activePersonas[0].personaTemplateId], 'approval-seed')
  const secondAccepted = await createAnalysis([activePersonas[1].personaTemplateId], 'rejection')
  const firstAnalysis = await poll(pmPage, pmUser, `/api/analyses/${firstAccepted.analysisId}`,
    (value) => ['COMPLETED', 'FAILED'].includes(value.status), `Analysis ${firstAccepted.analysisId}`)
  const secondAnalysis = await poll(pmPage, pmUser, `/api/analyses/${secondAccepted.analysisId}`,
    (value) => ['COMPLETED', 'FAILED'].includes(value.status), `Analysis ${secondAccepted.analysisId}`)
  assert.equal(firstAnalysis.status, 'COMPLETED', `Approval-seed analysis failed: ${firstAnalysis.errorCode || ''}`)
  assert.equal(secondAnalysis.status, 'COMPLETED', `Rejection analysis failed: ${secondAnalysis.errorCode || ''}`)
  const firstResult = await requireOk(pmPage, pmUser, 'GET', `/api/analyses/${firstAccepted.analysisId}/result`)
  assert(firstResult.findings?.length, 'Approval-seed analysis returned no findings')

  const approvalReview = await requireOk(pmPage, pmUser, 'POST', '/api/reviews', {
    analysisId: firstAccepted.analysisId,
    submissionComment: `Synthetic approval seed ${runKey}`,
  }, { 'Idempotency-Key': `cross-role-review-approval-${runKey}` })
  const rejectionReview = await requireOk(pmPage, pmUser, 'POST', '/api/reviews', {
    analysisId: secondAccepted.analysisId,
    submissionComment: `Synthetic rejection target ${runKey}`,
  }, { 'Idempotency-Key': `cross-role-review-rejection-${runKey}` })

  const pmForbidden = []
  pmForbidden.push(await assertForbidden(pmPage, pmUser, 'POST', `/api/reviews/${rejectionReview.reviewId}/decision`, {
    status: 'APPROVED',
    comment: 'unauthorized PM decision',
    selectedFindingIds: [firstResult.findings[0].findingId],
  }))
  const stillPending = await requireOk(reviewerPage, reviewerUser, 'GET', `/api/reviews/${rejectionReview.reviewId}`)
  assert.equal(stillPending.status, 'PENDING', 'Forbidden PM decision changed the pending review')

  const approved = await requireOk(reviewerPage, reviewerUser, 'POST', `/api/reviews/${approvalReview.reviewId}/decision`, {
    status: 'APPROVED',
    comment: `Synthetic promotion seed ${runKey}`,
    selectedFindingIds: [firstResult.findings[0].findingId],
  })
  assert.equal(approved.status, 'APPROVED', 'Reviewer approval seed did not persist')
  assert(approved.riskPatternIds?.length, 'Reviewer approval did not create a Risk Pattern for mutation authorization QA')
  const riskPatternId = approved.riskPatternIds[0]
  const activeRiskPattern = await requireOk(reviewerPage, reviewerUser, 'PATCH', `/api/risk-patterns/${riskPatternId}`, {
    name: `Synthetic authorization target ${runKey}`,
    status: 'ACTIVE',
  })
  assert.equal(activeRiskPattern.status, 'ACTIVE', 'Reviewer could not activate the synthetic Risk Pattern')
  pmForbidden.push(await assertForbidden(pmPage, pmUser, 'PATCH', `/api/risk-patterns/${riskPatternId}`, {
    name: `unauthorized PM rename ${runKey}`,
    status: 'ACTIVE',
  }))
  const riskAfterPmMutation = await requireOk(reviewerPage, reviewerUser, 'GET', '/api/risk-patterns?page=0&size=100')
  assert.deepEqual(
    riskAfterPmMutation.items.find((entry) => entry.riskPatternId === riskPatternId),
    activeRiskPattern,
    'Forbidden PM Risk Pattern mutation changed persisted state',
  )
  pmForbidden.push(await assertForbidden(pmPage, pmUser, 'POST', '/api/guardfit/actions', {
    riskPatternId,
    actionType: 'WARNING',
    label: `unauthorized PM GuardFit ${runKey}`,
    placement: 'synthetic placement',
    required: true,
    preview: 'synthetic preview',
  }, { 'Idempotency-Key': `unauthorized-pm-guardfit-${runKey}` }))
  const guardFit = await requireOk(reviewerPage, reviewerUser, 'POST', '/api/guardfit/actions', {
    riskPatternId,
    actionType: 'WARNING',
    label: `Synthetic reviewer GuardFit ${runKey}`,
    placement: 'synthetic detail header',
    required: true,
    preview: 'synthetic reviewer preview',
  }, { 'Idempotency-Key': `reviewer-guardfit-${runKey}` })
  const guardFitPath = `/api/guardfit/actions?riskPatternId=${riskPatternId}&page=0&size=100`
  const guardFitBeforePmMutation = await requireOk(reviewerPage, reviewerUser, 'GET', guardFitPath)
  pmForbidden.push(await assertForbidden(pmPage, pmUser, 'PUT', `/api/guardfit/actions/${guardFit.actionId}`, {
    actionType: 'WARNING',
    label: `unauthorized PM GuardFit update ${runKey}`,
    placement: 'unauthorized synthetic placement',
    required: false,
    preview: 'unauthorized synthetic preview',
    status: 'APPROVED',
  }))
  const guardFitAfterPmMutation = await requireOk(reviewerPage, reviewerUser, 'GET', guardFitPath)
  assert.deepEqual(guardFitAfterPmMutation, guardFitBeforePmMutation,
    'Forbidden PM GuardFit update changed persisted action state')
  const riskBeforeRejection = await requireOk(reviewerPage, reviewerUser, 'GET', '/api/risk-patterns?page=0&size=100')
  const riskIdsBeforeRejection = riskBeforeRejection.items.map((entry) => entry.riskPatternId)

  const rejectionComment = `Synthetic rejection persisted ${runKey}`
  await reviewerPage.goto(`${FRONTEND_URL}/reviews/${rejectionReview.reviewId}`, { waitUntil: 'domcontentloaded' })
  await reviewerPage.getByLabel('의견').fill(rejectionComment)
  const rejectionResponsePromise = reviewerPage.waitForResponse((response) =>
    response.request().method() === 'POST'
      && apiPath(response.url()) === `/api/reviews/${rejectionReview.reviewId}/decision`,
  { timeout: UI_TIMEOUT_MS })
  await reviewerPage.getByRole('button', { name: '반려', exact: true }).click()
  const rejectionResponse = await rejectionResponsePromise
  const rejected = await rejectionResponse.json().catch(() => null)
  assert(rejectionResponse.ok(), `Reviewer browser rejection failed: HTTP ${rejectionResponse.status()} ${rejected?.errorCode || ''}`)
  assert.equal(rejected.status, 'REJECTED', 'Reviewer rejection did not persist')
  assert.deepEqual(rejected.riskPatternIds, [], 'Rejected review unexpectedly promoted a Risk Pattern')
  const riskAfterRejection = await requireOk(reviewerPage, reviewerUser, 'GET', '/api/risk-patterns?page=0&size=100')
  assert.deepEqual(riskAfterRejection.items.map((entry) => entry.riskPatternId), riskIdsBeforeRejection,
    'Risk Pattern library changed during the rejection decision')

  const pmOutcome = await requireOk(pmPage, pmUser, 'GET', `/api/analyses/${secondAccepted.analysisId}/review`)
  const pmReviewDetail = await requireOk(pmPage, pmUser, 'GET', `/api/reviews/${rejectionReview.reviewId}`)
  assert.equal(pmOutcome.status, 'REJECTED', 'PM API session did not observe the persisted rejection')
  assert.equal(pmOutcome.comment, rejectionComment, 'PM API session did not observe the persisted rejection comment')
  assert.deepEqual(pmOutcome.selectedFindingIds, [], 'PM API session observed selected findings on the rejected review')
  assert.deepEqual(pmReviewDetail.riskPatternIds, [], 'PM API session observed a promotion from the rejected review')
  await pmPage.goto(`${FRONTEND_URL}/analyses/${secondAccepted.analysisId}`, { waitUntil: 'domcontentloaded' })
  await pmPage.getByText('검토 반려 · 수정 필요', { exact: true }).waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await pmPage.getByText(rejectionComment, { exact: true }).waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })

  await Promise.all([...responseTasks])
  assertNoBrowserFailures()
  receipt = {
    ...receipt,
    outcome: 'PASS',
    finishedAt: new Date().toISOString(),
    fixture: { path: SYNTHETIC_PDF_PATH, fileName: syntheticFileName, bytes: fixture.bytes, sha256: fixture.sha256, syntheticOnly: true },
    actors: {
      productManager: { userId: pmUser.userId, role: pmUser.role },
      reviewer: { userId: reviewerUser.userId, role: reviewerUser.role },
      isolatedBrowserContexts: true,
    },
    ids: {
      productId,
      batchId,
      itemId,
      documentId,
      approvalAnalysisId: firstAccepted.analysisId,
      rejectionAnalysisId: secondAccepted.analysisId,
      approvalReviewId: approvalReview.reviewId,
      rejectionReviewId: rejectionReview.reviewId,
      riskPatternId,
      guardFitActionId: guardFit.actionId,
    },
    authorization: {
      reviewerForbidden,
      productManagerForbidden: pmForbidden,
      stateUnchangedAfterReviewerRequests: true,
      pendingReviewUnchangedAfterPmDecision: true,
      riskPatternStateUnchangedAfterPmMutation: true,
      guardFitStateUnchangedAfterPmUpdate: true,
    },
    rejection: {
      status: pmOutcome.status,
      comment: pmOutcome.comment,
      selectedFindingIds: pmOutcome.selectedFindingIds,
      riskPatternIds: pmReviewDetail.riskPatternIds,
      riskPatternLibraryUnchanged: true,
      pmApiVisible: true,
      pmBrowserVisible: true,
    },
    browserSignals,
  }
} catch (error) {
  terminalError = error
  receipt = {
    ...receipt,
    outcome: 'FAIL',
    finishedAt: new Date().toISOString(),
    error: { name: error.name, message: error.message, stack: error.stack },
    browserSignals,
  }
} finally {
  await Promise.allSettled([...responseTasks])
  await pmContext?.close()
  await reviewerContext?.close()
  await browser?.close()
  await mkdir(dirname(receiptPath), { recursive: true })
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx' })
  console.log(JSON.stringify(receipt, null, 2))
}

if (terminalError) throw terminalError
