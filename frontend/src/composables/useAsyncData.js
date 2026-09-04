import { ref } from 'vue'

// Minimal async-data helper: data + loading + error + load().
export function useAsyncData(fetcher) {
  const data = ref(null)
  const loading = ref(false)
  const error = ref(null)
  async function load(...args) {
    loading.value = true
    error.value = null
    try {
      data.value = await fetcher(...args)
      return data.value
    } catch (e) {
      error.value = e
      throw e
    } finally {
      loading.value = false
    }
  }
  return { data, loading, error, load }
}
