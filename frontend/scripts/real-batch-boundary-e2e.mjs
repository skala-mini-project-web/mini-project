#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.BATCH_BOUNDARY_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.BATCH_BOUNDARY_BACKEND_URL || process.env.VITE_API_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '')
const AI_URL = (process.env.BATCH_BOUNDARY_AI_URL || process.env.AI_SERVICE_URL || 'http://127.0.0.1:8000').replace(/\/$/, '')
const VALID_PDF_PATH = fileURLToPath(new URL('../../data/synthetic-financial-corpus/documents/river-flex-savings/product-overview.pdf', import.meta.url))
const REPORT_DIRECTORY = fileURLToPath(new URL('../../docs/reports/runtime/batch-boundary-e2e', import.meta.url))
const REQUEST_TIMEOUT_MS = 8_000
const UI_TIMEOUT_MS = 30_000
const BATCH_TIMEOUT_MS = Number(process.env.BATCH_BOUNDARY_TIMEOUT_MS || 600_000)
const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
const receiptPath = process.env.BATCH_BOUNDARY_RECEIPT || join(REPORT_DIRECTORY, `receipt-${runKey}.json`)
const screenshotDirectory = process.env.BATCH_BOUNDARY_SCREENSHOT_DIR || REPORT_DIRECTORY

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
  const [fixtureStat, fixtureBuffer] = await Promise.all([stat(VALID_PDF_PATH), readFile(VALID_PDF_PATH)])
  assert(fixtureStat.isFile() && fixtureStat.size > 0, `Synthetic PDF is empty: ${VALID_PDF_PATH}`)
  assert.equal(fixtureBuffer.subarray(0, 4).toString('ascii'), '%PDF', 'Synthetic fixture does not pass frontend PDF magic-byte validation')
  const [frontend, backend, ai] = await Promise.all([
    fetchBounded(FRONTEND_URL, 'Frontend'),
    fetchBounded(`${BACKEND_URL}/actuator/health`, 'Spring backend'),
    fetchBounded(`${AI_URL}/internal/v1/health`, 'AI service'),
  ])
  assert.match(frontend.headers.get('content-type') || '', /text\/html/i, 'Frontend root did not return HTML')
  for (const [label, response] of [['Spring backend', backend], ['AI service', ai]]) {
    assert.equal((await response.json().catch(() => null))?.status, 'UP', `${label} did not report UP`)
  }
  return {
    buffer: fixtureBuffer,
    bytes: fixtureStat.size,
    sha256: createHash('sha256').update(fixtureBuffer).digest('hex'),
  }
}

const browserSignals = {
  pageErrors: [],
  consoleErrors: [],
  requestFailures: [],
  apiResponses: [],
  unexpectedApiResponses: [],
}
const responseTasks = new Set()

function apiPath(url) {
  const parsed = new URL(url)
  const index = parsed.pathname.indexOf('/api/')
  return index === -1 ? null : `${parsed.pathname.slice(index)}${parsed.search}`
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
        const entry = { actor, method: response.request().method(), path, status: response.status() }
        browserSignals.apiResponses.push(entry)
        if (response.status() < 200 || response.status() >= 300) {
          const body = await response.json().catch(() => null)
          browserSignals.unexpectedApiResponses.push({ ...entry, errorCode: body?.errorCode || null, message: body?.message || null })
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
  assert(response.ok(), `${method} ${apiPath(response.url())} failed: HTTP ${response.status()} ${JSON.stringify(body)}`)
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

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function fileName(count, ordinal) {
  return `boundary-${count}-${String(ordinal).padStart(3, '0')}-${runKey}.pdf`
}

async function createProduct(page) {
  const productName = `Batch Boundary E2E ${runKey}`
  await page.goto(`${FRONTEND_URL}/products`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('heading', { name: '상품', exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await page.getByRole('button', { name: '상품 등록' }).first().click()
  const dialog = page.getByRole('dialog', { name: '상품 등록' })
  await dialog.getByLabel('상품명').fill(productName)
  await dialog.getByLabel('설명').fill('Real Docker browser proof for the advertised 1–100 batch boundary')
  const created = await waitForApi(page, 'POST', /^\/api\/products$/, () => dialog.getByRole('button', { name: '등록', exact: true }).click())
  assert(created.body?.productId != null, 'Product creation response omitted productId')
  await page.waitForURL(new RegExp(`/products/${escapeRegex(created.body.productId)}$`), { timeout: UI_TIMEOUT_MS })
  return { productId: created.body.productId, productName }
}

async function submitBatch(page, productId, count, fixture) {
  const names = Array.from({ length: count }, (_, index) => fileName(count, index + 1))
  assert.equal(new Set(names).size, count, `Generated filenames for ${count}-file boundary are not unique`)
  await page.goto(`${FRONTEND_URL}/products/${productId}`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '문서 일괄 업로드' }).waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await page.getByRole('button', { name: '문서 일괄 업로드' }).click()
  const dialog = page.getByRole('dialog', { name: '문서 일괄 업로드' })
  const chooserPromise = page.waitForEvent('filechooser', { timeout: UI_TIMEOUT_MS })
  await dialog.getByRole('button', { name: /PDF\/PPTX 파일 선택/ }).click()
  const chooser = await chooserPromise
  await chooser.setFiles(names.map((name) => ({ name, mimeType: 'application/pdf', buffer: fixture.buffer })))
  await dialog.getByText('선택한 파일').waitFor({ timeout: UI_TIMEOUT_MS })
  assert.equal(await dialog.locator('.selection li').count(), count, `PM dialog did not display exactly ${count} selected files`)
  const created = await waitForApi(
    page,
    'POST',
    new RegExp(`^/api/products/${escapeRegex(productId)}/document-batches$`),
    () => dialog.getByRole('button', { name: '일괄 처리 시작' }).click(),
    120_000,
  )
  const batchId = created.body?.batchId
  assert(batchId != null, `${count}-file batch creation response omitted batchId`)
  assert.equal(created.body.acceptedItemCount, count, `${count}-file batch did not accept exactly ${count} items`)
  assert.equal(created.body.idempotentReplay, false, `${count}-file browser submission was unexpectedly replayed`)
  await page.waitForURL(`**/document-batches/${batchId}`, { timeout: UI_TIMEOUT_MS })
  return { batchId, names, acceptedAt: created.body.acceptedAt, responseStatus: created.response.status() }
}

async function refreshBatch(page, batchId) {
  const detailPath = `/api/document-batches/${batchId}`
  const itemPattern = new RegExp(`^/api/document-batches/${escapeRegex(batchId)}/items\\?page=0&size=100$`)
  const detailPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === detailPath, { timeout: UI_TIMEOUT_MS })
  const itemsPromise = page.waitForResponse((response) => response.request().method() === 'GET' && itemPattern.test(apiPath(response.url()) || ''), { timeout: UI_TIMEOUT_MS })
  await page.getByRole('button', { name: '새로고침' }).click()
  const [detailResponse, itemsResponse] = await Promise.all([detailPromise, itemsPromise])
  assert(detailResponse.ok(), `GET ${detailPath} failed: HTTP ${detailResponse.status()}`)
  assert(itemsResponse.ok(), `GET batch items failed: HTTP ${itemsResponse.status()}`)
  return { batch: await detailResponse.json(), itemsPage: await itemsResponse.json() }
}

async function waitForTerminal(page, batchId) {
  const deadline = Date.now() + BATCH_TIMEOUT_MS
  let snapshot
  while (Date.now() < deadline) {
    snapshot = await refreshBatch(page, batchId)
    const items = snapshot.itemsPage.items || []
    if (snapshot.batch?.status === 'SUCCEEDED' && items.length === snapshot.batch.requestedItemCount && items.every((item) => item.status === 'SUCCEEDED')) return snapshot
    await page.waitForTimeout(1_000)
  }
  throw new Error(`Batch ${batchId} did not reach successful terminal state in ${BATCH_TIMEOUT_MS}ms; last snapshot: ${JSON.stringify(snapshot)}`)
}

function assertDurableBoundary(snapshot, expectedNames, label) {
  const { batch, itemsPage } = snapshot
  const items = itemsPage.items || []
  const expectedCount = expectedNames.length
  assert.equal(batch.status, 'SUCCEEDED', `${label} batch is not SUCCEEDED`)
  assert.equal(batch.requestedItemCount, expectedCount, `${label} requested count changed`)
  assert.equal(itemsPage.totalElements, expectedCount, `${label} item page total is not exact`)
  assert.equal(items.length, expectedCount, `${label} did not return every item`)
  const aggregate = ['pendingItemCount', 'leasedItemCount', 'retryWaitingItemCount', 'succeededItemCount', 'cancelledItemCount', 'quarantinedItemCount']
    .reduce((total, field) => total + Number(batch[field]), 0)
  assert.equal(aggregate, expectedCount, `${label} aggregate counts do not total exactly ${expectedCount}`)
  assert.equal(batch.succeededItemCount, expectedCount, `${label} success aggregate is not exact`)
  assert.equal(batch.pendingItemCount, 0, `${label} retained pending items`)
  assert.equal(batch.leasedItemCount, 0, `${label} retained leased items`)
  assert.equal(batch.retryWaitingItemCount, 0, `${label} retained retry-waiting items`)
  assert.equal(batch.cancelledItemCount, 0, `${label} unexpectedly cancelled an item`)
  assert.equal(batch.quarantinedItemCount, 0, `${label} unexpectedly quarantined an item`)
  assert.equal(batch.totalAttemptCount, expectedCount, `${label} total attempts indicate missing or duplicate processing`)
  assert(batch.terminalAt, `${label} batch omitted its terminal timestamp`)
  assert.equal(new Set(items.map((item) => item.itemId)).size, expectedCount, `${label} item IDs are not unique`)
  assert.equal(new Set(items.map((item) => item.documentId)).size, expectedCount, `${label} document IDs are not unique`)
  assert.equal(new Set(items.map((item) => item.ordinal)).size, expectedCount, `${label} ordinals are not unique`)
  assert.deepEqual(items.map((item) => item.ordinal).sort((a, b) => a - b), Array.from({ length: expectedCount }, (_, index) => index + 1), `${label} ordinals are not exactly 1..${expectedCount}`)
  assert.deepEqual(items.map((item) => item.fileName).sort(), [...expectedNames].sort(), `${label} filenames differ from the unique PM selection`)
  for (const item of items) {
    assert.equal(item.status, 'SUCCEEDED', `${label} item ${item.ordinal} is not successful`)
    assert.equal(item.attemptCount, 1, `${label} item ${item.ordinal} was not processed exactly once`)
    assert(item.terminalAt, `${label} item ${item.ordinal} omitted its terminal timestamp`)
  }
}

async function assertTerminalDisplay(page, count, readOnly) {
  await page.getByText(/처리가 종료되었습니다/).waitFor({ timeout: UI_TIMEOUT_MS })
  await page.locator('.top .state', { hasText: '성공' }).waitFor({ timeout: UI_TIMEOUT_MS })
  const summary = (await page.locator('section.summary > div > strong').allTextContents()).map((text) => text.replace(/\s+/g, ' ').trim())
  assert.deepEqual(summary, [`${count} / ${count}`, '0', '0', '0', String(count), '0'], `${readOnly ? 'Reviewer' : 'PM'} summary does not display the exact ${count}-item terminal aggregate`)
  assert.equal(await page.locator('tbody tr').count(), count, `${readOnly ? 'Reviewer' : 'PM'} table does not display exactly ${count} items`)
  if (readOnly) {
    await page.getByText('검토자는 조회만 가능합니다.', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
    assert.equal(await page.getByRole('columnheader', { name: '관리', exact: true }).count(), 0, 'Reviewer can see the management column')
    assert.equal(await page.getByRole('button', { name: /^(재시도|취소|격리|배치 취소|배치 격리)$/ }).count(), 0, 'Reviewer can see mutation controls')
  }
}

async function reloadDetail(page, batchId) {
  const detailPath = `/api/document-batches/${batchId}`
  const itemPattern = new RegExp(`^/api/document-batches/${escapeRegex(batchId)}/items\\?page=0&size=100$`)
  const detailPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === detailPath, { timeout: UI_TIMEOUT_MS })
  const itemsPromise = page.waitForResponse((response) => response.request().method() === 'GET' && itemPattern.test(apiPath(response.url()) || ''), { timeout: UI_TIMEOUT_MS })
  await page.goto(`${FRONTEND_URL}/document-batches/${batchId}`, { waitUntil: 'domcontentloaded' })
  const [detailResponse, itemsResponse] = await Promise.all([detailPromise, itemsPromise])
  assert(detailResponse.ok() && itemsResponse.ok(), `Reload of batch ${batchId} failed`)
  return { batch: await detailResponse.json(), itemsPage: await itemsResponse.json() }
}

function assertExpectedApi(batchIds) {
  const responses = browserSignals.apiResponses
  const has = (actor, method, pattern) => responses.some((entry) => entry.actor === actor && entry.method === method && pattern.test(entry.path) && entry.status >= 200 && entry.status < 300)
  assert(has('PM', 'POST', /^\/api\/products$/), 'Expected PM product creation API response was not recorded')
  for (const batchId of batchIds) {
    assert(has('PM', 'GET', new RegExp(`^/api/document-batches/${escapeRegex(batchId)}$`)), `Expected PM batch ${batchId} detail API response was not recorded`)
    assert(has('PM', 'GET', new RegExp(`^/api/document-batches/${escapeRegex(batchId)}/items\\?page=0&size=100$`)), `Expected PM batch ${batchId} items API response was not recorded`)
    assert(has('reviewer', 'GET', new RegExp(`^/api/document-batches/${escapeRegex(batchId)}$`)), `Expected reviewer batch ${batchId} detail API response was not recorded`)
    assert(has('reviewer', 'GET', new RegExp(`^/api/document-batches/${escapeRegex(batchId)}/items\\?page=0&size=100$`)), `Expected reviewer batch ${batchId} items API response was not recorded`)
  }
  assert.equal(responses.filter((entry) => entry.actor === 'PM' && entry.method === 'POST' && /\/document-batches$/.test(entry.path)).length, 2, 'PM did not issue exactly two batch creation requests')
}

function assertNoBrowserFailures() {
  assert.deepEqual(browserSignals.pageErrors, [], `Browser page errors:\n${browserSignals.pageErrors.join('\n')}`)
  assert.deepEqual(browserSignals.consoleErrors, [], `Browser console errors:\n${browserSignals.consoleErrors.join('\n')}`)
  assert.deepEqual(browserSignals.requestFailures, [], `Browser request failures:\n${browserSignals.requestFailures.join('\n')}`)
  assert.deepEqual(browserSignals.unexpectedApiResponses, [], `Unexpected API responses:\n${JSON.stringify(browserSignals.unexpectedApiResponses, null, 2)}`)
}

let browser
let pmContext
let reviewerContext
let pmPage
let reviewerPage
let receipt = {
  outcome: 'FAIL',
  mode: 'real-docker-browser',
  runKey,
  startedAt: new Date().toISOString(),
  services: { frontend: FRONTEND_URL, backend: BACKEND_URL, ai: AI_URL },
  receiptPath,
  screenshotDirectory,
}
let terminalError

try {
  await mkdir(screenshotDirectory, { recursive: true })
  const fixture = await preflight()
  const chromium = await loadChromium()
  browser = await chromium.launch({
    headless: process.env.BATCH_BOUNDARY_HEADLESS !== 'false',
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}),
  })
  pmContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  reviewerContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  monitorContext(pmContext, 'PM')
  monitorContext(reviewerContext, 'reviewer')
  pmPage = await pmContext.newPage()
  reviewerPage = await reviewerContext.newPage()

  const pmUser = await login(pmPage, '상품 담당자')
  const product = await createProduct(pmPage)
  const boundaries = []
  for (const count of [1, 100]) {
    const submission = await submitBatch(pmPage, product.productId, count, fixture)
    const terminal = await waitForTerminal(pmPage, submission.batchId)
    assertDurableBoundary(terminal, submission.names, `${count}-file initial terminal`)
    await assertTerminalDisplay(pmPage, count, false)
    const pmScreenshot = join(screenshotDirectory, `pm-${count}-file-terminal.png`)
    await pmPage.screenshot({ path: pmScreenshot, fullPage: true })

    const persisted = await reloadDetail(pmPage, submission.batchId)
    assertDurableBoundary(persisted, submission.names, `${count}-file PM reload`)
    await assertTerminalDisplay(pmPage, count, false)
    boundaries.push({ count, submission, terminal: persisted, pmScreenshot })
  }

  const reviewerUser = await login(reviewerPage, '컴플라이언스 검토자')
  assert.notEqual(pmUser?.userId, reviewerUser?.userId, 'PM and reviewer resolved to the same demo user')
  for (const boundary of boundaries) {
    const reviewerSnapshot = await reloadDetail(reviewerPage, boundary.submission.batchId)
    assertDurableBoundary(reviewerSnapshot, boundary.submission.names, `${boundary.count}-file reviewer reload`)
    await assertTerminalDisplay(reviewerPage, boundary.count, true)
    const reviewerScreenshot = join(screenshotDirectory, `reviewer-${boundary.count}-file-terminal.png`)
    await reviewerPage.screenshot({ path: reviewerScreenshot, fullPage: true })
    boundary.reviewerScreenshot = reviewerScreenshot
  }

  await Promise.all([...responseTasks])
  assertExpectedApi(boundaries.map(({ submission }) => submission.batchId))
  assertNoBrowserFailures()
  receipt = {
    ...receipt,
    outcome: 'PASS',
    completedAt: new Date().toISOString(),
    fixture: { path: VALID_PDF_PATH, bytes: fixture.bytes, sha256: fixture.sha256, synthetic: true },
    actors: {
      productManager: { userId: pmUser.userId, role: pmUser.role },
      reviewer: { userId: reviewerUser.userId, role: reviewerUser.role },
      isolatedBrowserContexts: true,
    },
    product: { ...product, createdThroughPmUi: true },
    boundaries: boundaries.map(({ count, submission, terminal, pmScreenshot, reviewerScreenshot }) => ({
      count,
      batchId: submission.batchId,
      acceptedItemCount: count,
      acceptedAt: submission.acceptedAt,
      responseStatus: submission.responseStatus,
      uniqueFileNames: submission.names,
      terminalBatch: terminal.batch,
      terminalItems: terminal.itemsPage.items,
      assertions: {
        submittedThroughPmUi: true,
        uniqueOrdinalsItemsDocuments: true,
        aggregateTotalExact: true,
        terminalTimestampsPresent: true,
        exactlyOneAttemptPerItem: true,
        persistedAfterPmReload: true,
        pmTerminalDisplay: true,
        reviewerReadOnlyDisplay: true,
      },
      screenshots: { pm: pmScreenshot, reviewer: reviewerScreenshot },
    })),
    expectedApi: {
      verified: true,
      productCreate: 'PM POST /api/products',
      batchCreates: boundaries.map(({ submission }) => `PM POST /api/products/${product.productId}/document-batches -> ${submission.responseStatus}`),
      persistedReads: boundaries.flatMap(({ submission }) => [
        `PM GET /api/document-batches/${submission.batchId}`,
        `PM GET /api/document-batches/${submission.batchId}/items?page=0&size=100`,
        `reviewer GET /api/document-batches/${submission.batchId}`,
        `reviewer GET /api/document-batches/${submission.batchId}/items?page=0&size=100`,
      ]),
    },
    browserSignals,
  }
  console.log(`PASS real 1/100 durable batch boundary browser E2E; receipt: ${receiptPath}`)
} catch (error) {
  terminalError = error
  await Promise.allSettled([...responseTasks])
  const failureScreenshots = []
  for (const [actor, page] of [['pm', pmPage], ['reviewer', reviewerPage]]) {
    if (!page || page.isClosed()) continue
    const path = join(screenshotDirectory, `failure-${actor}-${runKey}.png`)
    await page.screenshot({ path, fullPage: true }).then(() => failureScreenshots.push(path)).catch(() => {})
  }
  receipt = {
    ...receipt,
    outcome: 'FAIL',
    completedAt: new Date().toISOString(),
    error: { name: error.name, message: error.message, stack: error.stack },
    failureScreenshots,
    browserSignals,
  }
} finally {
  await Promise.allSettled([...responseTasks])
  await pmContext?.close()
  await reviewerContext?.close()
  await browser?.close()
  await mkdir(dirname(receiptPath), { recursive: true })
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, 'utf8')
}

if (terminalError) {
  throw new Error(`${terminalError.message}\nFailure receipt: ${receiptPath}`, { cause: terminalError })
}
