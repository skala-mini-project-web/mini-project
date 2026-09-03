<script setup>
import { onMounted, ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { PhStack, PhShieldCheck } from '@phosphor-icons/vue'
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

const router = useRouter()
const toast = useToastStore()
const loading = ref(true)
const items = ref([])
const severity = ref('')
const submitting = ref(false)
const showCreate = ref(false)
const active = ref(null)
const form = reactive({ actionType: 'WARNING', label: '', placement: '', required: 'true' })
const actionTypeOptions = Object.entries(ACTION_TYPE_LABEL).map(([value, label]) => ({ value, label: `${label} · ${value}` }))
const requiredOptions = [{ value: 'true', label: '필수' }, { value: 'false', label: '권장' }]
const sevFilters = [{ v: '', l: 'ALL' }, { v: 'HIGH', l: 'HIGH' }, { v: 'MEDIUM', l: 'MED' }, { v: 'LOW', l: 'LOW' }]

onMounted(load)
async function load() { loading.value = true; try { items.value = (await api.listRiskPatterns(severity.value ? { severity: severity.value } : {})).items } catch (e) { toast.fromError(e) } finally { loading.value = false } }
function openCreate(p) {
  const suggestion = p.guardFitSuggestion
  active.value = p
  form.actionType = suggestion?.actionType || 'WARNING'
  form.label = suggestion?.label || ''
  form.placement = suggestion?.placement || ''
  form.required = suggestion?.required === false ? 'false' : 'true'
  showCreate.value = true
}
async function submit() {
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

    <div v-if="loading" class="list"><div v-for="i in 3" :key="i" class="sk"><GSkeleton h="18px" w="30%" /><GSkeleton h="13px" w="45%" /></div></div>
    <GEmptyState v-else-if="!items.length" title="위험 패턴이 없습니다" description="검토에서 Finding을 승인하면 패턴으로 승격됩니다."><template #icon><PhStack :size="20" /></template></GEmptyState>
    <ul v-else class="list">
      <li v-for="rp in items" :key="rp.riskPatternId" class="row">
        <div class="l">
          <div class="l-top"><GSeverityBadge :severity="rp.severity" /><span class="d-h3 name">{{ rp.name }}</span></div>
          <div class="tags"><GBadge tone="neutral">{{ RULE_LABEL[rp.ruleCode] || rp.ruleCode }}</GBadge><GBadge v-for="pc in rp.affectedPersonaCodes" :key="pc" tone="neutral" :mono="false">{{ personaName(pc) }}</GBadge></div>
          <span class="mono trace">{{ rp.sourceFindingId }} · {{ rp.sourceReviewId }} · {{ rp.sourceAnalysisId || '-' }} · {{ rp.riskPatternId }}</span>
        </div>
        <div class="r">
          <GStatusPill :status="rp.status" />
          <GButton variant="secondary" size="sm" :disabled="rp.status !== 'ACTIVE'" @click="openCreate(rp)"><template #icon><PhShieldCheck :size="15" /></template>GuardFit</GButton>
        </div>
      </li>
    </ul>

    <GModal v-if="showCreate" :title="`GuardFit 후보 · ${active?.name}`" @close="showCreate = false">
      <form class="form" @submit.prevent="submit">
        <GField label="조치 유형" for-id="at"><GSelect id="at" v-model="form.actionType" :options="actionTypeOptions" /></GField>
        <GField label="라벨 문구" required for-id="al" hint="소비자에게 노출될 문구"><GTextInput id="al" v-model="form.label" placeholder="예: 원금 손실 가능" /></GField>
        <GField label="배치 위치" required for-id="ap"><GTextInput id="ap" v-model="form.placement" placeholder="예: 상품 상세 상단" /></GField>
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
.list { list-style: none; border-top: 1px solid var(--line-strong); }
.row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-20); padding: var(--s-24) var(--s-4); border-bottom: 1px solid var(--line); }
.l { display: flex; flex-direction: column; gap: var(--s-10); min-width: 0; }
.l-top { display: flex; align-items: center; gap: var(--s-10); }
.name { letter-spacing: -0.01em; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.trace { font-size: 11px; color: var(--ink-faint); }
.r { display: flex; align-items: center; gap: var(--s-12); flex: none; }
.sk { display: flex; flex-direction: column; gap: 8px; padding: var(--s-24) var(--s-4); border-bottom: 1px solid var(--line); }
.form { display: flex; flex-direction: column; gap: var(--s-20); }
@media (max-width: 680px) { .row { flex-direction: column; align-items: flex-start; } }
</style>
