#!/usr/bin/env node

import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_URL = (process.env.CRITICAL_VISUAL_FRONTEND_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const BACKEND_URL = (process.env.CRITICAL_VISUAL_BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const FIXTURE_DIR = fileURLToPath(new URL('../../data/synthetic-ocr-fixtures/', import.meta.url))
const REPORT_DIR = fileURLToPath(new URL('../../docs/reports/runtime/', import.meta.url))
const runKey = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}`
const receiptPath = process.env.CRITICAL_VISUAL_RECEIPT || `${REPORT_DIR}/critical-visual-e2e-${runKey}.json`
const screenshotDir = process.env.CRITICAL_VISUAL_SCREENSHOT_DIR || `${REPORT_DIR}/critical-visual-e2e`
const UI_TIMEOUT_MS = 20_000
const WORKFLOW_TIMEOUT_MS = 240_000
const viewports = [
  { name: 'desktop', width: 1440, height: 1000 },
  { name: 'mobile-390', width: 390, height: 844 },
]
const escaped = (value) => String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const apiPath = (url) => {
  const path = new URL(url).pathname
  const offset = path.indexOf('/api/')
  return offset < 0 ? null : path.slice(offset)
}

async function loadChromium() {
  try { return (await import('playwright')).chromium }
  catch (playwrightError) {
    try { return (await import('playwright-core')).chromium }
    catch { throw new Error('Playwright or playwright-core is required for critical visual E2E.', { cause: playwrightError }) }
  }
}

async function fetchOk(url, label) {
  const response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(8_000) })
  assert(response.ok, `${label} unavailable at ${url}: HTTP ${response.status}`)
  return response
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
  const session = await waitForApi(page, 'POST', /^\/api\/demo\/session$/, () => role.click())
  await page.waitForURL('**/dashboard', { timeout: UI_TIMEOUT_MS })
  return session
}

async function assertNoHorizontalOverflow(page, state) {
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
    overflowing: [...document.querySelectorAll('*')]
      .map((element) => {
        const box = element.getBoundingClientRect()
        return { tag: element.tagName, className: element.className, right: Math.ceil(box.right), left: Math.floor(box.left) }
      })
      .filter((element) => element.right > document.documentElement.clientWidth + 1 || element.left < -1)
      .slice(0, 10),
  }))
  assert(dimensions.documentWidth <= dimensions.viewport + 1, `${state}: document horizontally overflows (${dimensions.documentWidth} > ${dimensions.viewport}): ${JSON.stringify(dimensions.overflowing)}`)
  assert(dimensions.bodyWidth <= dimensions.viewport + 1, `${state}: body horizontally overflows (${dimensions.bodyWidth} > ${dimensions.viewport}): ${JSON.stringify(dimensions.overflowing)}`)
}

async function assertFullyVisible(page, locator, label) {
  await locator.scrollIntoViewIfNeeded()
  await locator.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  const box = await locator.boundingBox()
  const viewport = page.viewportSize()
  assert(box && viewport, `${label}: missing layout box`)
  assert(box.x >= -1 && box.y >= -1 && box.x + box.width <= viewport.width + 1 && box.y + box.height <= viewport.height + 1,
    `${label}: clipped at ${JSON.stringify(box)} in ${JSON.stringify(viewport)}`)
}

async function assertDialogAndFocus(page, dialog, focusTarget, label) {
  await assertFullyVisible(page, dialog, `${label} dialog`)
  await focusTarget.focus()
  await assertFullyVisible(page, focusTarget, `${label} focus target`)
  assert(await focusTarget.evaluate((node) => node === document.activeElement), `${label}: expected control did not retain focus`)
  assert(await focusTarget.evaluate((node) => node.closest('[role="dialog"]') != null), `${label}: focused control escaped dialog`)
}

async function capture(page, state, assertions) {
  const evidence = []
  for (const viewport of viewports) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.addStyleTag({ content: '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}' })
    await assertions(viewport)
    await assertNoHorizontalOverflow(page, `${state}/${viewport.name}`)
    const path = `${screenshotDir}/${state}-${viewport.name}.png`
    await mkdir(dirname(path), { recursive: true })
    await page.screenshot({ path, fullPage: true, animations: 'disabled' })
    evidence.push({ state, viewport: { width: viewport.width, height: viewport.height }, path })
  }
  return evidence
}

async function createProduct(page) {
  await page.goto(`${FRONTEND_URL}/products`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '상품 등록' }).first().click()
  const dialog = page.getByRole('dialog', { name: '상품 등록' })
  const input = dialog.getByLabel('상품명')
  const screenshots = await capture(page, 'product-create-dialog', async () => {
    await assertDialogAndFocus(page, dialog, input, 'product-create')
    await assertFullyVisible(page, dialog.getByRole('button', { name: '등록', exact: true }), 'product-create primary action')
  })
  const name = `시각 QA 합성 상품 ${runKey}`
  await input.fill(name)
  await dialog.getByLabel('설명').fill('실제 API와 합성 PDF만 사용하는 반응형 시각 검증')
  const product = await waitForApi(page, 'POST', /^\/api\/products$/, () => dialog.getByRole('button', { name: '등록', exact: true }).click())
  await page.waitForURL(`**/products/${product.productId}`, { timeout: UI_TIMEOUT_MS })
  return { ...product, name, screenshots }
}

async function uploadSyntheticBatch(page, productId) {
  const lowName = '신뢰도가-낮은-한글-OCR-확인용.pdf'
  const corruptName = '처리할-수-없는-손상된-합성문서-격리-오류-확인용.pdf'
  const bornName = '검토-반려-흐름용-합성-디지털문서.pdf'
  const files = [
    { name: lowName, mimeType: 'application/pdf', buffer: await readFile(`${FIXTURE_DIR}/low-confidence-korean-scan.pdf`) },
    { name: corruptName, mimeType: 'application/pdf', buffer: await readFile(`${FIXTURE_DIR}/corrupt.pdf`) },
    { name: bornName, mimeType: 'application/pdf', buffer: await readFile(`${FIXTURE_DIR}/born-digital-ko-en.pdf`) },
  ]
  await page.getByRole('button', { name: '문서 일괄 업로드' }).click()
  const dialog = page.getByRole('dialog', { name: '문서 일괄 업로드' })
  const chooserPending = page.waitForEvent('filechooser', { timeout: UI_TIMEOUT_MS })
  await dialog.getByRole('button', { name: /PDF\/PPTX 파일 선택/ }).click()
  await (await chooserPending).setFiles(files)
  const batch = await waitForApi(page, 'POST', new RegExp(`^/api/products/${escaped(productId)}/document-batches$`), () => dialog.getByRole('button', { name: '일괄 처리 시작' }).click())
  await page.waitForURL(`**/document-batches/${batch.batchId}`, { timeout: UI_TIMEOUT_MS })
  return { batchId: batch.batchId, lowName, corruptName, bornName }
}

async function refreshBatch(page, batchId) {
  const detail = `/api/document-batches/${batchId}`
  const detailPending = page.waitForResponse((r) => r.request().method() === 'GET' && apiPath(r.url()) === detail, { timeout: UI_TIMEOUT_MS })
  const itemsPending = page.waitForResponse((r) => r.request().method() === 'GET' && apiPath(r.url()) === `${detail}/items`, { timeout: UI_TIMEOUT_MS })
  await page.getByRole('button', { name: '새로고침' }).click()
  const [batchResponse, itemsResponse] = await Promise.all([detailPending, itemsPending])
  assert(batchResponse.ok() && itemsResponse.ok(), 'Batch refresh failed')
  return { batch: await batchResponse.json(), items: (await itemsResponse.json()).items || [] }
}

async function waitForTerminalBatch(page, batchId) {
  const deadline = Date.now() + WORKFLOW_TIMEOUT_MS
  let snapshot
  while (Date.now() < deadline) {
    snapshot = await refreshBatch(page, batchId)
    if (['SUCCEEDED', 'CANCELLED', 'QUARANTINED'].includes(snapshot.batch.status)) return snapshot
    await page.waitForTimeout(1_000)
  }
  throw new Error(`Batch ${batchId} did not reach a terminal state: ${JSON.stringify(snapshot)}`)
}

async function captureBatchTerminal(page, snapshot, names) {
  assert.equal(snapshot.batch.status, 'QUARANTINED')
  const corrupt = snapshot.items.find((item) => item.fileName === names.corruptName)
  assert.equal(corrupt?.status, 'QUARANTINED')
  assert.match(corrupt?.errorMessage || '', /[가-힣]/, 'Actual batch error message did not contain Korean text')
  const row = page.locator('tbody tr').filter({ hasText: names.corruptName })
  return capture(page, 'batch-terminal-error', async (viewport) => {
    await page.getByText('처리가 종료되었습니다.', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
    await row.scrollIntoViewIfNeeded()
    await row.getByText('격리', { exact: true }).waitFor()
    await assertFullyVisible(page, row.getByRole('button', { name: '재시도', exact: true }), 'batch retry primary action')
    const error = row.locator('.error-message')
    await error.waitFor({ state: 'visible' })
    const wrapping = await error.evaluate((node) => {
      const style = getComputedStyle(node)
      const lineHeight = Number.parseFloat(style.lineHeight) || Number.parseFloat(style.fontSize) * 1.2
      return { scrollWidth: node.scrollWidth, clientWidth: node.clientWidth, height: node.getBoundingClientRect().height, lineHeight, whiteSpace: style.whiteSpace }
    })
    assert(wrapping.scrollWidth <= wrapping.clientWidth + 1 && wrapping.whiteSpace !== 'nowrap', 'Batch Korean error text is horizontally clipped')
    if (viewport.width === 390) assert(wrapping.height > wrapping.lineHeight * 1.5, 'Batch Korean error text did not wrap to multiple lines at 390px')
  })
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

async function captureAndConfirmLowConfidence(page, document) {
  assert(document.pages?.some((item) => item.selectedMethod === 'OCR_KOR_ENG' && item.ocrConfidenceBand === 'LOW'), 'Synthetic low-confidence document did not produce LOW OCR provenance')
  const confirm = page.getByRole('button', { name: '현재 실행·텍스트 확정' })
  const screenshots = await capture(page, 'ocr-low-confidence-confirmation', async () => {
    await page.getByText('확인되지 않음', { exact: true }).waitFor()
    await page.getByText(/신뢰도/).first().waitFor()
    await page.getByText(/· 낮음$/).first().waitFor()
    await assertFullyVisible(page, confirm, 'OCR confirmation primary action')
    assert(await page.getByRole('button', { name: '분석으로 이동' }).isDisabled(), 'Unconfirmed low-confidence OCR can be analyzed')
  })
  const confirmed = await waitForApi(page, 'PATCH', new RegExp(`^/api/documents/${escaped(document.documentId)}/text$`), () => confirm.click())
  assert.equal(confirmed.confirmed, true)
  return screenshots
}

async function confirmDocument(page, document) {
  const button = page.getByRole('button', { name: '현재 실행·텍스트 확정' })
  const confirmed = await waitForApi(page, 'PATCH', new RegExp(`^/api/documents/${escaped(document.documentId)}/text$`), () => button.click())
  assert.equal(confirmed.confirmed, true)
}

async function createAnalysisAndReview(page, productId, documentId) {
  await page.goto(`${FRONTEND_URL}/products/${productId}/analyze`, { waitUntil: 'domcontentloaded' })
  const documentOption = page.locator('label.opt').filter({ has: page.locator(`input[type="radio"][value="${documentId}"]`) })
  await documentOption.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await documentOption.click()
  const fact = page.locator('.fact').filter({ has: page.getByRole('button', { name: '확인', exact: true }) }).first()
  await fact.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  await waitForApi(page, 'PUT', /^\/api\/ground-truth-facts\/[^/]+\/verification$/, () => fact.getByRole('button', { name: '확인', exact: true }).click())
  const analysis = await waitForApi(page, 'POST', /^\/api\/analyses$/, () => page.getByRole('button', { name: 'Persona + Red Team 분석 시작' }).click())
  await page.waitForURL(`**/analyses/${analysis.analysisId}`, { timeout: UI_TIMEOUT_MS })
  const completed = page.getByRole('heading', { name: 'RAG 검색 근거' })
  const deadline = Date.now() + WORKFLOW_TIMEOUT_MS
  while (!(await completed.isVisible().catch(() => false)) && Date.now() < deadline) {
    const retry = page.getByRole('button', { name: '상태 다시 확인' })
    if (await retry.isVisible().catch(() => false)) await retry.click()
    else await completed.waitFor({ state: 'visible', timeout: 3_000 }).catch(() => {})
  }
  assert(await completed.isVisible(), 'Analysis did not complete')
  await page.getByRole('button', { name: '검토 요청', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '검토 요청' })
  const comment = dialog.getByLabel('제출 의견')
  const screenshots = await capture(page, 'review-request-dialog', async () => {
    await assertDialogAndFocus(page, dialog, comment, 'review-request')
    await assertFullyVisible(page, dialog.getByRole('button', { name: '검토 요청', exact: true }), 'review-request primary action')
  })
  await comment.fill(`합성 데이터 시각 QA 검토 요청 ${runKey}`)
  const review = await waitForApi(page, 'POST', /^\/api\/reviews$/, () => dialog.getByRole('button', { name: '검토 요청', exact: true }).click())
  return { analysisId: analysis.analysisId, reviewId: review.reviewId, screenshots }
}

async function rejectAndCapture(page, reviewId) {
  await page.goto(`${FRONTEND_URL}/reviews/${reviewId}`, { waitUntil: 'domcontentloaded' })
  const comment = page.getByLabel('의견')
  await comment.waitFor({ state: 'visible', timeout: UI_TIMEOUT_MS })
  const reason = `합성 문서의 저신뢰 OCR 원문을 재확인하고 긴 한글 수정 지침을 모두 반영한 뒤 다시 제출해 주세요. ${runKey}`
  await comment.fill(reason)
  const rejectionReady = await capture(page, 'review-rejection-decision', async () => {
    await assertFullyVisible(page, comment, 'review rejection comment')
    await comment.focus()
    assert(await comment.evaluate((node) => node === document.activeElement), 'Review rejection textarea lost focus')
    await assertFullyVisible(page, page.getByRole('button', { name: '반려', exact: true }), 'review rejection primary action')
  })
  const decided = await waitForApi(page, 'POST', new RegExp(`^/api/reviews/${escaped(reviewId)}/decision$`), () => page.getByRole('button', { name: '반려', exact: true }).click())
  assert.equal(decided.status, 'REJECTED')
  await page.goto(`${FRONTEND_URL}/reviews/${reviewId}`, { waitUntil: 'domcontentloaded' })
  const rejected = await capture(page, 'review-rejected', async () => {
    await page.getByText('반려됨', { exact: true }).waitFor({ timeout: UI_TIMEOUT_MS })
    const renderedReason = page.getByText(reason, { exact: true })
    await assertFullyVisible(page, renderedReason, 'persisted rejection reason')
    const wrap = await renderedReason.evaluate((node) => ({ scrollWidth: node.scrollWidth, clientWidth: node.clientWidth, whiteSpace: getComputedStyle(node).whiteSpace }))
    assert(wrap.scrollWidth <= wrap.clientWidth + 1 && wrap.whiteSpace !== 'nowrap', 'Persisted Korean rejection reason is clipped')
  })
  return [...rejectionReady, ...rejected]
}

let browser
let pmContext
let reviewerContext
let failure
let receipt = { outcome: 'FAIL', runKey, startedAt: new Date().toISOString(), mode: 'real-services-synthetic-data-browser', receiptPath, screenshotDir }
try {
  const manifest = JSON.parse(await readFile(`${FIXTURE_DIR}/manifest.json`, 'utf8'))
  assert.equal(manifest.provenance?.classification, 'SYNTHETIC_ONLY')
  assert.equal(manifest.provenance?.contains_real_customer_data, false)
  assert.equal(manifest.provenance?.contains_real_company_or_product_data, false)
  for (const fixtureName of ['low-confidence-korean-scan.pdf', 'corrupt.pdf', 'born-digital-ko-en.pdf']) {
    assert.equal(manifest.fixtures?.find((fixture) => fixture.file === fixtureName)?.synthetic, true, `${fixtureName} is not declared synthetic`)
  }
  await Promise.all([fetchOk(FRONTEND_URL, 'Frontend'), fetchOk(`${BACKEND_URL}/actuator/health`, 'Backend')])
  const chromium = await loadChromium()
  browser = await chromium.launch({ headless: process.env.CRITICAL_VISUAL_HEADLESS !== 'false', ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}) })
  pmContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  reviewerContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const pmPage = await pmContext.newPage()
  const reviewerPage = await reviewerContext.newPage()
  const browserSignals = { pageErrors: [], requestFailures: [] }
  for (const [page, actor] of [[pmPage, 'PM'], [reviewerPage, 'reviewer']]) {
    page.on('pageerror', (error) => browserSignals.pageErrors.push(`${actor}: ${error.message}`))
    page.on('requestfailed', (request) => browserSignals.requestFailures.push(`${actor}: ${request.method()} ${request.url()} ${request.failure()?.errorText || ''}`))
  }
  const pm = await login(pmPage, '상품 담당자')
  const reviewer = await login(reviewerPage, '컴플라이언스 검토자')
  assert.notEqual(pm.userId, reviewer.userId)
  const product = await createProduct(pmPage)
  const names = await uploadSyntheticBatch(pmPage, product.productId)
  const terminal = await waitForTerminalBatch(pmPage, names.batchId)
  const screenshots = [...product.screenshots, ...await captureBatchTerminal(pmPage, terminal, names)]
  const lowItem = terminal.items.find((item) => item.fileName === names.lowName)
  const bornItem = terminal.items.find((item) => item.fileName === names.bornName)
  assert.equal(lowItem?.status, 'SUCCEEDED')
  assert.equal(bornItem?.status, 'SUCCEEDED')
  const lowDocument = await openDocument(pmPage, lowItem.documentId)
  screenshots.push(...await captureAndConfirmLowConfidence(pmPage, lowDocument))
  const bornDocument = await openDocument(pmPage, bornItem.documentId)
  await confirmDocument(pmPage, bornDocument)
  const review = await createAnalysisAndReview(pmPage, product.productId, bornItem.documentId)
  screenshots.push(...review.screenshots)
  screenshots.push(...await rejectAndCapture(reviewerPage, review.reviewId))
  assert.deepEqual(browserSignals.pageErrors, [], `Page errors: ${browserSignals.pageErrors.join('\n')}`)
  assert.deepEqual(browserSignals.requestFailures, [], `Request failures: ${browserSignals.requestFailures.join('\n')}`)
  receipt = {
    ...receipt, outcome: 'PASS', completedAt: new Date().toISOString(),
    syntheticProvenance: { generator: manifest.generator, classification: manifest.provenance.classification, fixtures: ['low-confidence-korean-scan.pdf', 'corrupt.pdf', 'born-digital-ko-en.pdf'] },
    actors: { productManager: { userId: pm.userId, role: pm.role }, reviewer: { userId: reviewer.userId, role: reviewer.role }, isolatedBrowserContexts: true },
    ids: { productId: product.productId, batchId: names.batchId, lowConfidenceDocumentId: lowItem.documentId, analysisDocumentId: bornItem.documentId, analysisId: review.analysisId, reviewId: review.reviewId },
    assertions: { desktopAnd390Px: true, noHorizontalOverflow: true, primaryActionsVisible: true, dialogsAndFocusNotClipped: true, koreanBatchErrorWraps: true, lowConfidenceConfirmationGateVisible: true, reviewRejectionPersistedAndWraps: true },
    screenshots, browserSignals,
  }
  console.log(`PASS critical visual browser E2E; receipt: ${receiptPath}`)
} catch (error) {
  failure = error
  receipt = { ...receipt, outcome: 'FAIL', completedAt: new Date().toISOString(), error: { name: error.name, message: error.message, stack: error.stack } }
} finally {
  await pmContext?.close()
  await reviewerContext?.close()
  await browser?.close()
  await mkdir(dirname(receiptPath), { recursive: true })
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, 'utf8')
}

if (failure) throw new Error(`${failure.message}\nFailure receipt: ${receiptPath}`, { cause: failure })
