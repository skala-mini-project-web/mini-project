<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowLeft, PhUploadSimple, PhFilePdf, PhFilePpt, PhFileText, PhArrowUpRight, PhChartBar, PhLightning } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useToastStore } from '@/stores/toast'
import { useJobsStore } from '@/stores/jobs'
import { validateUploadFile } from '@/lib/upload'
import { PRODUCT_TYPE_LABEL, formatDateTime, formatBytes } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GBadge from '@/components/ui/GBadge.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'

const props = defineProps({ productId: { type: String, required: true } })
const router = useRouter()
const session = useSessionStore()
const toast = useToastStore()
const loading = ref(true)
const product = ref(null)
const uploading = ref(false)
const fileInput = ref(null)

const canEdit = computed(() => session.isPM && product.value && product.value.ownerId === session.user?.userId)
const confirmedDoc = computed(() => (product.value?.documents || []).find((d) => d.extractStatus === 'READY' && d.confirmed))

onMounted(load)
async function load() { loading.value = true; try { product.value = await api.getProduct(props.productId); useJobsStore().markProductRead(props.productId) } catch (e) { toast.fromError(e); router.push('/products') } finally { loading.value = false } }
function pick() { fileInput.value?.click() }
async function onFile(e) {
  const file = e.target.files?.[0]; e.target.value = ''; if (!file) return
  const v = await validateUploadFile(file)
  if (!v.ok) { toast.push({ type: 'error', title: '업로드 불가', message: v.message }); return }
  uploading.value = true
  try {
    const res = await api.uploadDocument(props.productId, file)
    toast.success('업로드 완료', `${res.fileName}`)
    useJobsStore().track({ kind: 'document', id: res.documentId, name: res.fileName || file.name, productId: props.productId })
    router.push(`/documents/${res.documentId}`)
  }
  catch (err) { toast.fromError(err) } finally { uploading.value = false }
}
function docIcon(mt, n) { if (/pptx?$/i.test(n) || /presentation/.test(mt || '')) return PhFilePpt; if (/pdf/i.test(mt || '') || /pdf$/i.test(n)) return PhFilePdf; return PhFileText }
</script>

<template>
  <div class="wrap rise">
    <button class="back" @click="router.push('/products')"><PhArrowLeft :size="15" /> 상품</button>

    <template v-if="loading"><GSkeleton w="45%" h="34px" /><GSkeleton w="30%" h="16px" style="margin-top:12px" /></template>

    <template v-else-if="product">
      <header class="top">
        <div>
          <p class="kicker">{{ PRODUCT_TYPE_LABEL[product.productType] }} · {{ product.productId }}</p>
          <h1 class="d-h1">{{ product.name }}</h1>
          <p v-if="product.description" class="t-base soft desc">{{ product.description }}</p>
        </div>
        <GStatusPill :status="product.status" />
      </header>

      <input ref="fileInput" type="file" accept=".pdf,.pptx" class="sr-only" @change="onFile" />

      <!-- Documents -->
      <section class="sec">
        <div class="sec-head">
          <h2 class="d-h3">문서 <span class="mono cnt">{{ product.documents?.length || 0 }}</span></h2>
          <GButton v-if="canEdit" variant="secondary" size="sm" :loading="uploading" @click="pick">업로드</GButton>
        </div>
        <p class="sec-note t-sm mute">PDF 또는 PPTX, 최대 10MB. 서버가 실제 텍스트를 추출합니다.</p>

        <GEmptyState v-if="!product.documents?.length" title="문서가 없습니다" description="상품 설명서를 올려 분석을 시작하세요.">
          <template #icon><PhUploadSimple :size="20" /></template>
          <template v-if="canEdit" #action><GButton variant="primary" :loading="uploading" @click="pick">문서 업로드</GButton></template>
        </GEmptyState>
        <ul v-else class="list">
          <li v-for="d in product.documents" :key="d.documentId" class="row" tabindex="0" @click="router.push(`/documents/${d.documentId}`)" @keyup.enter="router.push(`/documents/${d.documentId}`)">
            <component :is="docIcon(d.mediaType, d.fileName)" :size="20" class="ic" />
            <div class="l"><span class="fw-medium fname">{{ d.fileName }}</span><span class="mono meta">{{ formatBytes(d.fileSize) }} · {{ d.documentId }}</span></div>
            <div class="r">
              <GBadge v-if="d.confirmed" tone="ok" :mono="false">확정</GBadge>
              <GStatusPill :status="d.extractStatus" />
              <PhArrowUpRight class="go" :size="16" />
            </div>
          </li>
        </ul>
      </section>

      <!-- Analyses -->
      <section class="sec">
        <div class="sec-head">
          <h2 class="d-h3">분석 <span class="mono cnt">{{ product.analyses?.length || 0 }}</span></h2>
          <GButton v-if="canEdit" variant="primary" size="sm" :disabled="!confirmedDoc" @click="router.push(`/products/${productId}/analyze`)"><template #icon><PhLightning :size="15" /></template>분석 시작</GButton>
        </div>
        <p v-if="canEdit && !confirmedDoc" class="sec-note t-sm mute">분석하려면 먼저 문서 추출 텍스트를 확정하세요.</p>

        <GEmptyState v-if="!product.analyses?.length" title="분석 이력이 없습니다"><template #icon><PhChartBar :size="20" /></template></GEmptyState>
        <ul v-else class="list">
          <li v-for="a in product.analyses" :key="a.analysisId" class="row" tabindex="0" @click="router.push(`/analyses/${a.analysisId}`)" @keyup.enter="router.push(`/analyses/${a.analysisId}`)">
            <span class="score mono" :class="{ dim: a.riskScore == null }">{{ a.riskScore ?? '—' }}</span>
            <div class="l"><span class="fw-medium mono">{{ a.analysisId }}</span><span class="mono meta">{{ formatDateTime(a.createdAt) }}</span></div>
            <div class="r"><GStatusPill :status="a.status" /><PhArrowUpRight class="go" :size="16" /></div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 900px; }
.back { display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--s-16); }
.back:hover { color: var(--ink); }
.top { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-16); }
.top .kicker { margin-bottom: var(--s-10); }
.desc { margin-top: var(--s-12); max-width: 60ch; }
.sec { margin-top: var(--s-48); }
.sec-head { display: flex; align-items: baseline; justify-content: space-between; padding-bottom: var(--s-14, 14px); border-bottom: 1px solid var(--line-strong); }
.cnt { color: var(--ink-faint); font-size: var(--text-base); margin-left: 4px; }
.sec-note { margin-top: var(--s-12); }
.list { list-style: none; margin-top: var(--s-8); }
.row { display: flex; align-items: center; gap: var(--s-14, 14px); padding: var(--s-16) var(--s-12); border-bottom: 1px solid var(--line); cursor: pointer; transition: background var(--fast) var(--ease); }
.row:hover, .row:focus-visible { background: var(--surface-2); }
.ic { color: var(--ink-mute); flex: none; }
.score { display: grid; place-items: center; min-width: 40px; height: 40px; padding: 0 10px; border: 1px solid var(--line); border-radius: var(--r-sm); font-size: 17px; font-weight: var(--fw-medium); color: var(--ink); flex: none; }
.score.dim { color: var(--ink-faint); }
.l { display: flex; flex-direction: column; gap: 3px; flex: 1; min-width: 0; }
.fname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta { font-size: var(--text-xs); color: var(--ink-mute); }
.r { display: flex; align-items: center; gap: var(--s-12); flex: none; }
.go { color: var(--ink-faint); }
</style>
