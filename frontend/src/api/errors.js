// Shared API error shape (matches API 명세서 §1.3 error contract).
export class ApiError extends Error {
  constructor({ status, errorCode, message, retryable = false, fieldErrors = [], traceId, existingAnalysisId = null }) {
    super(message || errorCode)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
    this.retryable = retryable
    this.fieldErrors = fieldErrors
    this.existingAnalysisId = existingAnalysisId
    this.traceId = traceId || `trc-${Date.now()}`
    this.timestamp = new Date().toISOString()
  }
}

// Map an error to the UI treatment class (API 명세서 §1.3).
export function errorKind(err) {
  if (!(err instanceof ApiError)) return 'unknown'
  switch (err.status) {
    case 400:
      return 'validation'
    case 401:
      return 'identity'
    case 403:
      return 'forbidden'
    case 404:
      return 'notfound'
    case 409:
      return 'conflict'
    case 413:
      return 'filesize'
    case 503:
      return 'transient'
    default:
      return 'unknown'
  }
}
