<script setup>
import { onMounted, ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowClockwise, PhStack, PhShieldCheck, PhWarningCircle } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useToastStore } from '@/stores/toast'
import { RULE_LABEL, personaName, ACTION_TYPE_LABEL } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GBadge from '@/components/ui/GBadge.vue'
import GSeverityBadge from '@/components/ui/GSeverityBadge.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GEmptyState from '@/components/ui/GEmptyState.vue'
import GSkeleton from '@/components/ui/GSkeleton.vue'
import GModal from '@/components/ui/GModal.vue'
import GField from '@/components/ui/GField.vue'
import GTextInput from '@/components/ui/GTextInput.vue'
import GSelect from '@/components/ui/GSelect.vue'

const GUARDFIT_TEXT_MAXIMUM_COUNT = 100
const router = useRouter()
const toast = useToastStore()
const loading = ref(true)
const loadError = ref(null)
const items = ref([])
const severity = ref('')
const submitting = ref(false)
const showDetail = ref(false)
const showCreate = ref(false)
const active = ref(null)
const form = reactive({ actionType: 'WARNING', label: '', placement: '', required: 'true' })
const actionTypeOptions = Object.entries(ACTION_TYPE_LABEL).map(([value, label]) => ({ value, label: `${label} · ${value}` }))
const requiredOptions = [{ value: 'true', label: '필수' }, { value: 'false', label: '권장' }]
const sevFilters = [{ v: '', l: 'ALL' }, { v: 'HIGH', l: 'HIGH' }, { v: 'MEDIUM', l: 'MED' }, { v: 'LOW', l: 'LOW' }]

onMounted(load)
async function load() {
  loading.value = true
  try {
    const response = await api.listRiskPatterns(severity.value ? { severity: severity.value } : {})
    items.value = response.items
    loadError.value = null
  } catch (e) {
    loadError.value = e
    toast.fromError(e)
  } finally {
    loading.value = false
  }
}
function openDetail(p) {
  active.value = p
  showDetail.value = true
}
function openCreate(p) {
  const suggestion = p.guardFitSuggestion
  active.value = p
  form.actionType = suggestion?.actionType || 'WARNING'
  form.label = suggestion?.label || ''
  form.placement = suggestion?.placement || ''
  form.required = suggestion?.required === false ? 'false' : 'true'
  showDetail.value = false
  showCreate.value = true
}
async function submit() {
  if (form.label.length > GUARDFIT_TEXT_MAXIMUM_COUNT) {
    toast.push({ type: 'error', title: 'GuardFit 라벨 입력 오류', message: `GuardFit 라벨은 ${GUARDFIT_TEXT_MAXIMUM_COUNT}자 이하로 입력하세요.` })
    return
  }
  if (form.placement.length > GUARDFIT_TEXT_MAXIMUM_COUNT) {
    toast.push({ type: 'error', title: 'GuardFit 배치 위치 입력 오류', message: `GuardFit 배치 위치는 ${GUARDFIT_TEXT_MAXIMUM_COUNT}자 이하로 입력하세요.` })
    return
  }
  submitting.value = true
  try { const res = await api.createGuardFitAction({ riskPatternId: active.value.riskPatternId, actionType: form.actionType, label: form.label, placement: form.placement, required: form.required === 'true' }); toast.success('GuardFit 후보 생성', `${res.actionId} · DRAFT`); showCreate.value = false; router.push('/guardfit') }
  catch (e) { toast.fromError(e) } finally { submitting.value = false }
}
</script>

<template>
  <div class="wrap rise">
    <header class="top">
      <div><h1 class="d-h1">위험 패턴</h1></div>
      <div class="seg"><button v-for="f in sevFilters" :key="f.v" class="seg-b" :class="{ on: severity === f.v }" @click="severity = f.v; load()">{{ f.l }}</button></div>
    </header>
    <p class="lead t-sm mute">승인 Finding에서 승격된 재사용 가능한 위험 패턴입니다.</p>

    <div v-if="loading && !items.length" class="list"><div v-for="i in 3" :key="i" class="sk"><GSkeleton h="18px" w="30%" /><GSkeleton h="13px" w="45%" /></div></div>
    <div v-else-if="loadError && !items.length" class="load-fail" role="alert">
      <PhWarningCircle :size="22" class="load-fail-i" />
      <div class="grow"><p class="t-base fw-semibold">위험 패턴을 불러오지 못했습니다</p><p class="t-sm soft">잠시 후 다시 시도해 주세요.</p></div>
      <GButton variant="secondary" size="sm" @click="load"><template #icon><PhArrowClockwise :size="15" /></template>다시 시도</GButton>
    </div>
    <GEmptyState v-else-if="!items.length" title="위험 패턴이 없습니다" description="검토에서 Finding을 승인하면 패턴으로 승격됩니다."><template #icon><PhStack :size="20" /></template></GEmptyState>
    <ul v-else class="list">
      <li v-if="loadError" class="load-fail" role="alert">
        <PhWarningCircle :size="22" class="load-fail-i" />
        <div class="grow"><p class="t-base fw-semibold">위험 패턴을 새로 불러오지 못했습니다</p><p class="t-sm soft">이전에 불러온 내용을 유지합니다.</p></div>
        <GButton variant="secondary" size="sm" @click.stop="load"><template #icon><PhArrowClockwise :size="15" /></template>다시 시도</GButton>
      </li>
      <li
        v-for="rp in items"
        :key="rp.riskPatternId"
        class="row"
        role="button"
        tabindex="0"
        :aria-label="`${rp.title} 상세 보기`"
        @click="openDetail(rp)"
        @keydown.enter="openDetail(rp)"
        @keydown.space.prevent="openDetail(rp)"
      >
        <div class="l">
          <div class="l-top"><GSeverityBadge :severity="rp.severity" /><span class="d-h3 title">{{ rp.title }}</span></div>
          <div class="tags"><GBadge tone="neutral">{{ RULE_LABEL[rp.ruleCode] || rp.ruleCode }}</GBadge><GBadge v-for="pc in rp.affectedPersonaCodes" :key="pc" tone="neutral" :mono="false">{{ personaName(pc) }}</GBadge></div>
          <span class="mono trace">{{ rp.sourceFindingId }} · {{ rp.sourceReviewId }} · {{ rp.sourceAnalysisId || '-' }} · {{ rp.riskPatternId }}</span>
        </div>
        <div class="r">
          <GStatusPill :status="rp.status" />
        </div>
      </li>
    </ul>

    <GModal v-if="showDetail" :title="active?.title" @close="showDetail = false">
      <dl class="detail">
        <div>
          <dt>Finding 전체 내용</dt>
          <dd>{{ active?.findingStatement || '-' }}</dd>
        </div>
        <div>
          <dt>원문 발췌</dt>
          <dd>{{ active?.sourceExcerpt || '-' }}</dd>
        </div>
        <div>
          <dt>권고 사항</dt>
          <dd>{{ active?.recommendation || '-' }}</dd>
        </div>
        <div class="detail-grid">
          <div><dt>심각도</dt><dd>{{ active?.severity || '-' }}</dd></div>
          <div><dt>규칙</dt><dd>{{ RULE_LABEL[active?.ruleCode] || active?.ruleCode || '-' }} <span class="mono">({{ active?.ruleCode || '-' }})</span></dd></div>
        </div>
        <div>
          <dt>영향 대상</dt>
          <dd>{{ active?.affectedPersonaCodes?.map(personaName).join(', ') || '-' }}</dd>
        </div>
        <div>
          <dt>추적 ID</dt>
          <dd class="trace-list">
            <span><b>Finding</b> {{ active?.sourceFindingId || '-' }}</span>
            <span><b>검토</b> {{ active?.sourceReviewId || '-' }}</span>
            <span><b>분석</b> {{ active?.sourceAnalysisId || '-' }}</span>
            <span><b>위험 패턴</b> {{ active?.riskPatternId || '-' }}</span>
          </dd>
        </div>
      </dl>
      <template #footer>
        <GButton variant="ghost" @click="showDetail = false">닫기</GButton>
        <GButton variant="primary" :disabled="active?.status !== 'ACTIVE'" @click="openCreate(active)">
          <template #icon><PhShieldCheck :size="15" /></template>보호조치 초안 만들기
        </GButton>
      </template>
    </GModal>

    <GModal v-if="showCreate" :title="`GuardFit 후보 · ${active?.title}`" @close="showCreate = false">
      <form class="form" @submit.prevent="submit">
        <GField label="조치 유형" for-id="at"><GSelect id="at" v-model="form.actionType" :options="actionTypeOptions" /></GField>
        <GField label="라벨 문구" required for-id="al" hint="소비자에게 노출될 문구" :current-count="form.label.length" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT"><GTextInput id="al" v-model="form.label" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT" placeholder="예: 원금 손실 가능" /></GField>
        <GField label="배치 위치" required for-id="ap" :current-count="form.placement.length" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT"><GTextInput id="ap" v-model="form.placement" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT" placeholder="예: 상품 상세 상단" /></GField>
        <GField label="필수 여부" for-id="ar"><GSelect id="ar" v-model="form.required" :options="requiredOptions" /></GField>
      </form>
      <template #footer><GButton variant="ghost" @click="showCreate = false">취소</GButton><GButton variant="primary" :loading="submitting" :disabled="!form.label.trim() || !form.placement.trim()" @click="submit">DRAFT 생성</GButton></template>
    </GModal>
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
.load-fail { margin: var(--s-16) 0; padding: var(--s-20); border: 1px solid var(--risk-high-wash); background: var(--risk-high-wash); border-radius: var(--r-lg); display: flex; align-items: center; gap: var(--s-16); list-style: none; }
.load-fail-i { color: var(--risk-high); flex: none; }
.list { list-style: none; border-top: 1px solid var(--line-strong); }
.row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-20); padding: var(--s-24) var(--s-4); border-bottom: 1px solid var(--line); cursor: pointer; }
.row:hover { background: var(--surface-2); }
.row:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
.l { display: flex; flex-direction: column; gap: var(--s-10); min-width: 0; }
.l-top { display: flex; align-items: center; gap: var(--s-10); }
.title { letter-spacing: -0.01em; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.trace { font-size: 11px; color: var(--ink-faint); }
.r { display: flex; align-items: center; gap: var(--s-12); flex: none; }
.sk { display: flex; flex-direction: column; gap: 8px; padding: var(--s-24) var(--s-4); border-bottom: 1px solid var(--line); }
.detail { display: flex; flex-direction: column; gap: var(--s-20); margin: 0; }
.detail > div { display: flex; flex-direction: column; gap: var(--s-8); }
.detail dt { color: var(--ink-mute); font-size: var(--text-xs); font-weight: var(--fw-medium); }
.detail dd { margin: 0; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.detail-grid { display: grid !important; grid-template-columns: 1fr 2fr; gap: var(--s-16) !important; }
.detail-grid > div { display: flex; flex-direction: column; gap: var(--s-8); }
.trace-list { display: flex; flex-direction: column; gap: 4px; font-family: var(--font-mono); font-size: var(--text-xs); }
.trace-list b { display: inline-block; min-width: 72px; color: var(--ink-mute); font-family: var(--font-sans); }
.form { display: flex; flex-direction: column; gap: var(--s-20); }
@media (max-width: 680px) { .row { flex-direction: column; align-items: flex-start; } .detail-grid { grid-template-columns: 1fr; } }
</style>
