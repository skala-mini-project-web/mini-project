<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { PhShieldCheck, PhPencilSimple, PhSealCheck, PhArrowRight } from '@phosphor-icons/vue'
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

const session = useSessionStore()
const toast = useToastStore()
const loading = ref(true)
const items = ref([])
const showEdit = ref(false)
const editing = ref(null)
const saving = ref(null)
const form = reactive({ label: '', placement: '', required: 'true' })
const requiredOptions = [{ value: 'true', label: '필수' }, { value: 'false', label: '권장' }]
const approved = computed(() => items.value.filter((a) => a.status === 'APPROVED'))

onMounted(load)
async function load() { loading.value = true; try { items.value = (await api.listGuardFitActions({})).items } catch (e) { toast.fromError(e) } finally { loading.value = false } }
function openEdit(a) { editing.value = a; form.label = a.label; form.placement = a.placement; form.required = a.required ? 'true' : 'false'; showEdit.value = true }
async function save(status) {
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
      <div v-if="loading" class="ba-list"><GSkeleton v-for="i in 3" :key="i" h="128px" radius="var(--r-lg)" /></div>
      <GEmptyState v-else-if="!approved.length" title="승인된 보호조치가 없습니다" description="검토자가 승인하면 여기에서 조회할 수 있습니다."><template #icon><PhShieldCheck :size="20" /></template></GEmptyState>
      <div v-else class="ba-list">
        <article v-for="a in approved" :key="a.actionId" class="ba">
          <div class="ba-head">
            <GSeverityBadge v-if="a.pattern" :severity="a.pattern.severity" />
            <GBadge tone="accent">{{ ACTION_TYPE_LABEL[a.actionType] || a.actionType }}</GBadge>
            <GBadge v-if="a.pattern" tone="neutral">{{ a.pattern.ruleCode }}</GBadge>
            <GBadge :tone="a.required ? 'high' : 'neutral'" :mono="false">{{ a.required ? '필수' : '권장' }}</GBadge>
          </div>
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
      <div v-if="loading" class="list"><div v-for="i in 3" :key="i" class="sk"><GSkeleton h="18px" w="40%" /><GSkeleton h="13px" w="25%" /></div></div>
      <GEmptyState v-else-if="!items.length" title="보호조치가 없습니다" description="Risk Library에서 활성 패턴을 골라 후보를 만드세요."><template #icon><PhShieldCheck :size="20" /></template></GEmptyState>
      <ul v-else class="list">
        <li v-for="a in items" :key="a.actionId" class="row">
          <div class="l">
            <div class="l-top"><GBadge tone="accent">{{ ACTION_TYPE_LABEL[a.actionType] || a.actionType }}</GBadge><GBadge :tone="a.required ? 'high' : 'neutral'" :mono="false">{{ a.required ? '필수' : '권장' }}</GBadge><GStatusPill :status="a.status" /></div>
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
        <GField label="라벨 문구" required for-id="el"><GTextInput id="el" v-model="form.label" /></GField>
        <GField label="배치 위치" required for-id="ep"><GTextInput id="ep" v-model="form.placement" /></GField>
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
.ba-list { display: flex; flex-direction: column; gap: var(--s-16); }
.ba { border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); padding: var(--s-24); }
.ba-head { display: flex; gap: var(--s-8); flex-wrap: wrap; margin-bottom: var(--s-16); }
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
