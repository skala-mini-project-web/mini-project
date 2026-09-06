#!/usr/bin/env node
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const CORPUS_URL = new URL('../data/synthetic-financial-corpus/', import.meta.url)
const BACKEND_URL = (process.env.ARGUS_BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const HEADERS = { 'X-Demo-User-Id': '1', 'X-Demo-Role': 'PRODUCT_MANAGER' }
const POLL_TIMEOUT_MS = 120_000
const PRODUCT_TYPES = {
  'HORIZON-INCOME-NOTE': 'INVESTMENT',
  'RIVER-FLEX-SAVINGS': 'SAVINGS',
  'BLUE-LINE-CREDIT': 'LOAN',
  'SAFE-HARBOR-COVER': 'INVESTMENT',
  'GREEN-STEP-GOAL': 'SAVINGS',
  'NOVA-CASH-RESERVE': 'INVESTMENT',
}

async function request(url, options = {}) {
  const response = await fetch(url, { ...options, headers: { ...HEADERS, ...options.headers } })
  const body = await response.text()
  assert(response.ok, `${options.method || 'GET'} ${url}: HTTP ${response.status} ${body}`)
  return body ? JSON.parse(body) : null
}

async function createProduct(productCode) {
  return request(`${BACKEND_URL}/api/products`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: `CORPUS-${productCode}-${Date.now()}`,
      productType: PRODUCT_TYPES[productCode],
      description: 'ARGUS synthetic financial corpus extraction regression product',
    }),
  })
}

async function waitForExtraction(documentId) {
  const deadline = Date.now() + POLL_TIMEOUT_MS
  while (Date.now() < deadline) {
    const document = await request(`${BACKEND_URL}/api/documents/${documentId}`)
    if (document.extractStatus === 'READY') return document
    assert.notEqual(document.extractStatus, 'FAILED', `document ${documentId} extraction failed`)
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  throw new Error(`document ${documentId} extraction timed out`)
}

async function main() {
  const manifest = JSON.parse(await readFile(new URL('manifest.v1.json', CORPUS_URL), 'utf8'))
  const expected = JSON.parse(await readFile(new URL('expected-extraction.v1.json', CORPUS_URL), 'utf8'))
  assert.equal(manifest.documentCount, 30)
  assert(manifest.pageCount >= 100)
  const expectedById = new Map(expected.extractionCases.map((testCase) => [testCase.logicalId, testCase]))
  const productIds = new Map()
  const results = []

  for (const document of manifest.documents) {
    let productId = productIds.get(document.productCode)
    if (!productId) {
      const product = await createProduct(document.productCode)
      productId = product.productId
      productIds.set(document.productCode, productId)
    }
    const bytes = await readFile(new URL(document.path, CORPUS_URL))
    const form = new FormData()
    form.set('file', new Blob([bytes], { type: 'application/pdf' }), `${document.logicalId}.pdf`)
    const accepted = await request(`${BACKEND_URL}/api/products/${productId}/documents`, { method: 'POST', body: form })
    const extracted = await waitForExtraction(accepted.documentId)
    assert.equal(extracted.checksum, document.sha256, document.logicalId)
    assert.equal(extracted.extractStatus, 'READY', document.logicalId)
    const requiredTerms = expectedById.get(document.logicalId).requiredTerms
    for (const term of requiredTerms) {
      assert(extracted.extractedText.includes(term), `${document.logicalId} missing extracted term: ${term}`)
    }
    results.push({ logicalId: document.logicalId, documentId: accepted.documentId, characters: extracted.extractedText.length })
  }

  console.log(JSON.stringify({
    outcome: 'PASS',
    mode: 'actual-backend-pdfbox',
    products: productIds.size,
    documents: results.length,
    totalExtractedCharacters: results.reduce((sum, result) => sum + result.characters, 0),
    sourceRevisionsExpected: results.length,
  }, null, 2))
}

await main()
