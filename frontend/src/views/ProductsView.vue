<script setup>
import { onMounted, ref, reactive, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowUpRight, PhPlus, PhFolders, PhMagnifyingGlass, PhX } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useToastStore } from '@/stores/toast'
import { useJobsStore } from '@/stores/jobs'
import { PRODUCT_TYPE_LABEL, formatDateTime, productStatusKey } from '@/lib/format'
import { matchesQuery } from '@/lib/hangul'
import GButton from '@/components/ui/GButton.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'
import GModal from '@/components/ui/GModal.vue'
import GField from '@/components/ui/GField.vue'
import GTextInput from '@/components/ui/GTextInput.vue'
import GTextarea from '@/components/ui/GTextarea.vue'
import GSelect from '@/components/ui/GSelect.vue'
import GPagination from '@/components/ui/GPagination.vue'

const PAGE = 12
const router = useRouter()
const toast = useToastStore()
const jobs = useJobsStore()
const loading = ref(true)
const items = ref([])
const q = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const page = ref(0)
const showCreate = ref(false)
const submitting = ref(false)
const form = reactive({ name: '', productType: 'INVESTMENT', description: '' })
const fieldErrors = ref({})

const typeFilters = [{ v: '', l: 'ALL' }, { v: 'INVESTMENT', l: '투자' }, { v: 'LOAN', l: '대출' }, { v: 'SAVINGS', l: '예금' }]
const statusFilters = [
  { v: '', l: '전체' }, { v: 'RUNNING', l: '분석중' }, { v: 'ANALYZED', l: '분석됨' },
  { v: 'IN_REVIEW', l: '검토중' }, { v: 'APPROVED', l: '승인' }, { v: 'NEEDS_FIX', l: '수정필요' }, { v: 'DRAFT', l: '초안' },
]
const typeOptions = [
  { value: 'INVESTMENT', label: '투자 · INVESTMENT' },
  { value: 'LOAN', label: '대출 · LOAN' },
  { value: 'SAVINGS', label: '예금 · SAVINGS' },
]

const filtered = computed(() => {
  const term = q.value.trim().toLowerCase()
  return items.value.slice().sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || ''))).filter((p) => {
    if (typeFilter.value && p.productType !== typeFilter.value) return false
    if (statusFilter.value && productStatusKey(p) !== statusFilter.value) return false
    if (!term) return true
    return matchesQuery(p.name, q.value) || p.productId.toLowerCase().includes(term)
  })
})
const shown = computed(() => filtered.value.slice(page.value * PAGE, page.value * PAGE + PAGE))
watch([q, typeFilter, statusFilter], () => { page.value = 0 })

onMounted(load)
async function load() { loading.value = true; try { items.value = (await api.listProducts()).items } catch (e) { toast.fromError(e) } finally { loading.value = false } }
function openCreate() { form.name = ''; form.productType = 'INVESTMENT'; form.description = ''; fieldErrors.value = {}; showCreate.value = true }
async function submit() {
  submitting.value = true; fieldErrors.value = {}
  try { const res = await api.createProduct({ ...form }); toast.success('상품 등록됨', res.productId); showCreate.value = false; router.push(`/products/${res.productId}`) }
  catch (e) { if (e?.fieldErrors?.length) fieldErrors.value = Object.fromEntries(e.fieldErrors.map((f) => [f.field, f.message])); toast.fromError(e) }
  finally { submitting.value = false }
}
</script>

<template>
  <div class="wrap rise">
    <header class="top">
      <div><h1 class="d-h1">상품</h1></div>
      <GButton variant="primary" @click="openCreate"><template #icon><PhPlus :size="16" /></template>상품 등록</GButton>
    </header>

    <!-- Controls -->
    <div class="controls">
      <div class="search">
        <PhMagnifyingGlass :size="16" class="s-ico" />
        <input
          v-model="q"
          class="s-input"
          type="text"
          placeholder="이름 또는 ID로 검색"
          aria-label="상품 검색"
          autocomplete="off"
          autocorrect="off"
          autocapitalize="off"
          spellcheck="false"
        />
        <button v-if="q" class="s-clear" aria-label="지우기" @click="q = ''"><PhX :size="14" /></button>
      </div>
      <div class="seg">
        <button v-for="f in typeFilters" :key="f.v" class="seg-b" :class="{ on: typeFilter === f.v }" @click="typeFilter = f.v">{{ f.l }}</button>
      </div>
    </div>
    <div class="seg seg-status">
      <button v-for="f in statusFilters" :key="f.v" class="seg-b" :class="{ on: statusFilter === f.v }" @click="statusFilter = f.v">{{ f.l }}</button>
    </div>

    <p v-if="!loading" class="resline mono mute">
      총 {{ filtered.length }}개<template v-if="q || typeFilter"> · 전체 {{ items.length }}</template>
    </p>

    <div v-if="loading" class="list">
      <div v-for="i in 6" :key="i" class="sk"><GSkeleton h="20px" w="35%" /><GSkeleton h="13px" w="22%" /></div>
    </div>
    <GEmptyState v-else-if="!items.length" title="등록된 상품이 없습니다" description="첫 상품을 등록해 분석 흐름을 시작하세요.">
      <template #icon><PhFolders :size="20" /></template>
      <template #action><GButton variant="primary" @click="openCreate"><template #icon><PhPlus :size="16" /></template>상품 등록</GButton></template>
    </GEmptyState>
    <GEmptyState v-else-if="!filtered.length" title="검색 결과가 없습니다" :description="`'${q}'에 해당하는 상품이 없습니다.`">
      <template #icon><PhMagnifyingGlass :size="20" /></template>
      <template #action><GButton variant="secondary" @click="q = ''; typeFilter = ''; statusFilter = ''">필터 초기화</GButton></template>
    </GEmptyState>

    <template v-else>
      <ul class="list">
        <li v-for="p in shown" :key="p.productId" class="row" tabindex="0" @click="router.push(`/products/${p.productId}`)" @keyup.enter="router.push(`/products/${p.productId}`)">
          <div class="l">
            <div class="l-title"><span class="fw-semibold name">{{ p.name }}</span><span class="tag mono">{{ PRODUCT_TYPE_LABEL[p.productType] }}</span><span v-if="jobs.unreadForProduct(p.productId)" class="new-mark" title="새 알림">!</span></div>
            <span class="mono meta">{{ p.productId }} · {{ formatDateTime(p.createdAt) }}</span>
          </div>
          <div class="r">
            <GStatusPill :status="productStatusKey(p)" />
            <PhArrowUpRight class="go" :size="17" />
          </div>
        </li>
      </ul>
      <GPagination v-model="page" :total="filtered.length" :size="PAGE" class="pager" />
    </template>

    <GModal v-if="showCreate" title="상품 등록" @close="showCreate = false">
      <form class="form" @submit.prevent="submit">
        <GField label="상품명" required for-id="pn" :error="fieldErrors.name" hint="1 - 100자"><GTextInput id="pn" v-model="form.name" placeholder="예: 스마트 인컴 투자상품" :invalid="!!fieldErrors.name" /></GField>
        <GField label="상품 유형" required for-id="pt"><GSelect id="pt" v-model="form.productType" :options="typeOptions" /></GField>
        <GField label="설명" for-id="pd" :error="fieldErrors.description" hint="500자 이하"><GTextarea id="pd" v-model="form.description" :rows="3" placeholder="상품 개요" :invalid="!!fieldErrors.description" /></GField>
      </form>
      <template #footer><GButton variant="ghost" @click="showCreate = false">취소</GButton><GButton variant="primary" :loading="submitting" @click="submit">등록</GButton></template>
    </GModal>
  </div>
</template>

<style scoped>
.wrap { max-width: 940px; }
.top { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-16); margin-bottom: var(--s-24); }
.top .kicker { margin-bottom: var(--s-10); }
.controls { display: flex; align-items: center; gap: var(--s-12); flex-wrap: wrap; }
.search { position: relative; display: flex; align-items: center; flex: 1; min-width: 220px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--r-sm); }
.search:focus-within { border-color: var(--accent); box-shadow: var(--focus); }
.s-ico { color: var(--ink-mute); margin-left: 12px; flex: none; }
.s-input { flex: 1; border: 0; background: transparent; padding: 10px 12px; font-size: var(--text-base); color: var(--ink); }
.s-input:focus-visible { outline: none; box-shadow: none; } .s-input::placeholder { color: var(--ink-faint); }
.s-clear { border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; padding: 0 10px; display: grid; place-items: center; }
.seg { display: inline-flex; border: 1px solid var(--line-strong); border-radius: var(--r); padding: 2px; gap: 2px; flex: none; }
.seg-b { border: 0; background: transparent; padding: 6px 12px; border-radius: var(--r-sm); font-size: var(--text-xs); font-weight: var(--fw-medium); color: var(--ink-mute); cursor: pointer; font-family: var(--font-mono); letter-spacing: 0.02em; }
.seg-b.on { background: var(--ink); color: #fff; }
.seg-status { display: flex; flex-wrap: wrap; margin-top: var(--s-12); }
.resline { margin: var(--s-16) 0 var(--s-4); font-size: var(--text-xs); }
.list { list-style: none; border-top: 1px solid var(--line-strong); }
.row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-16); padding: var(--s-20) var(--s-12); border-bottom: 1px solid var(--line); cursor: pointer; transition: background var(--fast) var(--ease); }
.row:hover, .row:focus-visible { background: var(--surface-2); }
.l { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.l-title { display: flex; align-items: center; gap: var(--s-10); }
.name { font-size: var(--text-lg); letter-spacing: -0.01em; }
.tag { font-size: 11px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--ink-mute); border: 1px solid var(--line-strong); border-radius: var(--r-xs); padding: 2px 6px; }
.new-mark { display: inline-grid; place-items: center; width: 18px; height: 18px; border-radius: var(--r-pill); background: var(--accent-wash); color: var(--accent); font-weight: 700; font-size: 11px; line-height: 1; }
.meta { font-size: var(--text-xs); color: var(--ink-mute); }
.r { display: flex; align-items: center; gap: var(--s-16); flex: none; }
.go { color: var(--ink-faint); transition: transform var(--base) var(--ease), color var(--fast) var(--ease); } .row:hover .go, .row:focus-visible .go { color: var(--accent); transform: translateX(3px); }
.sk { display: flex; flex-direction: column; gap: 8px; padding: var(--s-20) var(--s-4); border-bottom: 1px solid var(--line); }
.pager { padding: var(--s-28) 0 var(--s-8); }
.form { display: flex; flex-direction: column; gap: var(--s-20); }
</style>
