#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.KOREAN_OCR_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.KOREAN_OCR_BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const FIXTURE_DIR = fileURLToPath(new URL('../../data/synthetic-ocr-fixtures/', import.meta.url))
const GENERATOR_PATH = fileURLToPath(new URL('../../tools/generate_synthetic_ocr_fixtures.py', import.meta.url))
const REPORT_DIR = fileURLToPath(new URL('../../docs/reports/runtime/', import.meta.url))
const UI_TIMEOUT_MS = 20_000
const EXTRACTION_TIMEOUT_MS = 240_000
const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
const receiptPath = process.env.KOREAN_OCR_E2E_RECEIPT || `${REPORT_DIR}/korean-ocr-e2e-${runKey}.json`
const fixtureNames = ['born-digital-ko-en.pdf', 'image-only-korean-scan.pdf', 'mixed-three-page.pdf']
const expectedRoutes = {
  'born-digital-ko-en.pdf': ['PDFBOX_TEXT'],
  'image-only-korean-scan.pdf': ['OCR_KOR_ENG'],
  'mixed-three-page.pdf': ['PDFBOX_TEXT', 'OCR_KOR_ENG', 'PDFBOX_TEXT'],
}
const expectedApi = [
  /^\/api\/demo\/users$/,
  /^\/api\/demo\/session$/,
  /^\/api\/dashboard\/(?:me|compliance)$/,
  /^\/api\/products(?:\/\d+)?$/,
  /^\/api\/products\/\d+\/document-batches$/,
  /^\/api\/document-batches\/\d+(?:\/items)?$/,
  /^\/api\/documents\/\d+(?:\/text)?$/,
  /^\/api\/documents\/\d+\/pages\/\d+\/render$/,
  /^\/api\/reviews$/,
]

const sha256 = (data) => createHash('sha256').update(data).digest('hex')
const escapeRegex = (value) => String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

async function loadChromium() {
  try { return (await import('playwright')).chromium }
  catch (playwrightError) {
    try { return (await import('playwright-core')).chromium }
    catch { throw new Error('Playwright or playwright-core is required for the real browser E2E.', { cause: playwrightError }) }
  }
}

async function fetchOk(url, label) {
  let response
  try { response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(8_000) }) }
  catch (error) { throw new Error(`${label} unavailable at ${url}: ${error.message}`) }
  assert(response.ok, `${label} failed at ${url}: HTTP ${response.status}`)
  return response
}

async function preflight() {
  const manifestBuffer = await readFile(`${FIXTURE_DIR}/manifest.json`)
  const manifest = JSON.parse(manifestBuffer.toString('utf8'))
  assert.equal(manifest.generator, 'argus-synthetic-korean-ocr-v1')
  assert.equal(manifest.provenance?.classification, 'SYNTHETIC_ONLY')
  assert.equal(manifest.provenance?.contains_real_customer_data, false)
  assert.equal(manifest.provenance?.contains_real_company_or_product_data, false)
  assert.equal(manifest.generator_sha256, sha256(await readFile(GENERATOR_PATH)), 'fixture generator provenance hash is stale')
  const entries = new Map(manifest.fixtures.map((entry) => [entry.file, entry]))
  const fixtures = {}
  for (const name of fixtureNames) {
    const entry = entries.get(name)
    assert(entry?.synthetic, `${name} is not declared synthetic`)
    assert.deepEqual(entry.expectations?.expected_page_routes, expectedRoutes[name], `${name} manifest route expectation changed`)
    const [buffer, info] = await Promise.all([readFile(`${FIXTURE_DIR}/${name}`), stat(`${FIXTURE_DIR}/${name}`)])
    assert(info.isFile() && info.size > 0, `${name} is missing or empty`)
    assert.equal(entry.bytes, buffer.length, `${name} manifest byte count mismatch`)
    assert.equal(entry.sha256, sha256(buffer), `${name} manifest SHA-256 mismatch`)
    assert.equal(buffer.subarray(0, 5).toString('ascii'), '%PDF-', `${name} has no PDF signature`)
    fixtures[name] = { buffer, bytes: buffer.length, sha256: entry.sha256, expectations: entry.expectations }
  }
  const [frontend, backend] = await Promise.all([
    fetchOk(FRONTEND_URL, 'Frontend'),
    fetchOk(`${BACKEND_URL}/actuator/health`, 'Spring backend'),
  ])
  assert.match(frontend.headers.get('content-type') || '', /text\/html/i)
  assert.equal((await backend.json()).status, 'UP')
  return { manifestSha256: sha256(manifestBuffer), fixtures }
}

const browserSignals = {
  pageErrors: [], consoleErrors: [], requestFailures: [], apiResponses: [],
  unexpectedApiCalls: [], unexpectedApiResponses: [],
}
const responseTasks = new Set()

function apiPath(url) {
  const pathname = new URL(url).pathname
  const index = pathname.indexOf('/api/')
  return index < 0 ? null : pathname.slice(index)
}

function monitor(context, actor) {
  context.on('page', (page) => {
    page.on('pageerror', (error) => browserSignals.pageErrors.push(`${actor}: ${error.message}`))
    page.on('console', (message) => { if (message.type() === 'error') browserSignals.consoleErrors.push(`${actor}: ${message.text()}`) })
    page.on('requestfailed', (request) => browserSignals.requestFailures.push(`${actor}: ${request.method()} ${request.url()} (${request.failure()?.errorText || 'failed'})`))
    page.on('response', (response) => {
      const path = apiPath(response.url())
      if (!path) return
      const entry = { actor, method: response.request().method(), path, status: response.status() }
      browserSignals.apiResponses.push(entry)
      if (!expectedApi.some((pattern) => pattern.test(path))) browserSignals.unexpectedApiCalls.push(entry)
      if (response.status() < 200 || response.status() >= 300) {
        const task = response.json().catch(() => null).then((body) => {
          browserSignals.unexpectedApiResponses.push({ ...entry, errorCode: body?.errorCode || null, message: body?.message || null })
        })
        responseTasks.add(task)
        task.finally(() => responseTasks.delete(task))
      }
    })
  })
}

async function waitForApi(page, method, pattern, action, timeout = UI_TIMEOUT_MS) {
  const pending = page.waitForResponse((response) => response.request().method() === method && pattern.test(apiPath(response.url()) || ''), { timeout })
  await action()
  const response = await pending
  const body = await response.json().catch(() => null)
  assert(response.ok(), `${method} ${apiPath(response.url())} failed: HTTP ${response.status()} ${body?.errorCode || ''}`)
  return body
}

async function login(page, roleName) {
  await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded' })
  const role = page.locator('button.role', { hasText: roleName })
  await role.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  assert(await role.isEnabled(), `${roleName} demo user is unavailable`)
  const session = await waitForApi(page, 'POST', /^\/api\/demo\/session$/, () => role.click())
  await page.waitForURL('**/dashboard', { timeout: UI_TIMEOUT_MS })
  await page.waitForLoadState('networkidle')
  return session
}

async function createProduct(page) {
  const name = `Korean OCR E2E ${runKey}`
  await page.goto(`${FRONTEND_URL}/products`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('heading', { name: '상품', exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await page.getByRole('button', { name: '상품 등록' }).first().click()
  const dialog = page.getByRole('dialog', { name: '상품 등록' })
  await dialog.getByLabel('상품명').fill(name)
  await dialog.getByLabel('설명').fill('Issue #87 deterministic synthetic-only Korean OCR browser QA')
  const created = await waitForApi(page, 'POST', /^\/api\/products$/, () => dialog.getByRole('button', { name: '등록', exact: true }).click())
  assert(created?.productId != null, 'product creation omitted productId')
  await page.waitForURL(new RegExp(`/products/${escapeRegex(created.productId)}$`), { timeout: UI_TIMEOUT_MS })
  return { productId: created.productId, name }
}

async function uploadBatch(page, productId, fixtures) {
  await page.getByRole('button', { name: '문서 일괄 업로드' }).click()
  const dialog = page.getByRole('dialog', { name: '문서 일괄 업로드' })
  const chooserPending = page.waitForEvent('filechooser', { timeout: UI_TIMEOUT_MS })
  await dialog.getByRole('button', { name: /PDF\/PPTX 파일 선택/ }).click()
  const chooser = await chooserPending
  await chooser.setFiles(fixtureNames.map((name) => ({ name, mimeType: 'application/pdf', buffer: fixtures[name].buffer })))
  for (const name of fixtureNames) await dialog.getByText(name, { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  const batch = await waitForApi(page, 'POST', new RegExp(`^/api/products/${escapeRegex(productId)}/document-batches$`), () => dialog.getByRole('button', { name: '일괄 처리 시작' }).click())
  assert.equal(batch?.acceptedItemCount, fixtureNames.length)
  assert.equal(batch?.idempotentReplay, false)
  await page.waitForURL(`**/document-batches/${batch.batchId}`, { timeout: UI_TIMEOUT_MS })
  return batch.batchId
}

async function refreshBatch(page, batchId) {
  const detailPath = `/api/document-batches/${batchId}`
  const itemsPath = `${detailPath}/items`
  const detailPending = page.waitForResponse((r) => r.request().method() === 'GET' && apiPath(r.url()) === detailPath, { timeout: UI_TIMEOUT_MS })
  const itemsPending = page.waitForResponse((r) => r.request().method() === 'GET' && apiPath(r.url()) === itemsPath, { timeout: UI_TIMEOUT_MS })
  await page.getByRole('button', { name: '새로고침' }).click()
  const [detailResponse, itemsResponse] = await Promise.all([detailPending, itemsPending])
  assert(detailResponse.ok() && itemsResponse.ok(), 'batch refresh failed')
  return { batch: await detailResponse.json(), items: (await itemsResponse.json()).items || [] }
}

async function waitForSuccessfulBatch(page, batchId) {
  const deadline = Date.now() + EXTRACTION_TIMEOUT_MS
  let snapshot
  while (Date.now() < deadline) {
    snapshot = await refreshBatch(page, batchId)
    if (snapshot.batch?.status === 'SUCCEEDED' && snapshot.items.every((item) => item.status === 'SUCCEEDED')) return snapshot
    if (['QUARANTINED', 'CANCELLED'].includes(snapshot.batch?.status)) throw new Error(`OCR batch terminal failure: ${JSON.stringify(snapshot)}`)
    await page.waitForTimeout(1_000)
  }
  throw new Error(`OCR batch did not succeed within ${EXTRACTION_TIMEOUT_MS}ms: ${JSON.stringify(snapshot)}`)
}

function assertPageContract(document, fileName) {
  assert.equal(document.extractStatus, 'READY', `${fileName} is not READY`)
  assert(document.currentRunId != null, `${fileName} omitted currentRunId`)
  assert.match(document.currentTextHash || '', /^[0-9a-f]{64}$/, `${fileName} omitted currentTextHash`)
  assert.equal(document.confirmed, false, `${fileName} was unexpectedly pre-confirmed`)
  assert.equal(document.requiresConfirmation, true, `${fileName} did not require PM confirmation`)
  assert.deepEqual(document.pages.map((page) => page.selectedMethod), expectedRoutes[fileName], `${fileName} page routing mismatch`)
  for (const page of document.pages) {
    assert.match(page.textHash || '', /^[0-9a-f]{64}$/)
    if (page.selectedMethod === 'OCR_KOR_ENG') {
      assert.equal(page.ocrLanguage, 'kor+eng')
      assert.match(page.ocrEngine || '', /\S/)
      assert.match(page.ocrModelVersion || '', /\S/)
      assert(Number(page.ocrConfidence) >= 0 && Number(page.ocrConfidence) <= 100)
      assert(['LOW', 'MEDIUM', 'HIGH'].includes(page.ocrConfidenceBand))
      assert.match(page.renderArtifactHash || '', /^[0-9a-f]{64}$/)
      assert.match(page.renderArtifactUrl || '', new RegExp(`/api/documents/${document.documentId}/pages/${page.pageNumber}/render$`))
    } else {
      assert.equal(page.ocrConfidence, null)
      assert.equal(page.renderArtifactHash, null)
    }
  }
}

async function openDocument(page, documentId) {
  const path = `/api/documents/${documentId}`
  const pending = page.waitForResponse((r) => r.request().method() === 'GET' && apiPath(r.url()) === path, { timeout: UI_TIMEOUT_MS })
  await page.goto(`${FRONTEND_URL}/documents/${documentId}`, { waitUntil: 'domcontentloaded' })
  const response = await pending
  assert(response.ok(), `GET ${path} failed`)
  const document = await response.json()
  await page.getByText('백엔드 추출 provenance', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  return document
}

async function assertPmAndConfirm(page, document, fileName) {
  assertPageContract(document, fileName)
  await page.getByText('확인되지 않음', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  await page.getByText('백엔드의 현재 실행·텍스트 확인이 완료되어야 분석할 수 있습니다.', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  assert(await page.getByRole('button', { name: '분석으로 이동' }).isDisabled(), `${fileName}: unconfirmed analysis gate is open`)
  const pageCards = page.locator('ol.pages > li.page')
  assert.equal(await pageCards.count(), expectedRoutes[fileName].length)
  for (let index = 0; index < expectedRoutes[fileName].length; index += 1) {
    const card = pageCards.nth(index)
    await card.getByText(`페이지 ${index + 1}`, { exact: true }).waitFor()
    await card.getByText(expectedRoutes[fileName][index] === 'OCR_KOR_ENG' ? 'OCR (kor+eng)' : 'PDFBOX', { exact: true }).waitFor()
  }
  const textarea = page.locator('textarea')
  assert(!(await textarea.isDisabled()), `${fileName}: PM text editor is disabled`)
  await textarea.fill(`${await textarea.inputValue()}\nPM 확인 메모: 합성 OCR QA ${runKey}`)
  const saved = await waitForApi(page, 'PATCH', new RegExp(`^/api/documents/${document.documentId}/text$`), () => page.getByRole('button', { name: '텍스트 저장' }).click())
  assert.equal(saved.confirmed, false)
  assert.equal(saved.requiresConfirmation, true)
  const confirmed = await waitForApi(page, 'PATCH', new RegExp(`^/api/documents/${document.documentId}/text$`), () => page.getByRole('button', { name: '현재 실행·텍스트 확정' }).click())
  assert.equal(confirmed.confirmed, true)
  assert.equal(confirmed.requiresConfirmation, false)
  assert.equal(confirmed.currentRunId, document.currentRunId)
  await page.getByText('현재 실행·텍스트 확인됨', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
  assert(!(await page.getByRole('button', { name: '분석으로 이동' }).isDisabled()), `${fileName}: analysis gate remained closed after confirmation`)
  return confirmed
}

async function assertReviewerReadOnly(page, expectedDocuments) {
  const evidence = []
  for (const [fileName, expected] of Object.entries(expectedDocuments)) {
    const loaded = await openDocument(page, expected.documentId)
    assert.deepEqual(loaded.pages, expected.pages, `${fileName}: reviewer provenance differs from confirmed PM response`)
    assert.equal(loaded.currentRunId, expected.currentRunId)
    assert.equal(loaded.currentTextHash, expected.currentTextHash)
    assert.equal(loaded.confirmed, true)
    assert(await page.locator('textarea').isDisabled(), `${fileName}: reviewer can edit confirmed text`)
    assert.equal(await page.getByRole('button', { name: /^(텍스트 저장|현재 실행·텍스트 확정|분석으로 이동|재시도)$/ }).count(), 0, `${fileName}: reviewer mutation control is visible`)
    const reloadPath = `/api/documents/${expected.documentId}`
    const pending = page.waitForResponse((r) => r.request().method() === 'GET' && apiPath(r.url()) === reloadPath, { timeout: UI_TIMEOUT_MS })
    await page.reload({ waitUntil: 'domcontentloaded' })
    const reloaded = await (await pending).json()
    assert.deepEqual(reloaded.pages, expected.pages, `${fileName}: persisted page provenance changed after reload`)
    assert.equal(reloaded.confirmed, true)
    await page.getByText('추출 원문 · 읽기 전용', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
    for (const route of expectedRoutes[fileName]) {
      await page.getByText(route === 'OCR_KOR_ENG' ? 'OCR (kor+eng)' : 'PDFBOX', { exact: true }).first().waitFor()
    }
    evidence.push({ fileName, documentId: expected.documentId, pages: reloaded.pages, textareaDisabled: true, mutationControls: 0, provenanceReloaded: true })
  }
  return evidence
}

function assertCleanSignals() {
  assert.deepEqual(browserSignals.pageErrors, [], `page errors: ${browserSignals.pageErrors.join('\n')}`)
  assert.deepEqual(browserSignals.consoleErrors, [], `console errors: ${browserSignals.consoleErrors.join('\n')}`)
  assert.deepEqual(browserSignals.requestFailures, [], `request failures: ${browserSignals.requestFailures.join('\n')}`)
  assert.deepEqual(browserSignals.unexpectedApiCalls, [], `unexpected API calls: ${JSON.stringify(browserSignals.unexpectedApiCalls)}`)
  assert.deepEqual(browserSignals.unexpectedApiResponses, [], `unexpected API responses: ${JSON.stringify(browserSignals.unexpectedApiResponses)}`)
}

let browser
let pmContext
let reviewerContext
let failure
let receipt = { outcome: 'FAIL', mode: 'real-local-docker-browser', issue: 87, runKey, startedAt: new Date().toISOString(), receiptPath, services: { frontend: FRONTEND_URL, backend: BACKEND_URL } }

try {
  const fixtureEvidence = await preflight()
  const chromium = await loadChromium()
  browser = await chromium.launch({ headless: process.env.KOREAN_OCR_HEADLESS !== 'false', ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}) })
  pmContext = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
  reviewerContext = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
  monitor(pmContext, 'PM')
  monitor(reviewerContext, 'reviewer')
  const pmPage = await pmContext.newPage()
  const reviewerPage = await reviewerContext.newPage()
  const pm = await login(pmPage, '상품 담당자')
  const product = await createProduct(pmPage)
  const batchId = await uploadBatch(pmPage, product.productId, fixtureEvidence.fixtures)
  const terminalBatch = await waitForSuccessfulBatch(pmPage, batchId)
  assert.equal(terminalBatch.items.length, 3)
  const itemsByName = Object.fromEntries(terminalBatch.items.map((item) => [item.fileName, item]))
  const confirmedDocuments = {}
  for (const fileName of fixtureNames) {
    assert(itemsByName[fileName]?.documentId != null, `${fileName}: batch item omitted documentId`)
    const document = await openDocument(pmPage, itemsByName[fileName].documentId)
    confirmedDocuments[fileName] = await assertPmAndConfirm(pmPage, document, fileName)
  }
  const reviewer = await login(reviewerPage, '컴플라이언스 검토자')
  assert.notEqual(pm.userId, reviewer.userId, 'PM and reviewer resolved to the same user')
  const reviewerEvidence = await assertReviewerReadOnly(reviewerPage, confirmedDocuments)
  await Promise.all([...responseTasks])
  assertCleanSignals()
  receipt = {
    ...receipt, outcome: 'PASS', completedAt: new Date().toISOString(),
    syntheticFixtureProvenance: {
      manifestSha256: fixtureEvidence.manifestSha256,
      generator: 'argus-synthetic-korean-ocr-v1', classification: 'SYNTHETIC_ONLY',
      files: fixtureNames.map((fileName) => ({ fileName, bytes: fixtureEvidence.fixtures[fileName].bytes, sha256: fixtureEvidence.fixtures[fileName].sha256, expectedRoutes: expectedRoutes[fileName] })),
    },
    actors: { productManager: { userId: pm.userId, role: pm.role }, reviewer: { userId: reviewer.userId, role: reviewer.role }, isolatedBrowserContexts: true },
    ids: { productId: product.productId, batchId },
    assertions: {
      uploadedThroughPmUi: true, allExtractionsReady: true, pageRoutesMatched: true,
      provenanceAndConfidencePresent: true, unconfirmedAnalysisGateClosed: true,
      pmSavedAndConfirmedAllDocuments: true, reviewerOcrDisplayReadOnly: true,
      provenanceSurvivedReviewerReload: true,
    },
    documents: Object.fromEntries(Object.entries(confirmedDocuments).map(([fileName, document]) => [fileName, { documentId: document.documentId, currentRunId: document.currentRunId, currentTextHash: document.currentTextHash, pages: document.pages }])),
    reviewerEvidence,
    browserSignals,
  }
  console.log(`PASS real Korean OCR browser E2E; receipt: ${receiptPath}`)
} catch (error) {
  failure = error
  await Promise.allSettled([...responseTasks])
  receipt = { ...receipt, outcome: 'FAIL', completedAt: new Date().toISOString(), error: { name: error.name, message: error.message, stack: error.stack }, browserSignals }
} finally {
  await Promise.allSettled([...responseTasks])
  await pmContext?.close()
  await reviewerContext?.close()
  await browser?.close()
  await mkdir(dirname(receiptPath), { recursive: true })
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, 'utf8')
}

if (failure) throw new Error(`${failure.message}\nFailure receipt: ${receiptPath}`, { cause: failure })
