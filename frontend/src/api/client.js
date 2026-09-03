// Real HTTP client for the Spring backend. Used when VITE_USE_MOCK=false.
// Attaches demo headers, parses the error contract into ApiError.
import { ApiError } from './errors.js'
import { getAuth } from './auth-context.js'

const BASE = '/api'

function authHeaders(extra = {}) {
  const auth = getAuth()
  const h = { ...extra }
  if (auth) {
    h['X-Demo-User-Id'] = auth.userId
    h['X-Demo-Role'] = auth.role
  }
  return h
}

async function parse(res) {
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new ApiError({
      status: res.status,
      errorCode: data?.errorCode || 'UNKNOWN',
      message: data?.message || res.statusText,
      retryable: data?.retryable ?? false,
      fieldErrors: data?.fieldErrors || [],
      traceId: data?.traceId,
      existingAnalysisId: data?.existingAnalysisId,
    })
  }
  return data
}

export const http = {
  get: (path, headers) => fetch(BASE + path, { headers: authHeaders(headers) }).then(parse),
  post: (path, body, headers) =>
    fetch(BASE + path, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json', ...headers }),
      body: body != null ? JSON.stringify(body) : undefined,
    }).then(parse),
  patch: (path, body, headers) =>
    fetch(BASE + path, {
      method: 'PATCH',
      headers: authHeaders({ 'Content-Type': 'application/json', ...headers }),
      body: JSON.stringify(body),
    }).then(parse),
  put: (path, body, headers) =>
    fetch(BASE + path, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json', ...headers }),
      body: JSON.stringify(body),
    }).then(parse),
  upload: (path, file, headers) => {
    const form = new FormData()
    form.append('file', file)
    return fetch(BASE + path, { method: 'POST', headers: authHeaders(headers), body: form }).then(parse)
  },
}

export const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random()))
