#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.BATCH_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.BATCH_BACKEND_URL || process.env.VITE_API_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '')
const AI_URL = (process.env.BATCH_AI_URL || process.env.AI_SERVICE_URL || 'http://127.0.0.1:8000').replace(/\/$/, '')
const FIXTURE_PATH = fileURLToPath(new URL('../../data/synthetic-financial-corpus/documents/river-flex-savings/product-overview.pdf', import.meta.url))
const REPORT_DIRECTORY = fileURLToPath(new URL('../../docs/reports/v1', import.meta.url))
const REQUEST_TIMEOUT_MS = 8_000
const UI_TIMEOUT_MS = 20_000
const ITEM_COUNT = 100
const CANCELLATION_REASON = '사용자 취소'
const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
const receiptPath = process.env.BATCH_CANCEL_E2E_RECEIPT || `${REPORT_DIRECTORY}/batch-cancel-e2e-${runKey}.json`
const screenshotDirectory = process.env.BATCH_CANCEL_E2E_SCREENSHOT_DIR || `${REPORT_DIRECTORY}/batch-cancel-e2e-${runKey}`

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
  const [fixtureStat, fixtureBuffer] = await Promise.all([stat(FIXTURE_PATH), readFile(FIXTURE_PATH)])
  assert(fixtureStat.isFile() && fixtureStat.size > 0, `Synthetic PDF is empty: ${FIXTURE_PATH}`)
  assert.equal(fixtureBuffer.subarray(0, 4).toString('ascii'), '%PDF', 'Synthetic fixture does not pass frontend PDF magic-byte validation')
  assert(fixtureStat.size <= 10 * 1024 * 1024, 'Synthetic fixture exceeds the browser upload limit')

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
  expectedCancellationResponses: [],
  unexpectedApiResponses: [],
}
const responseTasks = new Set()

function apiPath(url) {
  const pathname = new URL(url).pathname
  const index = pathname.indexOf('/api/')
  return index === -1 ? null : pathname.slice(index)
}

function isBatchCancellation(path, method) {
  return method === 'POST' && /^\/api\/document-batches\/\d+\/cancel$/.test(path)
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
        const entry = {
          actor,
          method: response.request().method(),
          path,
          status: response.status(),
          contentType: response.headers()['content-type'] || null,
        }
        browserSignals.apiResponses.push(entry)
        const body = response.status() >= 400 ? await response.json().catch(() => null) : null
        const detail = { ...entry, errorCode: body?.errorCode || null, message: body?.message || null }
        if (isBatchCancellation(path, entry.method)
            && (response.ok() || (response.status() === 409 && body?.errorCode === 'ACTION_ALREADY_FINALIZED'))) {
          browserSignals.expectedCancellationResponses.push(detail)
        } else if (!response.ok()) {
          browserSignals.unexpectedApiResponses.push(detail)
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
  const contentType = response.headers()['content-type'] || ''
  const body = /json/i.test(contentType) ? await response.json().catch(() => null) : null
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

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

async function loadBatchDetail(page, batchId, navigate) {
  const batchPath = `/api/document-batches/${batchId}`
  const itemsPath = `${batchPath}/items`
  const batchPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === batchPath, { timeout: UI_TIMEOUT_MS })
  const itemsPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === itemsPath, { timeout: UI_TIMEOUT_MS })
  await navigate()
  const [batchResponse, itemsResponse] = await Promise.all([batchPromise, itemsPromise])
  assert(batchResponse.ok(), `Batch detail failed: HTTP ${batchResponse.status()}`)
  assert(itemsResponse.ok(), `Batch items failed: HTTP ${itemsResponse.status()}`)
  return { batch: await batchResponse.json(), items: (await itemsResponse.json()).items || [] }
}

async function capture(page, name, screenshots) {
  const path = `${screenshotDirectory}/${name}.png`
  await mkdir(dirname(path), { recursive: true })
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  screenshots.push({ actor: name.startsWith('reviewer-') ? 'reviewer' : 'PM', state: name, path })
}

function assertCancelledSnapshot(snapshot, fileNames) {
  const { batch, items } = snapshot
  assert.equal(batch.status, 'CANCELLED', 'Batch did not reach terminal CANCELLED')
  assert.equal(batch.requestedItemCount, ITEM_COUNT, 'Requested-item count changed')
  assert.equal(items.length, ITEM_COUNT, 'Batch item list is incomplete')
  assert.equal(batch.pendingItemCount, 0, 'Cancelled batch still reports pending items')
  assert.equal(batch.leasedItemCount, 0, 'Cancelled batch still reports leased items')
  assert.equal(batch.retryWaitingItemCount, 0, 'Cancelled batch still reports retry-wait items')
  assert.equal(batch.succeededItemCount, 0, 'A supposedly cancelled batch reports successful items')
  assert.equal(batch.cancelledItemCount, ITEM_COUNT, 'Cancelled-item count does not reconcile')
  assert.equal(batch.quarantinedItemCount, 0, 'A supposedly cancelled batch reports quarantined items')
  assert.equal(
    batch.pendingItemCount + batch.leasedItemCount + batch.retryWaitingItemCount + batch.succeededItemCount + batch.cancelledItemCount + batch.quarantinedItemCount,
    batch.requestedItemCount,
    'Batch state counters do not sum to requestedItemCount',
  )
  assert.equal(batch.totalAttemptCount, items.reduce((sum, item) => sum + item.attemptCount, 0), 'Batch attempt counter does not reconcile with items')
  assert.equal(batch.cancellationReason, CANCELLATION_REASON, 'Batch cancellation reason was not persisted')
  assert(Number.isFinite(Date.parse(batch.terminalAt)), 'Batch terminal timestamp is absent or invalid')
  assert.deepEqual(new Set(items.map((item) => item.fileName)), new Set(fileNames), 'Returned item filenames differ from the submitted synthetic files')
  for (const item of items) {
    assert.equal(item.status, 'CANCELLED', `Item ${item.itemId} is not CANCELLED`)
    assert.equal(item.cancellationReason, CANCELLATION_REASON, `Item ${item.itemId} lost its cancellation reason`)
    assert(Number.isFinite(Date.parse(item.terminalAt)), `Item ${item.itemId} terminal timestamp is absent or invalid`)
    assert(Date.parse(item.terminalAt) <= Date.parse(batch.terminalAt), `Item ${item.itemId} terminal timestamp follows the batch terminal timestamp`)
  }
}

async function assertCancelledUi(page, itemCount, actor) {
  await page.getByText(/처리가 종료되었습니다/).waitFor({ timeout: UI_TIMEOUT_MS })
  await page.locator('.top .state', { hasText: '취소' }).waitFor({ timeout: UI_TIMEOUT_MS })
  const summary = await page.locator('section.summary > div > strong').allTextContents()
  assert.deepEqual(summary.map((text) => text.replace(/\s+/g, ' ').trim()), [`${itemCount} / ${itemCount}`, '0', '0', '0', '0', String(itemCount)], `${actor} summary does not show reconciled cancellation counters`)
  assert.equal(await page.locator('tbody tr').count(), itemCount, `${actor} does not show every cancelled item`)
  assert.equal(await page.locator('tbody tr .state', { hasText: '취소' }).count(), itemCount, `${actor} does not show CANCELLED for every item`)
  assert.equal(await page.getByText(CANCELLATION_REASON, { exact: true }).count(), itemCount, `${actor} does not show the cancellation reason for every item`)
}

async function assertPmTerminalControls(page) {
  assert.equal(await page.getByRole('button', { name: '배치 취소', exact: true }).count(), 0, 'PM can cancel an already terminal batch')
  assert.equal(await page.getByRole('button', { name: '배치 격리', exact: true }).count(), 0, 'PM can quarantine an already terminal batch')
  assert.equal(await page.getByRole('button', { name: '취소', exact: true }).count(), 0, 'PM can cancel an already terminal item')
  assert.equal(await page.getByRole('button', { name: '격리', exact: true }).count(), 0, 'PM can quarantine an already terminal item')
  assert.equal(await page.getByRole('button', { name: '재시도', exact: true }).count(), ITEM_COUNT, 'Cancelled-item retry control is missing despite the explicit retry contract')
}

function durableProjection(snapshot) {
  return {
    batch: {
      status: snapshot.batch.status,
      requestedItemCount: snapshot.batch.requestedItemCount,
      pendingItemCount: snapshot.batch.pendingItemCount,
      leasedItemCount: snapshot.batch.leasedItemCount,
      retryWaitingItemCount: snapshot.batch.retryWaitingItemCount,
      succeededItemCount: snapshot.batch.succeededItemCount,
      cancelledItemCount: snapshot.batch.cancelledItemCount,
      quarantinedItemCount: snapshot.batch.quarantinedItemCount,
      totalAttemptCount: snapshot.batch.totalAttemptCount,
      cancellationReason: snapshot.batch.cancellationReason,
      terminalAt: snapshot.batch.terminalAt,
    },
    items: snapshot.items.map((item) => ({
      itemId: item.itemId,
      status: item.status,
      attemptCount: item.attemptCount,
      cancellationReason: item.cancellationReason,
      terminalAt: item.terminalAt,
    })),
  }
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
let failure
let receipt = {
  outcome: 'FAIL',
  mode: 'real-localhost-docker-browser',
  runKey,
  startedAt: new Date().toISOString(),
  services: { frontend: FRONTEND_URL, backend: BACKEND_URL, ai: AI_URL },
  receiptPath,
  screenshotDirectory,
}

try {
  const fixture = await preflight()
  const chromium = await loadChromium()
  browser = await chromium.launch({
    headless: process.env.BATCH_HEADLESS !== 'false',
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}),
  })
  pmContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  reviewerContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  monitorContext(pmContext, 'PM')
  monitorContext(reviewerContext, 'reviewer')
  pmPage = await pmContext.newPage()
  reviewerPage = await reviewerContext.newPage()
  const screenshots = []

  const pmUser = await login(pmPage, '상품 담당자')
  const productName = `Batch cancellation E2E ${runKey}`
  await pmPage.goto(`${FRONTEND_URL}/products`, { waitUntil: 'domcontentloaded' })
  await pmPage.getByRole('button', { name: '상품 등록' }).first().click()
  const productDialog = pmPage.getByRole('dialog', { name: '상품 등록' })
  await productDialog.getByLabel('상품명').fill(productName)
  await productDialog.getByLabel('설명').fill('Real localhost browser cancellation lifecycle with a 100-item synthetic batch')
  const productCreate = await waitForApi(pmPage, 'POST', /^\/api\/products$/, () => productDialog.getByRole('button', { name: '등록', exact: true }).click())
  const productId = productCreate.body?.productId
  assert(productId != null, 'Product creation response omitted productId')
  await pmPage.waitForURL(new RegExp(`/products/${escapeRegex(productId)}$`), { timeout: UI_TIMEOUT_MS })

  const fileNames = Array.from({ length: ITEM_COUNT }, (_, index) => `cancel-synthetic-${runKey}-${String(index + 1).padStart(3, '0')}.pdf`)
  const uploadFiles = fileNames.map((name) => ({ name, mimeType: 'application/pdf', buffer: fixture.buffer }))
  await pmPage.getByRole('button', { name: '문서 일괄 업로드' }).click()
  const batchDialog = pmPage.getByRole('dialog', { name: '문서 일괄 업로드' })
  const chooserPromise = pmPage.waitForEvent('filechooser', { timeout: UI_TIMEOUT_MS })
  await batchDialog.getByRole('button', { name: /PDF\/PPTX 파일 선택/ }).click()
  await (await chooserPromise).setFiles(uploadFiles)
  await batchDialog.getByText(`선택한 파일 ${ITEM_COUNT}`, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  const batchCreate = await waitForApi(pmPage, 'POST', new RegExp(`^/api/products/${escapeRegex(productId)}/document-batches$`), () => batchDialog.getByRole('button', { name: '일괄 처리 시작' }).click(), 60_000)
  const batchId = batchCreate.body?.batchId
  assert(batchId != null, 'Batch creation response omitted batchId')
  assert.equal(batchCreate.body?.acceptedItemCount, ITEM_COUNT, 'Server did not accept the full 100-item batch')
  assert.equal(batchCreate.body?.idempotentReplay, false, 'Fresh browser submission was unexpectedly replayed')
  assert.equal(batchCreate.body?.status, 'PENDING', 'Large batch was already terminal before the PM could cancel it')
  await pmPage.waitForURL(`**/document-batches/${batchId}`, { timeout: UI_TIMEOUT_MS })
  const cancelButton = pmPage.getByRole('button', { name: '배치 취소', exact: true })
  await cancelButton.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await capture(pmPage, 'pm-pending-before-cancel', screenshots)

  const cancel = await waitForApi(pmPage, 'POST', new RegExp(`^/api/document-batches/${escapeRegex(batchId)}/cancel$`), () => cancelButton.click())
  assert.equal(cancel.body?.status, 'CANCELLED', 'Cancellation API did not return the terminal CANCELLED batch')
  assert.equal(cancel.body?.cancellationReason, CANCELLATION_REASON, 'Cancellation API returned a different reason')

  const firstTerminal = await loadBatchDetail(pmPage, batchId, async () => {
    const refresh = pmPage.getByRole('button', { name: '새로고침', exact: true })
    await refresh.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
    await refresh.click()
  })
  assertCancelledSnapshot(firstTerminal, fileNames)
  await assertCancelledUi(pmPage, ITEM_COUNT, 'PM')
  await assertPmTerminalControls(pmPage)
  await capture(pmPage, 'pm-terminal-cancelled', screenshots)

  const reloaded = await loadBatchDetail(pmPage, batchId, () => pmPage.reload({ waitUntil: 'domcontentloaded' }))
  assertCancelledSnapshot(reloaded, fileNames)
  assert.deepEqual(durableProjection(reloaded), durableProjection(firstTerminal), 'Reload changed the persisted terminal cancellation projection')
  await assertCancelledUi(pmPage, ITEM_COUNT, 'reloaded PM')
  await assertPmTerminalControls(pmPage)
  await capture(pmPage, 'pm-terminal-cancelled-after-reload', screenshots)

  const reviewerUser = await login(reviewerPage, '컴플라이언스 검토자')
  assert.notEqual(pmUser?.userId, reviewerUser?.userId, 'PM and reviewer resolved to the same demo user')
  const reviewerSnapshot = await loadBatchDetail(reviewerPage, batchId, () => reviewerPage.goto(`${FRONTEND_URL}/document-batches/${batchId}`, { waitUntil: 'domcontentloaded' }))
  assertCancelledSnapshot(reviewerSnapshot, fileNames)
  assert.deepEqual(durableProjection(reviewerSnapshot), durableProjection(reloaded), 'Reviewer sees a different terminal projection from the PM')
  await assertCancelledUi(reviewerPage, ITEM_COUNT, 'reviewer')
  await reviewerPage.getByText('검토자는 조회만 가능합니다.', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  assert.equal(await reviewerPage.getByRole('columnheader', { name: '관리', exact: true }).count(), 0, 'Reviewer can see the management column')
  assert.equal(await reviewerPage.getByRole('button', { name: /^(배치 취소|배치 격리|취소|격리|재시도)$/ }).count(), 0, 'Reviewer can see mutation controls')
  await capture(reviewerPage, 'reviewer-terminal-cancelled-read-only', screenshots)

  await Promise.all([...responseTasks])
  assert.equal(browserSignals.expectedCancellationResponses.filter((entry) => entry.status === 200).length, 1, 'Exactly one successful cancellation response was not observed')
  const postCancelMutations = browserSignals.apiResponses.filter((entry) =>
    entry.method === 'POST'
      && (
        /^\/api\/document-batches\/\d+\/quarantine$/.test(entry.path)
        || /^\/api\/document-batches\/\d+\/items\/\d+\/(?:cancel|quarantine|retry)$/.test(entry.path)
      ))
  assert.deepEqual(postCancelMutations, [], `Terminal batch was mutated after cancellation: ${JSON.stringify(postCancelMutations)}`)
  assertNoBrowserFailures()
  receipt = {
    ...receipt,
    outcome: 'PASS',
    completedAt: new Date().toISOString(),
    actors: {
      productManager: { userId: pmUser.userId, role: pmUser.role },
      reviewer: { userId: reviewerUser.userId, role: reviewerUser.role },
      isolatedBrowserContexts: true,
    },
    ids: { productId, batchId },
    product: { name: productName, createdThroughPmUi: true },
    submission: {
      createdThroughPmUi: true,
      requestedItemCount: ITEM_COUNT,
      acceptedItemCount: batchCreate.body.acceptedItemCount,
      idempotentReplay: batchCreate.body.idempotentReplay,
      syntheticFixture: { path: FIXTURE_PATH, bytes: fixture.bytes, sha256: fixture.sha256 },
      uniqueSyntheticFileNames: fileNames,
    },
    cancellation: {
      performedThroughPmUi: true,
      responseStatus: cancel.response.status(),
      reason: CANCELLATION_REASON,
      firstTerminal,
      persistedAfterReload: true,
      countersReconciled: true,
      itemReasonsAndTimestampsPersisted: true,
    },
    terminalMutationContract: {
      batchCancelVisible: false,
      batchQuarantineVisible: false,
      itemCancelVisible: false,
      itemQuarantineVisible: false,
      cancelledItemRetryVisible: true,
      retryExecuted: false,
      observedTerminalMutationRequests: postCancelMutations,
      basis: 'The product contract explicitly exposes retry for CANCELLED items; the E2E verifies visibility without mutating the terminal receipt.',
    },
    reviewerAuthorization: { detailVisible: true, readOnlyNoticeVisible: true, managementColumnVisible: false, mutationControlsVisible: false },
    screenshots,
    browserSignals,
  }
  console.log(`PASS real batch cancellation browser E2E; receipt: ${receiptPath}`)
} catch (error) {
  failure = error
  await Promise.allSettled([...responseTasks])
  const screenshots = []
  if (pmPage) await capture(pmPage, 'pm-failure', screenshots).catch(() => {})
  if (reviewerPage) await capture(reviewerPage, 'reviewer-failure', screenshots).catch(() => {})
  receipt = {
    ...receipt,
    outcome: 'FAIL',
    completedAt: new Date().toISOString(),
    error: { name: error.name, message: error.message, stack: error.stack },
    screenshots,
    browserSignals,
  }
} finally {
  await Promise.allSettled([...responseTasks])
  await pmContext?.close()
  await reviewerContext?.close()
  await browser?.close()
  await mkdir(dirname(receiptPath), { recursive: true })
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx' })
}

if (failure) throw new Error(`${failure.message}\nFailure receipt: ${receiptPath}`, { cause: failure })
