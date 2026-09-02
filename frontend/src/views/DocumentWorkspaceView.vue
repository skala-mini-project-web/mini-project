<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowLeft, PhCheckCircle, PhWarningCircle, PhArrowClockwise, PhLightning } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useToastStore } from '@/stores/toast'
import { usePolling } from '@/composables/usePolling'
import { formatDateTime } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GBadge from '@/components/ui/GBadge.vue'
import GField from '@/components/ui/GField.vue'
import GTextarea from '@/components/ui/GTextarea.vue'
import GSpinner from '@/components/ui/GSpinner.vue'

const props = defineProps({ documentId: { type: String, required: true } })
const router = useRouter()
const session = useSessionStore()
const toast = useToastStore()
const doc = ref(null)
const loading = ref(true)
const verifiedText = ref('')
const saving = ref(false)
const retrying = ref(false)
const textDirty = ref(false)
const inProgress = computed(() => ['UPLOADED', 'EXTRACTING'].includes(doc.value?.extractStatus))
const canEdit = computed(() => session.isPM)
const METHOD = { 'pdf-text': 'PDF 텍스트', pptx: 'PPTX', ocr: 'OCR', text: '텍스트' }

// OCR runs in-browser and is slow, so extraction polling gets a longer ceiling.
const { polling, start } = usePolling(() => api.getDocument(props.documentId), {
  intervalMs: 1200,
  maxMs: 180000,
  onResult: (res, err) => { if (err) return toast.fromError(err); apply(res) },
})
function apply(res) { doc.value = res; if (!textDirty.value && res.extractStatus === 'READY') verifiedText.value = res.verifiedText ?? res.rawExtractedText ?? '' }
onMounted(async () => {
  try { const res = await api.getDocument(props.documentId); apply(res); if (['UPLOADED', 'EXTRACTING'].includes(res.extractStatus)) start((r) => ['READY', 'FAILED'].includes(r.extractStatus)) }
  catch (e) { toast.fromError(e) } finally { loading.value = false }
})
async function saveConfirm() {
  saving.value = true
  try { const res = await api.patchDocumentText(props.documentId, { verifiedText: verifiedText.value, confirmed: true }); doc.value = { ...doc.value, ...res, verifiedText: verifiedText.value }; textDirty.value = false; toast.success('텍스트 확정됨', '이제 분석을 시작할 수 있습니다') }
  catch (e) { toast.fromError(e) } finally { saving.value = false }
}
async function retry() {
  retrying.value = true
  try { await api.retryDocument(props.documentId); toast.info('추출 재시도'); const res = await api.getDocument(props.documentId); apply(res); start((r) => ['READY', 'FAILED'].includes(r.extractStatus)) }
  catch (e) { toast.fromError(e) } finally { retrying.value = false }
}
function goAnalyze() { if (doc.value?.productId) router.push(`/products/${doc.value.productId}/analyze`) }
function goBack() { doc.value?.productId ? router.push(`/products/${doc.value.productId}`) : router.push('/products') }
</script>

<template>
  <div class="wrap rise">
    <button class="back" @click="goBack"><PhArrowLeft :size="15" /> 상품 상세</button>
    <header class="top">
      <div><h1 class="d-h1 fname">{{ doc?.fileName || documentId }}</h1></div>
      <div v-if="doc" class="top-meta">
        <GBadge v-if="doc.extractStatus === 'READY' && doc.extractMethod" tone="accent">{{ METHOD[doc.extractMethod] || doc.extractMethod }}</GBadge>
        <GStatusPill :status="doc.extractStatus" />
      </div>
    </header>

    <div v-if="loading" class="pad"><GSpinner :size="24" /></div>

    <template v-else-if="doc">
      <div v-if="inProgress" class="prog">
        <GSpinner :size="22" />
        <div><p class="t-base fw-medium">텍스트를 추출하고 있습니다</p><p class="t-sm mute">{{ polling ? '스캔 문서는 브라우저 OCR로 처리되어 다소 걸릴 수 있습니다.' : '잠시만 기다려 주세요' }}</p></div>
      </div>

      <div v-else-if="doc.extractStatus === 'FAILED'" class="fail">
        <PhWarningCircle :size="22" class="fi" />
        <div class="grow"><p class="t-base fw-semibold">추출 실패</p><p class="t-sm soft"><span class="mono">{{ doc.error?.errorCode }}</span> · {{ doc.error?.message }}</p>
          <p v-if="!doc.error?.retryable" class="t-xs mute rn">재시도할 수 없습니다. OCR은 후속 확장 항목입니다.</p></div>
        <GButton v-if="doc.error?.retryable" variant="secondary" size="sm" :loading="retrying" @click="retry"><template #icon><PhArrowClockwise :size="15" /></template>재시도</GButton>
      </div>

      <template v-else-if="doc.extractStatus === 'READY'">
        <div class="cols">
          <div class="col">
            <p class="mono ml">추출 원문 · 읽기 전용</p>
            <div class="raw">{{ doc.rawExtractedText }}</div>
          </div>
          <div class="col">
            <div class="ml-row"><p class="mono ml">확정 텍스트</p><span v-if="doc.confirmed" class="conf"><PhCheckCircle :size="13" weight="fill" /> 확정됨</span></div>
            <GField hint="분석에 사용할 최종 텍스트입니다. 원문은 변경되지 않습니다.">
              <GTextarea v-model="verifiedText" :rows="8" :disabled="!canEdit" placeholder="추출 텍스트를 검토하고 보정하세요" @update:modelValue="textDirty = true" />
            </GField>
            <p v-if="doc.confirmedAt" class="mono cmeta">{{ doc.confirmedBy }} · {{ formatDateTime(doc.confirmedAt) }}</p>
          </div>
        </div>
        <div v-if="canEdit" class="acts">
          <GButton variant="secondary" :loading="saving" :disabled="!verifiedText.trim()" @click="saveConfirm"><template #icon><PhCheckCircle :size="16" /></template>{{ doc.confirmed ? '확정 갱신' : '텍스트 확정' }}</GButton>
          <GButton variant="primary" :disabled="!doc.confirmed || textDirty" @click="goAnalyze"><template #icon><PhLightning :size="16" /></template>분석으로 이동</GButton>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 940px; }
.back { display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--s-16); }
.back:hover { color: var(--ink); }
.top { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-16); }
.top .kicker { margin-bottom: var(--s-10); }
.top-meta { display: flex; align-items: center; gap: var(--s-10); flex: none; }
.fname { word-break: break-all; }
.pad { display: grid; place-items: center; padding: var(--s-64); }
.prog { margin-top: var(--s-40); padding: var(--s-24); border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); display: flex; align-items: center; gap: var(--s-16); }
.fail { margin-top: var(--s-40); padding: var(--s-24); border: 1px solid var(--risk-high-wash); background: var(--risk-high-wash); border-radius: var(--r-lg); display: flex; align-items: center; gap: var(--s-16); }
.fi { color: var(--risk-high); flex: none; } .rn { margin-top: 6px; }
.cols { margin-top: var(--s-32); display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-32); }
.col { display: flex; flex-direction: column; gap: var(--s-12); }
.ml { font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-mute); }
.ml-row { display: flex; align-items: center; justify-content: space-between; }
.conf { display: inline-flex; align-items: center; gap: 4px; color: var(--ok); font-size: var(--text-xs); font-weight: var(--fw-medium); }
.raw { background: var(--surface-2); border-radius: var(--r); padding: var(--s-16); line-height: 1.75; white-space: pre-wrap; min-height: 180px; font-size: var(--text-sm); color: var(--ink-2); }
.cmeta { font-size: 11px; color: var(--ink-mute); }
.acts { display: flex; justify-content: flex-end; gap: var(--s-10); margin-top: var(--s-24); }
@media (max-width: 760px) { .cols { grid-template-columns: 1fr; } }
</style>
