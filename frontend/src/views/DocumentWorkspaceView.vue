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
const VERIFIED_TEXT_MAXIMUM_COUNT = 10000
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
const currentConfirmed = computed(() => doc.value?.confirmed && !doc.value?.requiresConfirmation)
const METHOD = { 'pdf-text': 'PDF 텍스트', pptx: 'PPTX', ocr: 'OCR', text: '텍스트' }
const PAGE_METHOD = { PDFBOX_TEXT: 'PDFBOX', OCR_KOR_ENG: 'OCR (kor+eng)' }
const CONFIDENCE_BAND = { HIGH: '높음', MEDIUM: '보통', LOW: '낮음' }

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
function validateText() {
  if (verifiedText.value.length > VERIFIED_TEXT_MAXIMUM_COUNT) {
    toast.push({ type: 'error', title: '확정 텍스트 입력 오류', message: `확정 텍스트는 ${VERIFIED_TEXT_MAXIMUM_COUNT.toLocaleString()}자 이하로 입력하세요.` })
    return false
  }
  return true
}
async function saveText() {
  if (!validateText()) return
  saving.value = true
  try {
    const res = await api.patchDocumentText(props.documentId, { verifiedText: verifiedText.value, confirmed: false })
    doc.value = { ...doc.value, ...res, verifiedText: verifiedText.value }
    textDirty.value = false
    toast.success('텍스트 저장됨', '현재 실행과 텍스트 해시를 확인한 뒤 확정하세요')
  }
  catch (e) { toast.fromError(e) } finally { saving.value = false }
}
async function confirmCurrent() {
  if (!validateText() || textDirty.value) return
  saving.value = true
  try {
    const res = await api.patchDocumentText(props.documentId, {
      verifiedText: verifiedText.value,
      confirmed: true,
      currentRunId: doc.value.currentRunId,
      currentTextHash: doc.value.currentTextHash,
    })
    doc.value = { ...doc.value, ...res, verifiedText: verifiedText.value }
    toast.success('현재 텍스트 확정됨', '백엔드가 현재 실행과 저장된 텍스트를 확인했습니다')
  }
  catch (e) { toast.fromError(e) } finally { saving.value = false }
}
async function retry() {
  retrying.value = true
  try { await api.retryDocument(props.documentId); toast.info('추출 재시도'); const res = await api.getDocument(props.documentId); apply(res); start((r) => ['READY', 'FAILED'].includes(r.extractStatus)) }
  catch (e) { toast.fromError(e) } finally { retrying.value = false }
}
function goAnalyze() { if (doc.value?.productId) router.push(`/products/${doc.value.productId}/analyze`) }
function goBack() { doc.value?.productId ? router.push(`/products/${doc.value.productId}`) : router.push('/products') }
async function openRender(page) {
  const preview = window.open('', '_blank')
  try {
    const blob = await api.getDocumentPageRender(page.renderArtifactUrl)
    const url = URL.createObjectURL(blob)
    if (preview) preview.location.replace(url)
    else window.open(url, '_blank', 'noopener')
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (error) {
    preview?.close()
    toast.fromError(error)
  }
}
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
        <GButton v-if="canEdit && doc.error?.retryable" variant="secondary" size="sm" :loading="retrying" @click="retry"><template #icon><PhArrowClockwise :size="15" /></template>재시도</GButton>
      </div>

      <template v-else-if="doc.extractStatus === 'READY'">
        <section v-if="doc.currentRunId" class="provenance">
          <div class="prov-head">
            <div>
              <p class="mono ml">백엔드 추출 provenance</p>
              <p class="t-sm soft">실행 #{{ doc.currentRunId }} · 텍스트 <span class="mono hash">{{ doc.currentTextHash }}</span></p>
            </div>
            <span :class="['confirm-state', currentConfirmed ? 'is-confirmed' : 'is-unconfirmed']">
              <PhCheckCircle v-if="currentConfirmed" :size="14" weight="fill" />
              <PhWarningCircle v-else :size="14" />
              {{ currentConfirmed ? '현재 실행·텍스트 확인됨' : '확인되지 않음' }}
            </span>
          </div>
          <p class="t-xs mute notice">OCR 신뢰도는 인식 품질 지표이며 담당자 확인을 의미하지 않습니다.</p>
          <ol class="pages">
            <li v-for="page in doc.pages" :key="page.pageNumber" class="page">
              <div class="page-top">
                <strong>페이지 {{ page.pageNumber }}</strong>
                <GBadge :tone="page.selectedMethod === 'OCR_KOR_ENG' ? 'med' : 'neutral'">
                  {{ PAGE_METHOD[page.selectedMethod] || page.selectedMethod }}
                </GBadge>
              </div>
              <dl class="page-meta">
                <div><dt>텍스트 SHA-256</dt><dd class="mono hash">{{ page.textHash }}</dd></div>
                <template v-if="page.selectedMethod === 'OCR_KOR_ENG'">
                  <div><dt>엔진</dt><dd>{{ page.ocrEngine }} {{ page.ocrModelVersion }}</dd></div>
                  <div><dt>언어</dt><dd>{{ page.ocrLanguage }}</dd></div>
                  <div><dt>신뢰도</dt><dd>{{ page.ocrConfidence }} · {{ CONFIDENCE_BAND[page.ocrConfidenceBand] || page.ocrConfidenceBand }}</dd></div>
                  <div><dt>렌더 SHA-256</dt><dd class="mono hash">{{ page.renderArtifactHash }}</dd></div>
                  <div v-if="page.renderArtifactUrl"><dt>렌더 URL</dt><dd><button class="render-link" type="button" @click="openRender(page)">{{ page.renderArtifactUrl }}</button></dd></div>
                  <div v-if="page.ocrWarnings?.length"><dt>경고</dt><dd>{{ page.ocrWarnings.join(' · ') }}</dd></div>
                </template>
              </dl>
            </li>
          </ol>
        </section>
        <div v-else-if="!doc.confirmed" class="legacy-unconfirmed">
          <PhWarningCircle :size="16" /> 확인되지 않은 텍스트입니다.
        </div>
        <div class="cols">
          <div class="col">
            <p class="mono ml">추출 원문 · 읽기 전용</p>
            <div class="raw">{{ doc.rawExtractedText }}</div>
          </div>
          <div class="col">
            <div class="ml-row"><p class="mono ml">확정 텍스트</p><span v-if="currentConfirmed" class="conf"><PhCheckCircle :size="13" weight="fill" /> 확정됨</span><span v-else class="unconf">미확정</span></div>
            <GField hint="분석에 사용할 최종 텍스트입니다. 원문은 변경되지 않습니다." :current-count="verifiedText.length" :maximum-count="VERIFIED_TEXT_MAXIMUM_COUNT">
              <GTextarea v-model="verifiedText" :rows="8" :disabled="!canEdit" :maximum-count="VERIFIED_TEXT_MAXIMUM_COUNT" placeholder="추출 텍스트를 검토하고 보정하세요" @update:modelValue="textDirty = true" />
            </GField>
            <p v-if="doc.confirmedAt" class="mono cmeta">{{ doc.confirmedBy }} · {{ formatDateTime(doc.confirmedAt) }}</p>
          </div>
        </div>
        <div v-if="canEdit" class="acts">
          <GButton variant="secondary" :loading="saving" :disabled="!verifiedText.trim() || !textDirty" @click="saveText">텍스트 저장</GButton>
          <GButton variant="secondary" :loading="saving" :disabled="!verifiedText.trim() || textDirty || currentConfirmed" @click="confirmCurrent"><template #icon><PhCheckCircle :size="16" /></template>현재 실행·텍스트 확정</GButton>
          <GButton variant="primary" :disabled="!currentConfirmed || textDirty" @click="goAnalyze"><template #icon><PhLightning :size="16" /></template>분석으로 이동</GButton>
        </div>
        <p v-if="canEdit && (!currentConfirmed || textDirty)" class="analysis-gate">백엔드의 현재 실행·텍스트 확인이 완료되어야 분석할 수 있습니다.</p>
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
.provenance { margin-top: var(--s-24); padding: var(--s-20); border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); }
.prov-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-16); }
.confirm-state { display: inline-flex; align-items: center; gap: 5px; flex: none; padding: 5px 8px; border-radius: var(--r); font-size: var(--text-xs); font-weight: var(--fw-medium); }
.is-confirmed { color: var(--ok); background: var(--ok-wash); }
.is-unconfirmed, .unconf { color: var(--risk-high); }
.notice { margin-top: var(--s-10); }
.pages { display: grid; gap: var(--s-10); margin-top: var(--s-16); padding: 0; list-style: none; }
.page { padding: var(--s-12); border: 1px solid var(--line); border-radius: var(--r); background: var(--surface-2); }
.page-top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-10); font-size: var(--text-sm); }
.page-meta { display: grid; gap: 6px; margin-top: var(--s-10); font-size: var(--text-xs); }
.page-meta div { display: grid; grid-template-columns: 112px minmax(0, 1fr); gap: var(--s-10); }
.page-meta dt { color: var(--ink-mute); }
.page-meta dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: var(--ink-2); }
.render-link { border: 0; padding: 0; background: transparent; color: var(--accent); cursor: pointer; text-align: left; overflow-wrap: anywhere; }
.hash { overflow-wrap: anywhere; }
.legacy-unconfirmed { display: flex; align-items: center; gap: 6px; margin-top: var(--s-24); padding: var(--s-12); border-radius: var(--r); background: var(--risk-high-wash); color: var(--risk-high); font-size: var(--text-sm); }
.cols { margin-top: var(--s-32); display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-32); }
.col { display: flex; flex-direction: column; gap: var(--s-12); }
.ml { font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-mute); }
.ml-row { display: flex; align-items: center; justify-content: space-between; }
.conf { display: inline-flex; align-items: center; gap: 4px; color: var(--ok); font-size: var(--text-xs); font-weight: var(--fw-medium); }
.raw { background: var(--surface-2); border-radius: var(--r); padding: var(--s-16); line-height: 1.75; white-space: pre-wrap; min-height: 180px; font-size: var(--text-sm); color: var(--ink-2); }
.cmeta { font-size: 11px; color: var(--ink-mute); }
.acts { display: flex; justify-content: flex-end; gap: var(--s-10); margin-top: var(--s-24); }
.analysis-gate { margin-top: var(--s-10); text-align: right; color: var(--risk-high); font-size: var(--text-xs); }
@media (max-width: 760px) { .cols { grid-template-columns: 1fr; } }
</style>
