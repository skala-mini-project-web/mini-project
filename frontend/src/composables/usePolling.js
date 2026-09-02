import { ref, onUnmounted } from 'vue'

// Polls fn() every intervalMs. Stops when shouldStop(result) is true or after
// maxMs (API 명세서 §8: 1s interval, stop UI polling after 30s).
export function usePolling(fn, { intervalMs = 1000, maxMs = 30000, onResult, onStop } = {}) {
  const polling = ref(false)
  const timedOut = ref(false)
  let timer = null
  let startedAt = 0

  async function tick(shouldStop) {
    try {
      const result = await fn()
      onResult?.(result)
      if (shouldStop(result)) return stop(false)
      if (Date.now() - startedAt >= maxMs) return stop(true)
      timer = setTimeout(() => tick(shouldStop), intervalMs)
    } catch (e) {
      onResult?.(null, e)
      stop(false)
    }
  }

  function start(shouldStop) {
    stop(false)
    polling.value = true
    timedOut.value = false
    startedAt = Date.now()
    tick(shouldStop)
  }

  function stop(didTimeout) {
    if (timer) clearTimeout(timer)
    timer = null
    polling.value = false
    if (didTimeout) timedOut.value = true
    onStop?.(didTimeout)
  }

  onUnmounted(() => stop(false))
  return { polling, timedOut, start, stop }
}
