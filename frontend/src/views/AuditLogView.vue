<script setup>
import { onMounted, ref, computed } from 'vue'
import { PhFingerprint } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useToastStore } from '@/stores/toast'
import { formatDateTime } from '@/lib/format'
import GBadge from '@/components/ui/GBadge.vue'
import GButton from '@/components/ui/GButton.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'
import GPagination from '@/components/ui/GPagination.vue'

const PAGE = 15
const toast = useToastStore()
const loading = ref(true)
const items = ref([])
const page = ref(0)
const shown = computed(() => items.value.slice(page.value * PAGE, page.value * PAGE + PAGE))
const tone = (a) => (/APPROVED/.test(a) ? 'ok' : /REJECTED/.test(a) ? 'high' : /SUBMITTED|CREATED/.test(a) ? 'accent' : 'neutral')
onMounted(async () => { try { items.value = (await api.listAuditLogs({})).items } catch (e) { toast.fromError(e) } finally { loading.value = false } })
</script>

<template>
  <div class="wrap rise">
    <header class="top"><h1 class="d-h1">감사 로그</h1><p class="t-base soft sub">상태 변경과 검토 결정 이력입니다. 원문 전문은 기록하지 않습니다.</p></header>

    <div v-if="loading" class="list"><GSkeleton v-for="i in 5" :key="i" h="44px" radius="var(--r-sm)" /></div>
    <GEmptyState v-else-if="!items.length" title="감사 로그가 없습니다"><template #icon><PhFingerprint :size="20" /></template></GEmptyState>
    <div v-else class="table">
      <div class="thead mono">
        <span>시각</span><span>액션</span><span>리소스</span><span>행위자</span><span>Trace ID</span>
      </div>
      <div v-for="log in shown" :key="log.auditId" class="trow">
        <span class="mono cell dt">{{ formatDateTime(log.createdAt) }}</span>
        <span class="cell"><GBadge :tone="tone(log.action)">{{ log.action }}</GBadge></span>
        <span class="mono cell">{{ log.resourceType }} · {{ log.resourceId }}</span>
        <span class="mono cell">{{ log.actorId }}</span>
        <span class="mono cell mute">{{ log.traceId }}</span>
      </div>
    </div>
    <GPagination v-if="!loading" v-model="page" :total="items.length" :size="PAGE" class="pager" />
  </div>
</template>

<style scoped>
.wrap { max-width: 980px; }
.top .kicker { margin-bottom: var(--s-10); } .sub { margin-top: var(--s-12); max-width: 60ch; }
.list { display: flex; flex-direction: column; gap: var(--s-8); margin-top: var(--s-32); }
.table { margin-top: var(--s-32); border: 1px solid var(--line); border-radius: var(--r-lg); overflow: hidden; background: var(--surface); }
.thead, .trow { display: grid; grid-template-columns: 1.4fr 1.3fr 1.3fr 1.1fr 1.3fr; gap: var(--s-16); padding: var(--s-14, 14px) var(--s-20); align-items: center; }
.thead { font-size: 11px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--ink-mute); border-bottom: 1px solid var(--line-strong); background: var(--surface-2); }
.trow { border-bottom: 1px solid var(--line); font-size: var(--text-sm); }
.trow:last-child { border-bottom: 0; }
.trow:hover { background: var(--surface-2); }
.cell { overflow: hidden; text-overflow: ellipsis; }
.dt { color: var(--ink-soft); }
.pager { padding: var(--s-28) 0 var(--s-8); }
@media (max-width: 820px) { .thead { display: none; } .trow { grid-template-columns: 1fr; gap: 6px; } }
</style>
