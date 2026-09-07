import { getAuth } from './auth-context.js'
import { ApiError } from './errors.js'
import { uuid } from './client.js'

const BASE = '/api'

function headers(extra = {}) {
  const auth = getAuth()
  return {
    ...extra,
    ...(auth ? { 'X-Demo-User-Id': auth.userId, 'X-Demo-Role': auth.role } : {}),
  }
}

async function parse(response) {
  const text = await response.text()
  let data = null
  if (text) {
    try { data = JSON.parse(text) } catch { data = null }
  }
  if (!response.ok) {
    throw new ApiError({
      status: response.status,
      errorCode: data?.errorCode || 'UNKNOWN',
      message: data?.message || response.statusText,
      retryable: data?.retryable ?? false,
      fieldErrors: data?.fieldErrors || [],
      traceId: data?.traceId,
    })
  }
  return data
}

function request(path, options = {}) {
  return fetch(BASE + path, { ...options, headers: headers(options.headers) }).then(parse)
}

function actionPath(path, reason) {
  const query = new URLSearchParams()
  if (reason) query.set('reason', reason)
  const suffix = query.toString()
  return suffix ? `${path}?${suffix}` : path
}

export const documentBatchesApi = {
  create(productId, files) {
    const form = new FormData()
    files.forEach((file) => form.append('files', file))
    return request(`/products/${productId}/document-batches`, {
      method: 'POST',
      headers: { 'Idempotency-Key': uuid() },
      body: form,
    })
  },
  get(batchId) {
    return request(`/document-batches/${batchId}`)
  },
  listItems(batchId, { page = 0, size = 100 } = {}) {
    return request(`/document-batches/${batchId}/items?page=${page}&size=${size}`)
  },
  cancelBatch(batchId, reason) {
    return request(actionPath(`/document-batches/${batchId}/cancel`, reason), { method: 'POST' })
  },
  quarantineBatch(batchId, reason) {
    return request(actionPath(`/document-batches/${batchId}/quarantine`, reason), { method: 'POST' })
  },
  cancelItem(batchId, itemId, reason) {
    return request(actionPath(`/document-batches/${batchId}/items/${itemId}/cancel`, reason), { method: 'POST' })
  },
  quarantineItem(batchId, itemId, reason) {
    return request(actionPath(`/document-batches/${batchId}/items/${itemId}/quarantine`, reason), { method: 'POST' })
  },
  retryItem(batchId, itemId) {
    return request(`/document-batches/${batchId}/items/${itemId}/retry`, { method: 'POST' })
  },
  async downloadErrorReport(batchId) {
    const response = await fetch(`${BASE}/document-batches/${batchId}/error-report.csv`, { headers: headers() })
    if (!response.ok) return parse(response)
    return response.blob()
  },
}
