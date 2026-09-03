<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowUpRight, PhClipboardText } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useToastStore } from '@/stores/toast'
import { formatDateTime } from '@/lib/format'
import GSeverityBadge from '@/components/ui/GSeverityBadge.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'
import GPagination from '@/components/ui/GPagination.vue'

const SIZE = 12
const router = useRouter()
const toast = useToastStore()
const loading = ref(true)
const items = ref([])
const page = ref(0)
const total = ref(0)
const filter = ref('PENDING')
const filters = [{ v: 'ALL', l: '전체' }, { v: 'PENDING', l: '대기' }, { v: 'APPROVED', l: '승인' }, { v: 'REJECTED', l: '반려' }]
const emptyTitle = computed(() => filter.value === 'PENDING' ? '대기 중인 검토가 없습니다' : '선택한 이력에 검토 기록이 없습니다')
const emptyDescription = computed(() => filter.value === 'PENDING' ? '새 검토 요청이 접수되면 여기에 표시됩니다.' : '다른 상태 필터에서 검토 이력을 확인해 주세요.')

async function load() {
  loading.value = true
  try {
    const res = await api.listReviews({ status: filter.value === 'ALL' ? undefined : filter.value, page: page.value, size: SIZE })
    items.value = res.items
    total.value = res.totalElements
  } catch (e) {
    toast.fromError(e)
  } finally {
    loading.value = false
  }
}
function onPage(p) { page.value = p; load() }
watch(filter, () => { page.value = 0; load() })
onMounted(load)
</script>

<template>
  <div class="wrap rise">
    <header class="top">
      <div><h1 class="d-h1">검토함</h1></div>
      <div class="seg"><button v-for="f in filters" :key="f.v" class="seg-b" :class="{ on: filter === f.v }" @click="filter = f.v">{{ f.l }}</button></div>
    </header>
    <p class="lead t-sm mute">위험도 내림차순, 제출시간 오름차순으로 정렬됩니다. <span class="mono" v-if="!loading">· 총 {{ total }}건</span></p>

    <div v-if="loading" class="list"><div v-for="i in 6" :key="i" class="sk"><GSkeleton h="18px" w="35%" /><GSkeleton h="13px" w="20%" /></div></div>
    <GEmptyState v-else-if="!items.length" :title="emptyTitle" :description="emptyDescription"><template #icon><PhClipboardText :size="20" /></template></GEmptyState>
    <template v-else>
      <ul class="list">
        <li v-for="r in items" :key="r.reviewId" class="row" tabindex="0" @click="router.push(`/reviews/${r.reviewId}`)" @keyup.enter="router.push(`/reviews/${r.reviewId}`)">
          <div class="l"><span class="fw-semibold name">{{ r.productName }}</span><span class="mono meta">{{ r.ownerName }} · {{ r.analysisId }} · {{ formatDateTime(r.submittedAt) }}</span></div>
          <div class="r"><GSeverityBadge :severity="r.maxSeverity" /><GStatusPill :status="r.status" /><PhArrowUpRight class="go" :size="17" /></div>
        </li>
      </ul>
      <GPagination :model-value="page" :total="total" :size="SIZE" class="pager" @update:model-value="onPage" />
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 940px; }
.top { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-16); }
.top .kicker { margin-bottom: var(--s-10); }
.seg { display: inline-flex; border: 1px solid var(--line-strong); border-radius: var(--r); padding: 2px; gap: 2px; }
.seg-b { border: 0; background: transparent; padding: 6px 12px; border-radius: var(--r-sm); font-size: var(--text-xs); font-weight: var(--fw-medium); color: var(--ink-mute); cursor: pointer; font-family: var(--font-mono); letter-spacing: 0.02em; }
.seg-b.on { background: var(--ink); color: #fff; }
.lead { margin: var(--s-16) 0 var(--s-8); }
.list { list-style: none; border-top: 1px solid var(--line-strong); }
.row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-16); padding: var(--s-20) var(--s-12); border-bottom: 1px solid var(--line); cursor: pointer; transition: background var(--fast) var(--ease); }
.row:hover, .row:focus-visible { background: var(--surface-2); }
.l { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.name { font-size: var(--text-lg); letter-spacing: -0.01em; }
.meta { font-size: var(--text-xs); color: var(--ink-mute); }
.r { display: flex; align-items: center; gap: var(--s-16); flex: none; }
.go { color: var(--ink-faint); transition: transform var(--base) var(--ease), color var(--fast) var(--ease); } .row:hover .go, .row:focus-visible .go { color: var(--accent); transform: translateX(3px); }
.sk { display: flex; flex-direction: column; gap: 8px; padding: var(--s-20) var(--s-12); border-bottom: 1px solid var(--line); }
.pager { padding: var(--s-28) 0 var(--s-8); }
</style>
