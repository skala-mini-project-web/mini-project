#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.BATCH_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.BATCH_BACKEND_URL || process.env.VITE_API_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '')
const AI_URL = (process.env.BATCH_AI_URL || process.env.AI_SERVICE_URL || 'http://127.0.0.1:8000').replace(/\/$/, '')
const VALID_PDF_PATH = fileURLToPath(new URL('../../data/synthetic-financial-corpus/documents/river-flex-savings/product-overview.pdf', import.meta.url))
const MALFORMED_PDF_PATH = fileURLToPath(new URL('../../data/demo-corpus/documents/product/MALFORMED-SYNTHETIC.pdf', import.meta.url))
const REPORT_DIRECTORY = fileURLToPath(new URL('../../docs/reports/v1', import.meta.url))
const REQUEST_TIMEOUT_MS = 8_000
const UI_TIMEOUT_MS = 15_000
const BATCH_TIMEOUT_MS = 180_000
const TERMINAL_BATCH_STATES = new Set(['SUCCEEDED', 'CANCELLED', 'QUARANTINED'])
const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
const receiptPath = process.env.BATCH_E2E_RECEIPT || `${REPORT_DIRECTORY}/real-batch-e2e-${runKey}.json`
const validFileName = `valid-synthetic-${runKey}.pdf`
const malformedFileName = `malformed-synthetic-${runKey}.pdf`

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
  const [validStat, malformedStat, validBuffer, malformedBuffer] = await Promise.all([
    stat(VALID_PDF_PATH),
    stat(MALFORMED_PDF_PATH),
    readFile(VALID_PDF_PATH),
    readFile(MALFORMED_PDF_PATH),
  ])
  assert(validStat.isFile() && validStat.size > 0, `Valid PDF is empty: ${VALID_PDF_PATH}`)
  assert(malformedStat.isFile() && malformedStat.size > 0, `Malformed PDF is empty: ${MALFORMED_PDF_PATH}`)
  assert.equal(validBuffer.subarray(0, 4).toString('ascii'), '%PDF', 'Valid fixture does not pass frontend PDF magic-byte validation')
  assert.equal(malformedBuffer.subarray(0, 4).toString('ascii'), '%PDF', 'Malformed fixture does not pass frontend PDF magic-byte validation')
  assert(malformedBuffer.length < 128, 'Malformed fixture is no longer the minimal corrupt-PDF fixture')

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
    validBuffer,
    malformedBuffer,
    validBytes: validStat.size,
    malformedBytes: malformedStat.size,
    validSha256: createHash('sha256').update(validBuffer).digest('hex'),
    malformedSha256: createHash('sha256').update(malformedBuffer).digest('hex'),
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
  const pathname = new URL(url).pathname
  const index = pathname.indexOf('/api/')
  return index === -1 ? null : pathname.slice(index)
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
        if (response.status() < 200 || response.status() >= 300) {
          const body = await response.json().catch(() => null)
          const unexpected = { ...entry, errorCode: body?.errorCode || null, message: body?.message || null }
          browserSignals.unexpectedApiResponses.push(unexpected)
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

async function loadBatchDetail(page, batchId) {
  const batchPath = `/api/document-batches/${batchId}`
  const itemsPath = `${batchPath}/items`
  const batchPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === batchPath, { timeout: UI_TIMEOUT_MS })
  const itemsPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === itemsPath, { timeout: UI_TIMEOUT_MS })
  await page.goto(`${FRONTEND_URL}/document-batches/${batchId}`, { waitUntil: 'domcontentloaded' })
  const [batchResponse, itemsResponse] = await Promise.all([batchPromise, itemsPromise])
  assert(batchResponse.ok(), `Batch detail failed: HTTP ${batchResponse.status()}`)
  assert(itemsResponse.ok(), `Batch items failed: HTTP ${itemsResponse.status()}`)
  return { batch: await batchResponse.json(), items: (await itemsResponse.json()).items || [] }
}

async function refreshBatchDetail(page, batchId) {
  const batchPath = `/api/document-batches/${batchId}`
  const itemsPath = `${batchPath}/items`
  const batchPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === batchPath, { timeout: UI_TIMEOUT_MS })
  const itemsPromise = page.waitForResponse((response) => response.request().method() === 'GET' && apiPath(response.url()) === itemsPath, { timeout: UI_TIMEOUT_MS })
  const refreshButton = page.getByRole('button', { name: '새로고침' })
  await refreshButton.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await refreshButton.click()
  const [batchResponse, itemsResponse] = await Promise.all([batchPromise, itemsPromise])
  assert(batchResponse.ok(), `Batch refresh failed: HTTP ${batchResponse.status()}`)
  assert(itemsResponse.ok(), `Batch item refresh failed: HTTP ${itemsResponse.status()}`)
  return { batch: await batchResponse.json(), items: (await itemsResponse.json()).items || [] }
}

async function waitForTerminalBatch(page, batchId) {
  const deadline = Date.now() + BATCH_TIMEOUT_MS
  let snapshot
  while (Date.now() < deadline) {
    snapshot = await refreshBatchDetail(page, batchId)
    if (TERMINAL_BATCH_STATES.has(snapshot.batch?.status) && snapshot.items.every((item) => TERMINAL_BATCH_STATES.has(item.status))) return snapshot
    await page.waitForTimeout(1_000)
  }
  throw new Error(`Batch ${batchId} did not reach terminal state within ${BATCH_TIMEOUT_MS}ms; last snapshot: ${JSON.stringify(snapshot)}`)
}

async function assertTerminalUi(page, batch, items) {
  assert.equal(batch.status, 'QUARANTINED', 'Malformed item did not make the completed batch QUARANTINED')
  assert.equal(batch.requestedItemCount, 2, 'Batch requested count was not two')
  assert.equal(batch.succeededItemCount, 1, 'Batch did not report one successful item')
  assert.equal(batch.quarantinedItemCount, 1, 'Batch did not report one quarantined item')
  assert.equal(batch.pendingItemCount, 0, 'Terminal batch still reported pending items')
  assert.equal(batch.leasedItemCount, 0, 'Terminal batch still reported leased items')
  assert.equal(batch.retryWaitingItemCount, 0, 'Terminal batch still reported retry-wait items')
  assert(batch.terminalAt, 'Terminal batch has no persisted terminal timestamp')
  assert.equal(items.length, 2, 'Batch detail did not return exactly two items')

  const validItem = items.find((item) => item.fileName === validFileName)
  const malformedItem = items.find((item) => item.fileName === malformedFileName)
  assert(validItem, 'Valid synthetic PDF item is missing')
  assert(malformedItem, 'Malformed synthetic PDF item is missing')
  assert.equal(validItem.status, 'SUCCEEDED', 'Valid synthetic PDF did not succeed')
  assert.equal(malformedItem.status, 'QUARANTINED', 'Malformed synthetic PDF did not quarantine')
  assert(validItem.terminalAt, 'Successful item has no persisted terminal timestamp')
  assert(malformedItem.terminalAt, 'Quarantined item has no persisted terminal timestamp')
  assert(malformedItem.errorCode, 'Malformed item has no durable error code')
  assert(malformedItem.errorMessage, 'Malformed item has no durable error message')
  assert(malformedItem.errorTimestamp, 'Malformed item has no durable error timestamp')

  await page.getByText('처리가 종료되었습니다.', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await page.locator('.top .state', { hasText: '격리' }).waitFor({ timeout: UI_TIMEOUT_MS })
  const summary = await page.locator('section.summary > div > strong').allTextContents()
  assert.deepEqual(summary.map((text) => text.replace(/\s+/g, ' ').trim()), ['2 / 2', '0', '0', '0', '1', '1'], 'Browser-visible summary counts do not match terminal API state')
  const malformedRow = page.locator('tbody tr').filter({ hasText: malformedFileName })
  await malformedRow.getByText(malformedItem.errorCode, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  assert.match((await malformedRow.locator('.error-message').innerText()).trim(), /\S/, 'Malformed item error message is not browser-visible')
  return { validItem, malformedItem }
}

function csvRows(csv) {
  return csv.replace(/^\uFEFF/, '').trim().split(/\r?\n/)
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
let receipt = {
  outcome: 'FAIL',
  mode: 'real-docker-browser',
  issue: 86,
  runKey,
  startedAt: new Date().toISOString(),
  services: { frontend: FRONTEND_URL, backend: BACKEND_URL, ai: AI_URL },
  receiptPath,
}
let terminalError

try {
  const fixtures = await preflight()
  const chromium = await loadChromium()
  browser = await chromium.launch({
    headless: process.env.BATCH_HEADLESS !== 'false',
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}),
  })
  pmContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true })
  reviewerContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true })
  monitorContext(pmContext, 'PM')
  monitorContext(reviewerContext, 'reviewer')
  const pmPage = await pmContext.newPage()
  const reviewerPage = await reviewerContext.newPage()

  const pmUser = await login(pmPage, '상품 담당자')
  const productName = `Durable Batch E2E ${runKey}`
  await pmPage.goto(`${FRONTEND_URL}/products`, { waitUntil: 'domcontentloaded' })
  await pmPage.getByRole('heading', { name: '상품', exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await pmPage.getByRole('button', { name: '상품 등록' }).first().click()
  const productDialog = pmPage.getByRole('dialog', { name: '상품 등록' })
  await productDialog.getByLabel('상품명').fill(productName)
  await productDialog.getByLabel('설명').fill('Issue #86 real Docker durable batch browser E2E')
  const productCreate = await waitForApi(pmPage, 'POST', /^\/api\/products$/, () => productDialog.getByRole('button', { name: '등록', exact: true }).click())
  const productId = productCreate.body?.productId
  assert(productId != null, 'Product creation response omitted productId')
  await pmPage.waitForURL(new RegExp(`/products/${escapeRegex(productId)}$`), { timeout: UI_TIMEOUT_MS })

  await pmPage.getByRole('button', { name: '문서 일괄 업로드' }).click()
  const batchDialog = pmPage.getByRole('dialog', { name: '문서 일괄 업로드' })
  const chooserPromise = pmPage.waitForEvent('filechooser', { timeout: UI_TIMEOUT_MS })
  await batchDialog.getByRole('button', { name: /PDF\/PPTX 파일 선택/ }).click()
  const chooser = await chooserPromise
  await chooser.setFiles([
    { name: validFileName, mimeType: 'application/pdf', buffer: fixtures.validBuffer },
    { name: malformedFileName, mimeType: 'application/pdf', buffer: fixtures.malformedBuffer },
  ])
  await batchDialog.getByText('선택한 파일').waitFor({ timeout: UI_TIMEOUT_MS })
  await batchDialog.getByText(validFileName, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await batchDialog.getByText(malformedFileName, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  const batchCreate = await waitForApi(pmPage, 'POST', new RegExp(`^/api/products/${escapeRegex(productId)}/document-batches$`), () => batchDialog.getByRole('button', { name: '일괄 처리 시작' }).click())
  const batchId = batchCreate.body?.batchId
  assert(batchId != null, 'Batch creation response omitted batchId')
  assert.equal(batchCreate.body?.acceptedItemCount, 2, 'Batch submission did not accept both items')
  assert.equal(batchCreate.body?.idempotentReplay, false, 'Fresh browser submission was unexpectedly an idempotency replay')
  await pmPage.waitForURL(`**/document-batches/${batchId}`, { timeout: UI_TIMEOUT_MS })

  const firstTerminal = await waitForTerminalBatch(pmPage, batchId)
  const firstItems = await assertTerminalUi(pmPage, firstTerminal.batch, firstTerminal.items)
  const attemptsBeforeRetry = firstItems.malformedItem.attemptCount
  assert(attemptsBeforeRetry >= 1, 'Malformed item was quarantined without an attempt')

  const malformedRow = pmPage.locator('tbody tr').filter({ hasText: malformedFileName })
  const retry = await waitForApi(pmPage, 'POST', new RegExp(`^/api/document-batches/${escapeRegex(batchId)}/items/${escapeRegex(firstItems.malformedItem.itemId)}/retry$`), () => malformedRow.getByRole('button', { name: '재시도', exact: true }).click())
  assert.equal(retry.body?.status, 'PENDING', 'Retry action did not durably requeue the quarantined item')
  const secondTerminal = await waitForTerminalBatch(pmPage, batchId)
  const secondItems = await assertTerminalUi(pmPage, secondTerminal.batch, secondTerminal.items)
  assert(secondItems.malformedItem.attemptCount > attemptsBeforeRetry, 'Retried malformed item did not record another durable attempt')

  const downloadPromise = pmPage.waitForEvent('download', { timeout: UI_TIMEOUT_MS })
  await pmPage.getByRole('button', { name: '오류 CSV' }).click()
  const download = await downloadPromise
  assert.equal(download.suggestedFilename(), `document-batch-${batchId}-errors.csv`, 'CSV download filename did not identify the batch')
  const downloadedPath = await download.path()
  assert(downloadedPath, 'Browser did not persist the CSV download')
  const csv = await readFile(downloadedPath, 'utf8')
  const rows = csvRows(csv)
  assert.equal(rows[0], 'batchId,ordinal,filename,state,attempts,errorCode,errorMessage,timestamp', 'CSV header contract changed')
  assert.equal(rows.length, 3, 'CSV did not contain one row per batch item')
  assert(rows.some((row) => row.includes(validFileName) && /,["']?SUCCEEDED["']?,/.test(row)), 'CSV omitted the successful PDF state')
  assert(rows.some((row) => row.includes(malformedFileName) && /,["']?QUARANTINED["']?,/.test(row) && row.includes(secondItems.malformedItem.errorCode)), 'CSV omitted the malformed PDF error/state')

  const reviewerUser = await login(reviewerPage, '컴플라이언스 검토자')
  assert.notEqual(pmUser?.userId, reviewerUser?.userId, 'PM and reviewer resolved to the same demo user')
  const reviewerSnapshot = await loadBatchDetail(reviewerPage, batchId)
  assert.equal(reviewerSnapshot.batch.status, secondTerminal.batch.status, 'Reviewer did not see the durable terminal batch state')
  assert.deepEqual(reviewerSnapshot.items.map((item) => item.status), secondTerminal.items.map((item) => item.status), 'Reviewer item states differ from PM item states')
  await reviewerPage.getByText('검토자는 조회만 가능합니다.', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  assert.equal(await reviewerPage.getByRole('columnheader', { name: '관리', exact: true }).count(), 0, 'Reviewer can see the management column')
  assert.equal(await reviewerPage.getByRole('button', { name: '재시도', exact: true }).count(), 0, 'Reviewer can see retry controls')
  assert.equal(await reviewerPage.getByRole('button', { name: /^(배치 취소|배치 격리|취소|격리)$/ }).count(), 0, 'Reviewer can see batch/item mutation controls')
  assert.equal(await reviewerPage.getByRole('button', { name: '오류 CSV' }).count(), 1, 'Reviewer cannot access the read-only error report')

  await Promise.all([...responseTasks])
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
      acceptedItemCount: batchCreate.body.acceptedItemCount,
      idempotentReplay: batchCreate.body.idempotentReplay,
      files: [
        { fileName: validFileName, bytes: fixtures.validBytes, sha256: fixtures.validSha256, expected: 'SUCCEEDED' },
        { fileName: malformedFileName, bytes: fixtures.malformedBytes, sha256: fixtures.malformedSha256, expected: 'QUARANTINED' },
      ],
    },
    firstTerminal: { batch: firstTerminal.batch, items: firstTerminal.items },
    action: {
      type: 'RETRY_QUARANTINED_ITEM',
      itemId: firstItems.malformedItem.itemId,
      responseStatus: retry.response.status(),
      attemptsBefore: attemptsBeforeRetry,
      attemptsAfter: secondItems.malformedItem.attemptCount,
    },
    finalTerminal: { batch: secondTerminal.batch, items: secondTerminal.items },
    csvReport: {
      downloadedThroughUi: true,
      suggestedFilename: download.suggestedFilename(),
      header: rows[0],
      rowCount: rows.length - 1,
      containsSuccessfulItem: true,
      containsMalformedError: true,
    },
    reviewerAuthorization: {
      detailVisible: true,
      readOnlyNoticeVisible: true,
      managementColumnVisible: false,
      mutationControlsVisible: false,
      csvReportVisible: true,
    },
    browserSignals,
  }
  console.log(`PASS real durable batch browser E2E; receipt: ${receiptPath}`)
} catch (error) {
  terminalError = error
  await Promise.allSettled([...responseTasks])
  receipt = {
    ...receipt,
    outcome: 'FAIL',
    completedAt: new Date().toISOString(),
    error: { name: error.name, message: error.message, stack: error.stack },
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
