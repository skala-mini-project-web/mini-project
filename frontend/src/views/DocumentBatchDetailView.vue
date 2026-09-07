<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowLeft, PhArrowClockwise, PhDownloadSimple, PhFiles } from '@phosphor-icons/vue'
import { documentBatchesApi } from '@/api/documentBatches'
import { useSessionStore } from '@/stores/session'
import { useToastStore } from '@/stores/toast'
import { formatBytes, formatDateTime } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'

const props = defineProps({ batchId: { type: String, required: true } })
const router = useRouter()
const session = useSessionStore()
const toast = useToastStore()
const batch = ref(null)
const items = ref([])
const loading = ref(true)
const refreshing = ref(false)
const actionKey = ref('')
let timer = null

const isPM = computed(() => session.isPM)
const isTerminal = computed(() => ['SUCCEEDED', 'CANCELLED', 'QUARANTINED'].includes(batch.value?.status))
const canManageBatch = computed(() => isPM.value && batch.value?.status === 'PENDING')
const completedCount = computed(() => (batch.value?.succeededItemCount || 0) + (batch.value?.cancelledItemCount || 0) + (batch.value?.quarantinedItemCount || 0))
const stateLabels = {
  PENDING: '대기',
  LEASED: '처리 중',
  RETRY_WAIT: '재시도 대기',
  SUCCEEDED: '성공',
  CANCELLED: '취소',
  QUARANTINED: '격리',
}
function stateLabel(status) { return stateLabels[status] || status }

function back() {
  router.push(isPM.value && batch.value?.productId ? `/products/${batch.value.productId}` : '/dashboard')
}
async function refresh({ quiet = false } = {}) {
  if (refreshing.value) return
  refreshing.value = true
  try {
    const [batchResponse, itemResponse] = await Promise.all([
      documentBatchesApi.get(props.batchId),
      documentBatchesApi.listItems(props.batchId),
    ])
    batch.value = batchResponse
    items.value = itemResponse.items || []
    if (isTerminal.value) stopPolling()
  } catch (err) {
    if (!quiet) toast.fromError(err)
  } finally {
    refreshing.value = false
    loading.value = false
  }
}
function startPolling() {
  stopPolling()
  timer = window.setInterval(() => refresh({ quiet: true }), 2000)
}
function stopPolling() {
  if (timer) window.clearInterval(timer)
  timer = null
}
async function runAction(key, action, successMessage) {
  if (actionKey.value) return
  actionKey.value = key
  try {
    await action()
    toast.success('처리 완료', successMessage)
    await refresh()
    if (!isTerminal.value) startPolling()
  } catch (err) {
    toast.fromError(err)
  } finally {
    actionKey.value = ''
  }
}
function cancelBatch() {
  runAction('batch-cancel', () => documentBatchesApi.cancelBatch(props.batchId, '사용자 취소'), '처리 전 항목을 취소했습니다.')
}
function quarantineBatch() {
  runAction('batch-quarantine', () => documentBatchesApi.quarantineBatch(props.batchId, '사용자 격리'), '처리 전 항목을 격리했습니다.')
}
function cancelItem(item) {
  runAction(`cancel-${item.itemId}`, () => documentBatchesApi.cancelItem(props.batchId, item.itemId, '사용자 취소'), `${item.fileName} 항목을 취소했습니다.`)
}
function quarantineItem(item) {
  runAction(`quarantine-${item.itemId}`, () => documentBatchesApi.quarantineItem(props.batchId, item.itemId, '사용자 격리'), `${item.fileName} 항목을 격리했습니다.`)
}
function retryItem(item) {
  runAction(`retry-${item.itemId}`, () => documentBatchesApi.retryItem(props.batchId, item.itemId), `${item.fileName} 항목을 다시 대기열에 넣었습니다.`)
}
async function downloadReport() {
  if (actionKey.value) return
  actionKey.value = 'report'
  try {
    const blob = await documentBatchesApi.downloadErrorReport(props.batchId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `document-batch-${props.batchId}-errors.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (err) {
    toast.fromError(err)
  } finally {
    actionKey.value = ''
  }
}
function canCancel(item) { return isPM.value && ['PENDING', 'RETRY_WAIT'].includes(item.status) }
function canRetry(item) { return isPM.value && ['CANCELLED', 'QUARANTINED'].includes(item.status) }

onMounted(async () => {
  await refresh()
  if (!isTerminal.value) startPolling()
})
onUnmounted(stopPolling)
</script>

<template>
  <div class="wrap rise">
    <button class="back" type="button" @click="back"><PhArrowLeft :size="15" /> 돌아가기</button>

    <template v-if="loading">
      <GSkeleton w="45%" h="34px" />
      <GSkeleton w="100%" h="150px" style="margin-top:24px" />
    </template>

    <template v-else-if="batch">
      <header class="top">
        <div>
          <p class="kicker">DOCUMENT BATCH · {{ batch.batchId }}</p>
          <h1 class="d-h1">문서 일괄 처리</h1>
          <p class="t-sm mute note">상품 {{ batch.productId }} · {{ formatDateTime(batch.createdAt) }}</p>
        </div>
        <span class="state" :class="`state-${batch.status.toLowerCase()}`">{{ stateLabel(batch.status) }}</span>
      </header>

      <section class="summary" aria-label="배치 처리 현황">
        <div><strong class="mono">{{ completedCount }} / {{ batch.requestedItemCount }}</strong><span>처리 완료</span></div>
        <div><strong class="mono">{{ batch.pendingItemCount }}</strong><span>대기</span></div>
        <div><strong class="mono">{{ batch.leasedItemCount }}</strong><span>처리 중</span></div>
        <div><strong class="mono">{{ batch.retryWaitingItemCount }}</strong><span>재시도 대기</span></div>
        <div><strong class="mono">{{ batch.succeededItemCount }}</strong><span>성공</span></div>
        <div><strong class="mono">{{ batch.cancelledItemCount + batch.quarantinedItemCount }}</strong><span>취소/격리</span></div>
      </section>

      <div class="toolbar">
        <p class="t-sm mute">{{ isTerminal ? '처리가 종료되었습니다.' : '2초마다 최신 상태를 확인합니다.' }}<span v-if="!isPM"> 검토자는 조회만 가능합니다.</span></p>
        <div class="actions">
          <GButton variant="secondary" size="sm" :loading="refreshing" @click="refresh()"><template #icon><PhArrowClockwise :size="15" /></template>새로고침</GButton>
          <GButton variant="secondary" size="sm" :loading="actionKey === 'report'" @click="downloadReport"><template #icon><PhDownloadSimple :size="15" /></template>오류 CSV</GButton>
          <template v-if="canManageBatch">
            <GButton variant="secondary" size="sm" :disabled="!!actionKey" @click="cancelBatch">배치 취소</GButton>
            <GButton variant="danger" size="sm" :disabled="!!actionKey" @click="quarantineBatch">배치 격리</GButton>
          </template>
        </div>
      </div>

      <section class="sec">
        <h2 class="d-h3">항목 <span class="mono count">{{ items.length }}</span></h2>
        <GEmptyState v-if="!items.length" title="배치 항목이 없습니다"><template #icon><PhFiles :size="20" /></template></GEmptyState>
        <div v-else class="table-wrap">
          <table>
            <thead><tr><th>파일</th><th>상태</th><th>시도</th><th>오류</th><th v-if="isPM">관리</th></tr></thead>
            <tbody>
              <tr v-for="item in items" :key="item.itemId">
                <td><span class="file-name">{{ item.fileName }}</span><span class="sub mono">#{{ item.ordinal }} · {{ formatBytes(item.fileSize) }}</span></td>
                <td><span class="state" :class="`state-${item.status.toLowerCase()}`">{{ stateLabel(item.status) }}</span><span v-if="item.cancellationReason || item.quarantineReason" class="sub">{{ item.cancellationReason || item.quarantineReason }}</span></td>
                <td class="mono">{{ item.attemptCount }} / {{ item.maxAttempts }}</td>
                <td><span v-if="item.errorCode" class="error-code mono">{{ item.errorCode }}</span><span class="sub error-message">{{ item.errorMessage || '—' }}</span></td>
                <td v-if="isPM">
                  <div class="item-actions">
                    <GButton v-if="canCancel(item)" variant="secondary" size="sm" :disabled="!!actionKey" @click="cancelItem(item)">취소</GButton>
                    <GButton v-if="canCancel(item)" variant="secondary" size="sm" :disabled="!!actionKey" @click="quarantineItem(item)">격리</GButton>
                    <GButton v-if="canRetry(item)" variant="primary" size="sm" :loading="actionKey === `retry-${item.itemId}`" :disabled="!!actionKey && actionKey !== `retry-${item.itemId}`" @click="retryItem(item)">재시도</GButton>
                    <span v-if="!canCancel(item) && !canRetry(item)" class="mute">—</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 1100px; }
.back { display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--s-16); }
.back:hover { color: var(--ink); }
.top { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-16); }
.note { margin-top: var(--s-10); }
.state { display: inline-flex; align-items: center; padding: 4px 8px; border-radius: var(--r-pill); background: var(--surface-2); color: var(--ink-soft); font-size: var(--text-xs); font-weight: var(--fw-medium); white-space: nowrap; }
.state-leased, .state-retry_wait { background: var(--accent-wash); color: var(--accent); }
.state-succeeded { background: var(--ok-wash); color: var(--ok); }
.state-cancelled, .state-quarantined { background: var(--risk-high-wash); color: var(--risk-high); }
.summary { display: grid; grid-template-columns: repeat(6, 1fr); margin-top: var(--s-28, 28px); border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; }
.summary div { display: flex; flex-direction: column; gap: 4px; padding: var(--s-16); border-right: 1px solid var(--line); }
.summary div:last-child { border-right: 0; }
.summary strong { font-size: 19px; }
.summary span { color: var(--ink-mute); font-size: var(--text-xs); }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-16); margin-top: var(--s-20); }
.actions, .item-actions { display: flex; align-items: center; justify-content: flex-end; gap: var(--s-8); }
.sec { margin-top: var(--s-36, 36px); }
.count { color: var(--ink-faint); font-size: var(--text-base); }
.table-wrap { overflow-x: auto; margin-top: var(--s-14, 14px); border-top: 1px solid var(--line-strong); }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; vertical-align: middle; padding: var(--s-14, 14px) var(--s-10); border-bottom: 1px solid var(--line); }
th { color: var(--ink-mute); font-size: var(--text-xs); font-weight: var(--fw-medium); white-space: nowrap; }
td { font-size: var(--text-sm); }
.file-name, .sub { display: block; }
.file-name { max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: var(--fw-medium); }
.sub { margin-top: 4px; color: var(--ink-mute); font-size: var(--text-xs); }
.error-code { color: var(--risk-high); font-size: var(--text-xs); }
.error-message { max-width: 320px; white-space: normal; }
@media (max-width: 800px) {
  .summary { grid-template-columns: repeat(3, 1fr); }
  .summary div:nth-child(3) { border-right: 0; }
  .summary div:nth-child(-n+3) { border-bottom: 1px solid var(--line); }
  .toolbar { align-items: flex-start; flex-direction: column; }
  .actions { flex-wrap: wrap; justify-content: flex-start; }
}
</style>
