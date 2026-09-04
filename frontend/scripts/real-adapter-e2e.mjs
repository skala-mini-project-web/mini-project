#!/usr/bin/env node

import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

process.env.VITE_USE_MOCK = 'false'

const { createServer } = await import('vite')
const frontendRoot = fileURLToPath(new URL('..', import.meta.url))

async function loadChromium() {
  try {
    return (await import('playwright')).chromium
  } catch (playwrightError) {
    try {
      return (await import('playwright-core')).chromium
    } catch {
      throw new Error(
        'Playwright is required. Install playwright or playwright-core before running this regression.',
        { cause: playwrightError },
      )
    }
  }
}

const users = [
  { userId: 'USER-PM-E2E', name: 'Adapter Product Manager', role: 'PRODUCT_MANAGER' },
  { userId: 'USER-CR-E2E', name: 'Adapter Reviewer', role: 'COMPLIANCE_REVIEWER' },
]

const review = {
  reviewId: 'REV-ADAPTER-44',
  analysisId: 'ANA-ADAPTER-44',
  productName: 'Adapter Identity Product',
  ownerName: 'Adapter Product Manager',
  maxSeverity: 'HIGH',
  status: 'PENDING',
  submissionComment: 'Backend reviewId fixture',
}

const result = {
  analysisId: review.analysisId,
  riskScore: 77,
  findings: [
    {
      findingId: 'FND-ADAPTER-44',
      severity: 'HIGH',
      findingType: 'MISLEADING_EXPRESSION',
      ruleCode: 'LOSS_SOFTENING',
      statement: 'Backend review identity was accepted.',
      affectedPersonaCodes: [],
      evidenceReferences: [],
      recommendation: 'Keep the reviewId adapter contract intact.',
    },
  ],
  vulnerabilityPatterns: [],
  guardFitSuggestions: [],
  groundTruthFacts: [],
}

const auditPages = new Map([
  [0, {
    items: [{
      auditId: 'AUD-FIRST',
      createdAt: '2026-09-04T00:00:00Z',
      action: 'FIRST_PAGE_RENDERED',
      resourceType: 'REVIEW',
      resourceId: review.reviewId,
      resourceLabel: 'Server item from offset zero',
      actorId: users[1].userId,
      traceId: 'trace-first-page',
    }],
    offset: 0,
    limit: 15,
    totalElements: 16,
  }],
  [15, {
    items: [{
      auditId: 'AUD-SECOND',
      createdAt: '2026-09-04T00:01:00Z',
      action: 'SECOND_PAGE_RENDERED',
      resourceType: 'REVIEW',
      resourceId: review.reviewId,
      resourceLabel: 'Server item from offset fifteen',
      actorId: users[1].userId,
      traceId: 'trace-second-page',
    }],
    offset: 15,
    limit: 15,
    totalElements: 16,
  }],
])

const server = await createServer({
  root: frontendRoot,
  server: { host: '127.0.0.1', port: 0, strictPort: true },
  clearScreen: false,
})

let browser
try {
  await server.listen()
  const address = server.httpServer?.address()
  assert(address && typeof address !== 'string', 'Vite did not expose a TCP listening address')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const chromium = await loadChromium()
  browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH }
      : {}),
  })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  const pageErrors = []
  const consoleErrors = []
  const failedRequests = []
  const apiRequests = []
  const unexpectedRequests = []
  const auditRequests = []
  let loginRequest
  let authenticatedRequest
  let failNextAuditRequest = false

  page.on('pageerror', (error) => pageErrors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      consoleErrors.push(`${message.type()}: ${message.text()}`)
    }
  })
  page.on('requestfailed', (request) => {
    failedRequests.push(`${request.method()} ${request.url()} (${request.failure()?.errorText ?? 'failed'})`)
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    const key = `${request.method()} ${url.pathname}`
    apiRequests.push(`${key}${url.search}`)
    const json = (status, body) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(body),
    })

    if (key === 'GET /api/demo/users') return json(200, users)
    if (key === 'POST /api/demo/session') {
      loginRequest = request.postDataJSON()
      return json(200, users[1])
    }
    if (key === 'GET /api/dashboard/compliance') {
      authenticatedRequest = request
      return json(200, {
        summary: { pendingReviews: 1, highFindings: 1, activeRiskPatterns: 0, decidedToday: 0 },
        priorityReviews: [],
      })
    }
    if (key === 'GET /api/reviews' && url.searchParams.get('status') === 'PENDING') {
      return json(200, { items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
    }
    if (key === `GET /api/reviews/${review.reviewId}`) return json(200, review)
    if (key === `GET /api/analyses/${review.analysisId}/result`) return json(200, result)
    if (key === 'GET /api/audit-logs') {
      auditRequests.push(url.search)
      if (failNextAuditRequest) {
        failNextAuditRequest = false
        return json(503, {
          errorCode: 'AUDIT_BACKEND_UNAVAILABLE',
          message: 'Deterministic audit failure',
          retryable: true,
          traceId: 'trace-audit-failure',
        })
      }
      const offset = Number(url.searchParams.get('offset'))
      const fixture = auditPages.get(offset)
      if (url.searchParams.get('limit') === '15' && fixture) return json(200, fixture)
    }

    unexpectedRequests.push(`${key}${url.search}`)
    return json(500, { errorCode: 'UNEXPECTED_E2E_REQUEST', message: `${key}${url.search}` })
  })

  const entryResponse = await page.goto(baseUrl, { waitUntil: 'domcontentloaded' })

  const pmRole = page.locator('button.role', { hasText: '상품 담당자' })
  const reviewerRole = page.locator('button.role', { hasText: '컴플라이언스 검토자' })
  try {
    await pmRole.waitFor({ state: 'visible', timeout: 5_000 })
  } catch (error) {
    const body = await page.locator('body').innerText({ timeout: 1_000 }).catch(() => '')
    const summarize = (values) => values.length ? values.slice(-4).join(' | ') : 'none'
    throw new Error([
      'Role Select did not render within 5s.',
      `page: ${page.url()} (HTTP ${entryResponse?.status() ?? 'no response'}), body: ${body.replace(/\s+/g, ' ').trim().slice(0, 240) || '<empty>'}`,
      `console: ${summarize(consoleErrors)}`,
      `page errors: ${summarize(pageErrors)}`,
      `API requests: ${summarize(apiRequests)}`,
      `failed requests: ${summarize(failedRequests)}`,
    ].join('\n'), { cause: error })
  }
  assert.equal(await pmRole.isEnabled(), true, 'backend PRODUCT_MANAGER object did not enable Role Select')
  assert.equal(await reviewerRole.isEnabled(), true, 'backend COMPLIANCE_REVIEWER object did not enable Role Select')
  await assert.doesNotReject(() => page.getByText('USER-PM-E2E · Adapter Product Manager', { exact: true }).waitFor())
  await assert.doesNotReject(() => page.getByText('USER-CR-E2E · Adapter Reviewer', { exact: true }).waitFor())

  await reviewerRole.click()
  await page.waitForURL('**/dashboard')
  await assert.doesNotReject(() => page.getByRole('heading', { name: 'Adapter Reviewer님, 오늘의 작업' }).waitFor())
  await assert.doesNotReject(() => page.locator('.who-name', { hasText: 'Adapter Reviewer' }).waitFor())
  assert.deepEqual(loginRequest, {
    userId: users[1].userId,
    role: users[1].role,
  }, 'Role Select submitted the wrong backend identity')
  assert.equal(authenticatedRequest?.headers()['x-demo-user-id'], users[1].userId)
  assert.equal(authenticatedRequest?.headers()['x-demo-role'], users[1].role)

  await page.goto(`${baseUrl}/reviews/${review.reviewId}`, { waitUntil: 'networkidle' })
  await assert.doesNotReject(() => page.getByText(review.reviewId, { exact: true }).waitFor())
  await assert.doesNotReject(() => page.getByRole('heading', { name: review.productName }).waitFor())
  await assert.doesNotReject(() => page.getByText('Backend review identity was accepted.', { exact: true }).waitFor())
  assert.equal(await page.getByText('검토 내용을 불러오지 못했습니다', { exact: true }).count(), 0)

  await page.goto(`${baseUrl}/audit`, { waitUntil: 'networkidle' })
  await assert.doesNotReject(() => page.getByText('Server item from offset zero', { exact: true }).waitFor())
  assert.equal(await page.getByText('Server item from offset fifteen', { exact: true }).count(), 0)

  await page.getByRole('button', { name: '다음 페이지' }).click()
  await assert.doesNotReject(() => page.getByText('Server item from offset fifteen', { exact: true }).waitFor())
  assert.equal(await page.getByText('Server item from offset zero', { exact: true }).count(), 0)
  assert.deepEqual(
    auditRequests.slice(0, 2),
    ['?offset=0&limit=15', '?offset=15&limit=15'],
    'audit pagination did not use the backend offset/limit contract',
  )

  failNextAuditRequest = true
  await page.getByRole('button', { name: '이전 페이지' }).click()
  const errorAlert = page.getByRole('alert')
  await assert.doesNotReject(() => errorAlert.waitFor())
  await assert.doesNotReject(() => errorAlert.getByText('감사 로그를 불러오지 못했습니다', { exact: true }).waitFor())
  assert.equal(await page.getByText('감사 로그가 없습니다', { exact: true }).count(), 0, 'request failure was rendered as an empty audit list')
  assert.equal(unexpectedRequests.length, 0, `unexpected API requests: ${unexpectedRequests.join(', ')}`)
  assert.equal(pageErrors.length, 0, `browser page errors: ${pageErrors.join('; ')}`)

  console.log('PASS real adapter browser regression')
} finally {
  await browser?.close()
  await server.close()
}
