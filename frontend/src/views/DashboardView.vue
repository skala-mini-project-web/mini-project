<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowUpRight, PhPlus, PhFolders, PhClipboardText, PhBell } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useJobsStore } from '@/stores/jobs'
import { useToastStore } from '@/stores/toast'
import { PRODUCT_TYPE_LABEL, formatDateTime, productStatusKey } from '@/lib/format'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GSeverityBadge from '@/components/ui/GSeverityBadge.vue'
import GButton from '@/components/ui/GButton.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'

const router = useRouter()
const session = useSessionStore()
const jobs = useJobsStore()
const toast = useToastStore()
const loading = ref(true)
const data = ref(null)

const pad = (n) => String(n ?? 0).padStart(2, '0')
// 안 읽은 알림만 표시 — “모두 읽음”/상품 진입 시 사라짐(영구 이력은 감사 로그)
const notes = computed(() => jobs.notifications.filter((n) => !n.read).sort((a, b) => b.at - a.at).slice(0, 5))
function openNote(n) { if (n.productId) { jobs.markProductRead(n.productId); router.push(`/products/${n.productId}`) } }
function clearAll() { jobs.markAllRead() }
function reltime(ts) {
  const s = Math.floor((Date.now() - ts) / 1000)
  if (s < 60) return '방금'
  if (s < 3600) return `${Math.floor(s / 60)}분 전`
  if (s < 86400) return `${Math.floor(s / 3600)}시간 전`
  return new Date(ts).toLocaleDateString('sv-SE')
}
const metrics = computed(() => {
  const s = data.value?.summary || {}
  return session.isPM
    ? [
        { k: '상품', v: s.products, tone: '' },
        { k: '추출 중', v: s.extracting, tone: s.extracting ? 'accent' : '' },
        { k: '분석 중', v: s.runningAnalyses, tone: s.runningAnalyses ? 'accent' : '' },
        { k: '검토 대기', v: s.pendingReviews, tone: '' },
        { k: '반려', v: s.rejectedReviews, tone: s.rejectedReviews ? 'high' : '' },
      ]
    : [
        { k: '검토 대기', v: s.pendingReviews, tone: s.pendingReviews ? 'accent' : '' },
        { k: 'HIGH Finding', v: s.highFindings, tone: s.highFindings ? 'high' : '' },
        { k: '활성 패턴', v: s.activeRiskPatterns, tone: 'ok' },
        { k: '오늘 결정', v: s.decidedToday, tone: '' },
      ]
})

onMounted(load)
async function load() {
  loading.value = true
  try { data.value = session.isPM ? await api.getDashboardMe() : await api.getDashboardCompliance() }
  catch (e) { toast.fromError(e) } finally { loading.value = false }
}
</script>

<template>
  <div class="wrap rise">
    <header class="top">
      <div>
        <h1 class="d-h1 greet">{{ session.user?.name }}님, 오늘의 작업</h1>
      </div>
      <GButton v-if="session.isPM" variant="primary" @click="router.push('/products')">
        <template #icon><PhFolders :size="16" /></template>상품 관리
      </GButton>
      <GButton v-else variant="primary" @click="router.push('/reviews')">
        <template #icon><PhClipboardText :size="16" /></template>검토함 열기
      </GButton>
    </header>

    <!-- Instrument readout, not stat cards -->
    <div class="metrics">
      <div v-for="m in metrics" :key="m.k" class="metric">
        <span class="num" :class="m.tone ? `fg-${m.tone}` : ''">
          <GSkeleton v-if="loading" w="38px" h="34px" /><template v-else>{{ pad(m.v) }}</template>
        </span>
        <span class="mono mlabel">{{ m.k }}</span>
      </div>
    </div>

    <!-- PM: 알림 + 최근 상품 2단 / Reviewer: 우선 검토 -->
    <div v-if="session.isPM" class="dash-cols">
      <section class="panel">
        <div class="q-head">
          <h2 class="d-h3 notif-heading"><PhBell :size="18" />알림</h2>
          <button v-if="notes.length" class="linkish" @click="clearAll">모두 읽음</button>
        </div>
        <div v-if="loading" class="q-list"><div v-for="i in 3" :key="i" class="q-sk"><GSkeleton h="16px" w="70%" /></div></div>
        <div v-else-if="!notes.length" class="notif-empty"><span class="t-sm fw-medium">새 알림이 없습니다</span><span class="t-xs mute">추출·분석·검토가 끝나면 여기에 표시됩니다.</span></div>
        <ul v-else class="notif-list">
          <li v-for="n in notes" :key="n.id" class="notif-row" tabindex="0" @click="openNote(n)" @keyup.enter="openNote(n)">
            <span class="notif-dot" :class="{ on: !n.read }" aria-hidden="true"></span>
            <div class="notif-main">
              <span class="notif-msg" :class="{ mut: n.read }">{{ n.message }}</span>
              <span class="mono notif-time">{{ reltime(n.at) }}</span>
            </div>
          </li>
        </ul>
      </section>

      <section class="panel">
        <div class="q-head">
          <h2 class="d-h3">최근 상품</h2>
          <RouterLink v-if="!loading" class="q-all" to="/products">상품 전체 <PhArrowUpRight :size="14" /></RouterLink>
        </div>
        <div v-if="loading" class="q-list"><div v-for="i in 4" :key="i" class="q-sk"><GSkeleton h="20px" w="40%" /><GSkeleton h="14px" w="20%" /></div></div>
        <GEmptyState v-else-if="!data?.recentItems?.length" title="등록한 상품이 없습니다" description="상품을 등록하고 첫 분석을 시작하세요.">
          <template #icon><PhFolders :size="20" /></template>
          <template #action><GButton variant="primary" @click="router.push('/products')"><template #icon><PhPlus :size="16" /></template>상품 등록</GButton></template>
        </GEmptyState>
        <ul v-else class="q-list">
          <li v-for="p in data.recentItems" :key="p.productId" class="q-row" tabindex="0" @click="router.push(`/products/${p.productId}`)" @keyup.enter="router.push(`/products/${p.productId}`)">
            <div class="q-main">
              <span style="display:inline-flex;align-items:center;gap:6px"><span class="q-title fw-semibold">{{ p.name }}</span><span v-if="jobs.unreadForProduct(p.productId)" title="새 알림" style="display:inline-grid;place-items:center;width:18px;height:18px;border-radius:999px;background:var(--accent-wash);color:var(--accent);font-weight:700;font-size:11px;line-height:1">!</span></span>
              <span class="mono q-meta">{{ PRODUCT_TYPE_LABEL[p.productType] }} · {{ p.productId }} · {{ formatDateTime(p.createdAt) }}</span>
            </div>
            <div class="q-side">
              <GStatusPill :status="productStatusKey(p)" />
              <PhArrowUpRight class="q-go" :size="17" />
            </div>
          </li>
        </ul>
      </section>
    </div>

    <section v-else class="queue">
      <div class="q-head">
        <h2 class="d-h3">우선 검토</h2>
        <RouterLink v-if="!loading" class="q-all" to="/reviews">검토함 전체 <PhArrowUpRight :size="14" /></RouterLink>
      </div>
      <div v-if="loading" class="q-list"><div v-for="i in 4" :key="i" class="q-sk"><GSkeleton h="20px" w="40%" /><GSkeleton h="14px" w="20%" /></div></div>
      <GEmptyState v-else-if="!data?.priorityReviews?.length" title="대기 중인 검토가 없습니다"><template #icon><PhClipboardText :size="20" /></template></GEmptyState>
      <ul v-else class="q-list">
        <li v-for="r in data.priorityReviews" :key="r.reviewId" class="q-row" tabindex="0" @click="router.push(`/reviews/${r.reviewId}`)" @keyup.enter="router.push(`/reviews/${r.reviewId}`)">
          <div class="q-main">
            <span class="q-title fw-semibold">{{ r.productName }}</span>
            <span class="mono q-meta">{{ r.ownerName }} · {{ r.analysisId }}</span>
          </div>
          <div class="q-side">
            <GSeverityBadge :severity="r.maxSeverity" />
            <GStatusPill :status="r.status" />
            <PhArrowUpRight class="q-go" :size="17" />
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.top { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-24); flex-wrap: wrap; }
.greet { margin-top: var(--s-12); }
.dash-cols { display: grid; grid-template-columns: 0.82fr 1.18fr; gap: var(--s-24); margin-top: var(--s-40); align-items: start; }
.queue { margin-top: var(--s-40); }
.notif-list { list-style: none; }
.notif-heading { display: inline-flex; align-items: center; gap: var(--s-8); }
.notif-empty { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: var(--s-16) var(--s-8); }
.notif-row { display: flex; gap: var(--s-12); align-items: flex-start; padding: var(--s-14, 14px) var(--s-8); border-bottom: 1px solid var(--line); cursor: pointer; transition: background var(--fast) var(--ease); }
.notif-row:hover, .notif-row:focus-visible { background: var(--surface-2); }
.notif-dot { width: 7px; height: 7px; border-radius: var(--r-pill); background: var(--line-strong); margin-top: 6px; flex: none; }
.notif-dot.on { background: var(--accent); }
.notif-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.notif-msg { line-height: 1.5; }
.notif-msg.mut { color: var(--ink-mute); }
.notif-time { font-size: var(--text-xs); color: var(--ink-faint); }
.linkish { border: 0; background: transparent; color: var(--ink-mute); font-size: var(--text-xs); cursor: pointer; }
.linkish:hover { color: var(--ink); }
@media (max-width: 900px) { .dash-cols { grid-template-columns: 1fr; } }

.metrics {
  display: flex; flex-wrap: wrap; margin-top: var(--s-40);
  border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); overflow: hidden;
}
.metric { flex: 1; min-width: 120px; padding: var(--s-20) var(--s-24); border-left: 1px solid var(--line); }
.metric:first-child { border-left: 0; }
.num { display: block; font-family: var(--font-mono); font-size: 34px; font-weight: var(--fw-medium); line-height: 1; letter-spacing: -0.02em; color: var(--ink); }
.mlabel { display: block; margin-top: var(--s-10); font-size: 11px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--ink-mute); }
.fg-accent { color: var(--accent); }
.fg-high { color: var(--risk-high); }
.fg-ok { color: var(--ok); }

.queue { margin-top: var(--s-48); }
.q-head { display: flex; align-items: baseline; justify-content: space-between; padding-bottom: var(--s-16); border-bottom: 1px solid var(--line-strong); }
.q-all { display: inline-flex; align-items: center; gap: 4px; font-size: var(--text-sm); font-weight: var(--fw-medium); color: var(--ink-soft); }
.q-all:hover { color: var(--accent); }
.q-list { display: flex; flex-direction: column; }
.q-row {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-16);
  padding: var(--s-20) var(--s-12); border-bottom: 1px solid var(--line); cursor: pointer;
  transition: background var(--fast) var(--ease);
}
.q-row:hover, .q-row:focus-visible { background: var(--surface-2); }
.q-main { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.q-title { font-size: var(--text-lg); letter-spacing: -0.01em; }
.q-meta { font-size: var(--text-xs); color: var(--ink-mute); }
.q-side { display: flex; align-items: center; gap: var(--s-16); flex: none; }
.q-go { color: var(--ink-faint); transition: transform var(--base) var(--ease), color var(--fast) var(--ease); }
.q-row:hover .q-go, .q-row:focus-visible .q-go { color: var(--accent); transform: translateX(3px); }
.q-sk { display: flex; flex-direction: column; gap: 8px; padding: var(--s-20) var(--s-4); border-bottom: 1px solid var(--line); }
</style>
