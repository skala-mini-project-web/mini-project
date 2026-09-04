const SPRING_BASE_URL = process.env.VITE_API_BASE || 'http://localhost:8080'
const AI_BASE_URL = process.env.AI_SERVICE_URL || 'http://localhost:8000'
const REQUEST_TIMEOUT_MS = 5_000

function endpoint(baseUrl, path, dependency) {
  try {
    return new URL(path, `${baseUrl.replace(/\/$/, '')}/`)
  } catch {
    throw new Error(`${dependency} unavailable: invalid base URL "${baseUrl}"`)
  }
}

async function getHealth(url, dependency) {
  try {
    return await fetch(url, {
      method: 'GET',
      redirect: 'manual',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
  } catch (error) {
    const detail = error.name === 'TimeoutError'
      ? `timed out after ${REQUEST_TIMEOUT_MS}ms`
      : error.message
    throw new Error(`${dependency} unavailable at ${url}: ${detail}`)
  }
}

async function readStatus(response, dependency, url) {
  let body
  try {
    body = await response.json()
  } catch {
    throw new Error(`${dependency} unavailable at ${url}: health response was not JSON`)
  }

  if (body.status !== 'UP') {
    throw new Error(`${dependency} unavailable at ${url}: health status was ${JSON.stringify(body.status)}`)
  }
}

async function checkAiService() {
  const dependency = 'AI service'
  const url = endpoint(AI_BASE_URL, '/internal/v1/health', dependency)
  const response = await getHealth(url, dependency)

  if (!response.ok) {
    throw new Error(`${dependency} unavailable at ${url}: HTTP ${response.status}`)
  }

  await readStatus(response, dependency, url)
  console.log(`AI service ready: ${url}`)
}

async function checkSpringBackend() {
  const dependency = 'Spring backend'
  const url = endpoint(SPRING_BASE_URL, '/actuator/health', dependency)
  const response = await getHealth(url, dependency)

  if (response.status === 404) {
    console.log(`Spring backend reachable: ${SPRING_BASE_URL} (Actuator health endpoint is not exposed)`)
    return
  }

  if (!response.ok) {
    throw new Error(`${dependency} unavailable at ${url}: HTTP ${response.status}`)
  }

  await readStatus(response, dependency, url)
  console.log(`Spring backend ready: ${url}`)
}

const results = await Promise.allSettled([
  checkAiService(),
  checkSpringBackend(),
])
const failures = results
  .filter((result) => result.status === 'rejected')
  .map((result) => result.reason instanceof Error ? result.reason.message : String(result.reason))

if (failures.length > 0) {
  for (const failure of failures) console.error(`Preflight failed: ${failure}`)
  process.exitCode = 1
} else {
  console.log('Real E2E dependencies are ready.')
}
