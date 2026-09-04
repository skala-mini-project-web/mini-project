<script setup>
import { onMounted, ref, reactive, computed, watch } from 'vue'
import { PhShieldCheck, PhPencilSimple, PhSealCheck, PhArrowRight, PhArrowClockwise, PhWarningCircle } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useToastStore } from '@/stores/toast'
import { ACTION_TYPE_LABEL, formatDateTime, personaName } from '@/lib/format'
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
const session = useSessionStore()
const toast = useToastStore()
const loading = ref(true)
const loadError = ref(null)
const items = ref([])
const showEdit = ref(false)
const editing = ref(null)
const saving = ref(null)
const statusFilter = ref('DRAFT')
const form = reactive({ label: '', placement: '', required: 'true' })
const requiredOptions = [{ value: 'true', label: '필수' }, { value: 'false', label: '권장' }]
const statusFilters = [{ value: 'DRAFT', label: '초안' }, { value: 'APPROVED', label: '승인' }, { value: 'ALL', label: '전체' }]
const approved = computed(() => items.value.filter((a) => a.status === 'APPROVED'))
const reviewerEmptyTitle = computed(() => statusFilter.value === 'DRAFT' ? '승인 대기 중인 GuardFit 초안이 없습니다' : '선택한 이력에 보호조치 기록이 없습니다')
const reviewerEmptyDescription = computed(() => statusFilter.value === 'DRAFT'
  ? 'Risk Library에서 ACTIVE Risk Pattern 상세를 열어 GuardFit 초안을 만들면 여기에 표시됩니다.'
  : '다른 상태 필터에서 보호조치 이력을 확인해 주세요.')

onMounted(load)
watch(statusFilter, load)
async function load() {
  loading.value = true
  try {
    const status = session.isPM ? 'APPROVED' : statusFilter.value === 'ALL' ? undefined : statusFilter.value
    const response = await api.listGuardFitActions({ status })
    items.value = response.items
    loadError.value = null
  } catch (e) {
    loadError.value = e
    toast.fromError(e)
  } finally {
    loading.value = false
  }
}
function openEdit(a) { editing.value = a; form.label = a.label; form.placement = a.placement; form.required = a.required ? 'true' : 'false'; showEdit.value = true }
async function save(status) {
  if (form.label.length > GUARDFIT_TEXT_MAXIMUM_COUNT) {
    toast.push({ type: 'error', title: 'GuardFit 라벨 입력 오류', message: `GuardFit 라벨은 ${GUARDFIT_TEXT_MAXIMUM_COUNT}자 이하로 입력하세요.` })
    return
  }
  if (form.placement.length > GUARDFIT_TEXT_MAXIMUM_COUNT) {
    toast.push({ type: 'error', title: 'GuardFit 배치 위치 입력 오류', message: `GuardFit 배치 위치는 ${GUARDFIT_TEXT_MAXIMUM_COUNT}자 이하로 입력하세요.` })
    return
  }
  saving.value = status
  try { await api.updateGuardFitAction(editing.value.actionId, { label: form.label, placement: form.placement, required: form.required === 'true', status }); toast.success(`${{ DRAFT: '초안 저장', APPROVED: '승인' }[status]} 완료`, editing.value.actionId); showEdit.value = false; await load() }
  catch (e) { toast.fromError(e) } finally { saving.value = null }
}
</script>

<template>
  <div class="wrap rise">
    <header class="top">
      <h1 class="d-h1">{{ session.isPM ? '적용 가이드' : '보호조치 관리' }}</h1>
      <p class="t-base soft sub">{{ session.isPM ? '승인된 보호조치만 표시됩니다. 상품 화면에 반영할 가이드입니다.' : '승인 패턴에서 만든 보호조치를 편집하고 결정합니다.' }}</p>
    </header>

    <!-- PM: Before/After 가이드 (spec GF-001) -->
    <template v-if="session.isPM">
      <p class="pm-note t-sm soft">승인된 보호조치의 <b class="fw-semibold" style="color:var(--ink)">적용 전(원문 표현)</b>과 <b class="fw-semibold" style="color:var(--ink)">적용 후(권고 조치)</b>를 비교한 가이드입니다.</p>
      <div v-if="loading && !items.length" class="ba-list"><GSkeleton v-for="i in 3" :key="i" h="128px" radius="var(--r-lg)" /></div>
      <div v-else-if="loadError && !items.length" class="load-fail" role="alert">
        <PhWarningCircle :size="22" class="load-fail-i" />
        <div class="grow"><p class="t-base fw-semibold">적용 가이드를 불러오지 못했습니다</p><p class="t-sm soft">잠시 후 다시 시도해 주세요.</p></div>
        <GButton variant="secondary" size="sm" @click="load"><template #icon><PhArrowClockwise :size="15" /></template>다시 시도</GButton>
      </div>
      <GEmptyState v-else-if="!approved.length && !loadError" title="승인된 보호조치가 없습니다" description="검토자가 Risk Library에서 GuardFit 초안을 만든 뒤 보호조치 관리에서 승인해야 표시됩니다."><template #icon><PhShieldCheck :size="20" /></template></GEmptyState>
      <div v-else class="ba-list">
        <div v-if="loadError" class="load-fail" role="alert">
          <PhWarningCircle :size="22" class="load-fail-i" />
          <div class="grow"><p class="t-base fw-semibold">적용 가이드를 새로 불러오지 못했습니다</p><p class="t-sm soft">이전에 불러온 내용을 유지합니다.</p></div>
          <GButton variant="secondary" size="sm" @click="load"><template #icon><PhArrowClockwise :size="15" /></template>다시 시도</GButton>
        </div>
        <article v-for="a in approved" :key="a.actionId" class="ba">
          <div class="ba-head">
            <GSeverityBadge v-if="a.pattern" :severity="a.pattern.severity" />
            <GBadge tone="accent">{{ ACTION_TYPE_LABEL[a.actionType] || a.actionType }}</GBadge>
            <GBadge v-if="a.pattern" tone="neutral">{{ a.pattern.ruleCode }}</GBadge>
            <GBadge :tone="a.required ? 'high' : 'neutral'" :mono="false">{{ a.required ? '필수' : '권장' }}</GBadge>
          </div>
          <p v-if="a.pattern?.title" class="pattern-title"><span>출처 Risk Pattern</span>{{ a.pattern.title }}</p>
          <div class="ba-grid">
            <div class="ba-col before">
              <span class="ba-tag mono">BEFORE · 원문 표현</span>
              <p class="ba-text">“{{ a.pattern?.sourceExcerpt || '원문 표현' }}”</p>
              <div v-if="a.pattern?.affectedPersonaCodes?.length" class="ba-personas"><GBadge v-for="pc in a.pattern.affectedPersonaCodes" :key="pc" tone="neutral" :mono="false">{{ personaName(pc) }}</GBadge></div>
            </div>
            <div class="ba-arrow"><PhArrowRight :size="18" /></div>
            <div class="ba-col after">
              <span class="ba-tag mono">AFTER · 보호조치</span>
              <p class="ba-label">{{ a.label }}</p>
              <p class="ba-place mono">위치: {{ a.placement }}</p>
              <p v-if="a.pattern?.recommendation" class="ba-reco">권고 · {{ a.pattern.recommendation }}</p>
            </div>
          </div>
          <details v-if="a.supportingContext" class="support">
            <summary>근거 보기</summary>
            <p class="t-sm fw-semibold">{{ a.supportingContext.statement }}</p>
            <p v-for="(source, index) in a.supportingContext.sourceReferences || []" :key="`s-${index}`" class="t-sm soft">판매자료 p.{{ source.page ?? '-' }} · {{ source.excerpt }}</p>
            <p v-for="evidence in a.supportingContext.evidenceReferences || []" :key="evidence.evidenceDocumentId" class="t-sm soft">공식 근거 {{ evidence.evidenceDocumentId }} · {{ evidence.excerpt }}</p>
            <p v-for="caseItem in a.supportingContext.caseReferences || []" :key="caseItem.knowledgeSourceId" class="t-sm soft">민원/분쟁 · {{ caseItem.excerpt }}</p>
            <p class="mono t-xs mute">finding {{ a.supportingContext.findingId }} · riskPattern {{ a.riskPatternId }} · review {{ a.supportingContext.reviewId }}</p>
          </details>
        </article>
      </div>
    </template>

    <!-- Reviewer management -->
    <template v-else>
      <div class="reviewer-tools">
        <div class="seg" aria-label="보호조치 상태 필터">
          <button v-for="option in statusFilters" :key="option.value" class="seg-b" :class="{ on: statusFilter === option.value }" @click="statusFilter = option.value">{{ option.label }}</button>
        </div>
      </div>
      <div v-if="loading && !items.length" class="list"><div v-for="i in 3" :key="i" class="sk"><GSkeleton h="18px" w="40%" /><GSkeleton h="13px" w="25%" /></div></div>
      <div v-else-if="loadError && !items.length" class="load-fail" role="alert">
        <PhWarningCircle :size="22" class="load-fail-i" />
        <div class="grow"><p class="t-base fw-semibold">보호조치를 불러오지 못했습니다</p><p class="t-sm soft">잠시 후 다시 시도해 주세요.</p></div>
        <GButton variant="secondary" size="sm" @click="load"><template #icon><PhArrowClockwise :size="15" /></template>다시 시도</GButton>
      </div>
      <GEmptyState v-else-if="!items.length" :title="reviewerEmptyTitle" :description="reviewerEmptyDescription"><template #icon><PhShieldCheck :size="20" /></template></GEmptyState>
      <ul v-else class="list">
        <li v-if="loadError" class="load-fail" role="alert">
          <PhWarningCircle :size="22" class="load-fail-i" />
          <div class="grow"><p class="t-base fw-semibold">보호조치를 새로 불러오지 못했습니다</p><p class="t-sm soft">이전에 불러온 내용을 유지합니다.</p></div>
          <GButton variant="secondary" size="sm" @click="load"><template #icon><PhArrowClockwise :size="15" /></template>다시 시도</GButton>
        </li>
        <li v-for="a in items" :key="a.actionId" class="row">
          <div class="l">
            <div class="l-top"><GBadge tone="accent">{{ ACTION_TYPE_LABEL[a.actionType] || a.actionType }}</GBadge><GBadge :tone="a.required ? 'high' : 'neutral'" :mono="false">{{ a.required ? '필수' : '권장' }}</GBadge><GStatusPill :status="a.status" /></div>
            <span v-if="a.pattern?.title" class="pattern-title reviewer-pattern"><span>출처 Risk Pattern</span>{{ a.pattern.title }}</span>
            <span class="fw-medium label">{{ a.label }}</span>
            <span class="mono meta">{{ a.placement }} · {{ a.riskPatternId }}</span>
            <details v-if="a.supportingContext" class="support">
              <summary>근거 보기</summary>
              <p class="t-sm">{{ a.supportingContext.statement }}</p>
              <p class="mono t-xs mute">finding {{ a.supportingContext.findingId }} · review {{ a.supportingContext.reviewId }}</p>
            </details>
          </div>
          <GButton v-if="a.status === 'DRAFT'" variant="secondary" size="sm" @click="openEdit(a)"><template #icon><PhPencilSimple :size="15" /></template>편집·결정</GButton>
          <span v-else class="mono fin">{{ formatDateTime(a.updatedAt) }}</span>
        </li>
      </ul>
    </template>

    <GModal v-if="showEdit" title="GuardFit 조치 편집" @close="showEdit = false">
      <form class="form" @submit.prevent>
        <GField label="라벨 문구" required for-id="el" :current-count="form.label.length" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT"><GTextInput id="el" v-model="form.label" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT" /></GField>
        <GField label="배치 위치" required for-id="ep" :current-count="form.placement.length" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT"><GTextInput id="ep" v-model="form.placement" :maximum-count="GUARDFIT_TEXT_MAXIMUM_COUNT" /></GField>
        <GField label="필수 여부" for-id="er"><GSelect id="er" v-model="form.required" :options="requiredOptions" /></GField>
      </form>
      <template #footer>
        <GButton variant="ghost" :loading="saving === 'DRAFT'" :disabled="!!saving" @click="save('DRAFT')">초안 저장</GButton>
        <GButton variant="primary" :loading="saving === 'APPROVED'" :disabled="!!saving" @click="save('APPROVED')"><template #icon><PhSealCheck :size="15" /></template>승인</GButton>
      </template>
    </GModal>
  </div>
</template>

<style scoped>
.wrap { max-width: 940px; }
.top .kicker { margin-bottom: var(--s-10); } .sub { margin-top: var(--s-12); max-width: 56ch; }
.pm-note { margin: var(--s-16) 0 var(--s-24); }
.reviewer-tools { display: flex; justify-content: flex-end; margin-top: var(--s-20); }
.seg { display: inline-flex; border: 1px solid var(--line-strong); border-radius: var(--r); padding: 2px; gap: 2px; }
.seg-b { border: 0; background: transparent; padding: 6px 12px; border-radius: var(--r-sm); font-family: var(--font-mono); font-size: var(--text-xs); font-weight: var(--fw-medium); letter-spacing: 0.02em; color: var(--ink-mute); cursor: pointer; }
.seg-b.on { background: var(--ink); color: #fff; }
.load-fail { margin: var(--s-16) 0; padding: var(--s-20); border: 1px solid var(--risk-high-wash); background: var(--risk-high-wash); border-radius: var(--r-lg); display: flex; align-items: center; gap: var(--s-16); list-style: none; }
.load-fail-i { color: var(--risk-high); flex: none; }
.ba-list { display: flex; flex-direction: column; gap: var(--s-16); }
.ba { border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); padding: var(--s-24); }
.ba-head { display: flex; gap: var(--s-8); flex-wrap: wrap; margin-bottom: var(--s-16); }
.pattern-title { margin-bottom: var(--s-16); color: var(--ink); font-size: var(--text-lg); font-weight: var(--fw-semibold); }
.pattern-title span { display: block; margin-bottom: 4px; color: var(--ink-mute); font-family: var(--font-mono); font-size: 10.5px; font-weight: var(--fw-medium); letter-spacing: 0.08em; text-transform: uppercase; }
.reviewer-pattern { margin: 2px 0 0; }
.ba-grid { display: grid; grid-template-columns: 1fr auto 1fr; align-items: stretch; gap: var(--s-16); }
.ba-col { display: flex; flex-direction: column; gap: var(--s-8); padding: var(--s-16); border-radius: var(--r); }
.ba-col.before { background: var(--surface-2); }
.ba-col.after { background: var(--accent-wash); }
.support { margin-top: var(--s-12); font-size: var(--text-sm); }
.support summary { cursor: pointer; color: var(--accent); }
.ba-tag { font-size: 10.5px; letter-spacing: 0.1em; }
.ba-col.before .ba-tag { color: var(--ink-mute); }
.ba-col.after .ba-tag { color: var(--accent); }
.ba-text { font-size: var(--text-sm); color: var(--ink-soft); line-height: 1.6; }
.ba-label { font-size: var(--text-lg); font-weight: var(--fw-semibold); letter-spacing: -0.01em; }
.ba-place { font-size: var(--text-xs); color: var(--ink-mute); }
.ba-personas { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 6px; }
.ba-reco { margin-top: var(--s-10); font-size: var(--text-xs); color: var(--ink-soft); line-height: 1.55; }
.ba-arrow { display: flex; align-self: center; align-items: center; justify-content: center; color: var(--ink-faint); }
@media (max-width: 680px) { .ba-grid { grid-template-columns: 1fr; } .ba-arrow { justify-self: center; transform: rotate(90deg); } }
.list { list-style: none; border-top: 1px solid var(--line-strong); margin-top: var(--s-32); }
.row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-16); padding: var(--s-20) var(--s-4); border-bottom: 1px solid var(--line); }
.l { display: flex; flex-direction: column; gap: var(--s-8); min-width: 0; }
.l-top { display: flex; align-items: center; gap: var(--s-8); flex-wrap: wrap; }
.label { font-size: var(--text-lg); }
.meta { font-size: var(--text-xs); color: var(--ink-mute); }
.fin { font-size: 11px; color: var(--ink-faint); flex: none; }
.sk { display: flex; flex-direction: column; gap: 8px; padding: var(--s-20) var(--s-4); border-bottom: 1px solid var(--line); }
.form { display: flex; flex-direction: column; gap: var(--s-20); }
</style>
