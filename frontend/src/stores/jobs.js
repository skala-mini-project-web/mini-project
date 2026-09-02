import { defineStore } from 'pinia'
import { api } from '@/api'
import { useToastStore } from './toast'
import { useSessionStore } from './session'

// Background job tracker + unread notifications.
// - Polls tracked async jobs (document extraction / analysis / review decision)
//   every 2.2s, even across route changes, and fires a toast on completion.
// - Also records a persistent unread notification anchored to a product, which
//   drives the sidebar count badge and the per-row "!" mark. Opening the
//   product marks its notifications read (count goes down).
const LS = 'guardlab.jobs.v1'
let nid = 0

export const useJobsStore = defineStore('jobs', {
  state: () => ({ tracked: [], notifications: [], timer: null }),
  getters: {
    unread: (s) => s.notifications.filter((n) => !n.read).length,
    unreadForProduct: (s) => (pid) => s.notifications.filter((n) => !n.read && n.productId === pid).length,
  },
  actions: {
    restore() {
      try {
        const raw = localStorage.getItem(LS)
        if (raw) {
          const d = JSON.parse(raw)
          this.tracked = d.tracked || []
          this.notifications = d.notifications || []
        }
      } catch {
        this.tracked = []
        this.notifications = []
      }
      if (this.tracked.length) this.start()
    },
    save() {
      try { localStorage.setItem(LS, JSON.stringify({ tracked: this.tracked, notifications: this.notifications })) } catch {}
    },
    track(job) {
      if (this.tracked.some((j) => j.kind === job.kind && j.id === job.id)) return
      this.tracked.push({ ...job })
      this.save()
      this.start()
    },
    untrack(kind, id) {
      this.tracked = this.tracked.filter((j) => !(j.kind === kind && j.id === id))
      this.save()
      if (!this.tracked.length) this.stop()
    },
    addNote(kind, productId, message) {
      this.notifications.push({ id: `${++nid}-${Date.now()}`, kind, productId: productId || null, message, read: false, at: Date.now() })
      this.save()
    },
    markAllRead() {
      let changed = false
      this.notifications.forEach((n) => { if (!n.read) { n.read = true; changed = true } })
      if (changed) this.save()
    },
    markProductRead(productId) {
      let changed = false
      this.notifications.forEach((n) => {
        if (!n.read && n.productId === productId) { n.read = true; changed = true }
      })
      if (changed) this.save()
    },
    start() {
      if (this.timer) return
      this.timer = setInterval(() => this.tick(), 2200)
    },
    stop() {
      if (this.timer) clearInterval(this.timer)
      this.timer = null
    },
    async tick() {
      const session = useSessionStore()
      if (!session.isAuthed || !this.tracked.length) return
      const toast = useToastStore()
      for (const job of [...this.tracked]) {
        try {
          if (job.kind === 'document') {
            const d = await api.getDocument(job.id)
            if (d.extractStatus === 'READY') {
              toast.success('추출 완료', `${job.name} 텍스트 추출이 끝났습니다`)
              this.addNote('document', job.productId || d.productId, `추출 완료 · ${job.name}`)
              this.untrack('document', job.id)
            } else if (d.extractStatus === 'FAILED') {
              toast.push({ type: 'error', title: '추출 실패', message: `${job.name} · ${d.error?.errorCode || ''}` })
              this.addNote('document', job.productId || d.productId, `추출 실패 · ${job.name}`)
              this.untrack('document', job.id)
            }
          } else if (job.kind === 'analysis') {
            const a = await api.getAnalysis(job.id)
            if (a.status === 'COMPLETED') {
              toast.success('분석 완료', `${job.name} 리스크 분석이 끝났습니다`)
              this.addNote('analysis', job.productId, `분석 완료 · ${job.name}`)
              this.untrack('analysis', job.id)
            } else if (a.status === 'FAILED') {
              toast.push({ type: 'error', title: '분석 실패', message: `${job.name} · ${a.error?.errorCode || ''}` })
              this.addNote('analysis', job.productId, `분석 실패 · ${job.name}`)
              this.untrack('analysis', job.id)
            }
          } else if (job.kind === 'review') {
            const r = await api.getReview(job.id)
            if (r && r.status !== 'PENDING' && r.submittedBy === session.user?.userId) {
              const ok = r.decision === 'APPROVED'
              toast.push({ type: ok ? 'success' : 'error', title: '검토 완료', message: `${job.name} · ${ok ? '승인되었습니다' : '반려되었습니다'}` })
              this.addNote('review', r.productId, `검토 ${ok ? '승인' : '반려'} · ${job.name}`)
              this.untrack('review', job.id)
            }
          }
        } catch (e) {
          if (e?.status === 404) this.untrack(job.kind, job.id)
        }
      }
    },
  },
})
