import { defineStore } from 'pinia'
import { api } from '@/api'
import { setAuth } from '@/api/auth-context.js'

const STORAGE_KEY = 'guardlab.session'

export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null, // { userId, name, role, active }
  }),
  getters: {
    isAuthed: (s) => !!s.user,
    role: (s) => s.user?.role || null,
    isPM: (s) => s.user?.role === 'PRODUCT_MANAGER',
    isReviewer: (s) => s.user?.role === 'COMPLIANCE_REVIEWER',
    roleLabel: (s) =>
      s.user?.role === 'PRODUCT_MANAGER' ? '상품 담당자' : s.user?.role === 'COMPLIANCE_REVIEWER' ? '컴플라이언스 검토자' : '',
  },
  actions: {
    async login(userId, role) {
      const user = await api.createSession(userId, role)
      this.user = user
      setAuth({ userId: user.userId, role: user.role })
      localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
      return user
    },
    restore() {
      try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (!raw) return
        const user = JSON.parse(raw)
        this.user = user
        setAuth({ userId: user.userId, role: user.role })
      } catch {
        localStorage.removeItem(STORAGE_KEY)
      }
    },
    logout() {
      this.user = null
      setAuth(null)
      localStorage.removeItem(STORAGE_KEY)
    },
  },
})
