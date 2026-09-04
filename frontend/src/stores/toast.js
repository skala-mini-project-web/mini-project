import { defineStore } from 'pinia'
import { ApiError, errorKind } from '@/api'

let counter = 0

export const useToastStore = defineStore('toast', {
  state: () => ({ items: [] }),
  actions: {
    push({ type = 'info', title, message, traceId, timeout = 5000 }) {
      const id = ++counter
      this.items.push({ id, type, title, message, traceId })
      if (timeout) setTimeout(() => this.remove(id), timeout)
      return id
    },
    success(title, message) {
      return this.push({ type: 'success', title, message })
    },
    info(title, message) {
      return this.push({ type: 'info', title, message })
    },
    // Turn any thrown error into a toast using the error contract.
    fromError(err, fallback = '요청을 처리하지 못했습니다.') {
      if (err instanceof ApiError) {
        const kindLabel = {
          validation: '입력 오류',
          identity: '인증 오류',
          forbidden: '권한 오류',
          notfound: '찾을 수 없음',
          conflict: '상태 충돌',
          filesize: '파일 크기 초과',
          transient: '일시적 오류',
          unknown: '오류',
        }
        return this.push({
          type: 'error',
          title: `${kindLabel[errorKind(err)]} · ${err.errorCode}`,
          message: err.message,
          traceId: err.traceId,
          timeout: 7000,
        })
      }
      return this.push({ type: 'error', title: '오류', message: err?.message || fallback, timeout: 7000 })
    },
    remove(id) {
      this.items = this.items.filter((t) => t.id !== id)
    },
  },
})
