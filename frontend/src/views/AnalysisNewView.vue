<script setup>
import { onMounted, ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowLeft, PhArrowClockwise, PhCheck, PhLightning, PhWarningCircle } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useToastStore } from '@/stores/toast'
import { useJobsStore } from '@/stores/jobs'
import { SCENARIO_OPTIONS } from '@/api/mock/scenarios'
import { RULE_LABEL } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GField from '@/components/ui/GField.vue'
import GSelect from '@/components/ui/GSelect.vue'
import GBadge from '@/components/ui/GBadge.vue'
import GSpinner from '@/components/ui/GSpinner.vue'

const props = defineProps({ productId: { type: String, required: true } })
const router = useRouter()
const toast = useToastStore()
const loading = ref(true)
const loadError = ref(null)
const submitting = ref(false)
const docs = ref([]); const evidence = ref([]); const personas = ref([]); const packs = ref([]); const productName = ref('')
const facts = ref([])
const selDoc = ref(''); const selEv = ref([]); const selPer = ref([]); const selPack = ref('')
const scenario = ref('GUARANTEE_MISUNDERSTANDING_HIGH')
const localAi = ref((() => { try { return localStorage.getItem('guardlab.ai.local') === '1' } catch { return false } })())
const debugControls = import.meta.env.VITE_DEBUG_AI_CONTROLS === 'true'
function setLocalAi(v) { localAi.value = v; try { localStorage.setItem('guardlab.ai.local', v ? '1' : '0') } catch {} }
const evOk = computed(() => selEv.value.length >= 1 && selEv.value.length <= 3)
const perOk = computed(() => selPer.value.length >= 1 && selPer.value.length <= 4)
const factsVerified = computed(() => facts.value.some((fact) => fact.verificationStatus === 'VERIFIED'))
const canSubmit = computed(() => selDoc.value && evOk.value && perOk.value && selPack.value && factsVerified.value && !submitting.value)

async function loadFacts() {
  facts.value = selDoc.value ? (await api.listGroundTruthFacts(selDoc.value)).items : []
}

onMounted(load)
async function load() {
  loading.value = true
  try {
    const [product, ev, ps, pk] = await Promise.all([api.getProduct(props.productId), api.listEvidenceDocuments({ active: true }), api.listPersonaTemplates(), api.listRedTeamPacks()])
    productName.value = product.name
    docs.value = (product.documents || []).filter((d) => d.extractStatus === 'READY' && d.confirmed)
    evidence.value = ev.items; personas.value = ps.items; packs.value = pk.items
    if (docs.value.length) selDoc.value = docs.value[0].documentId
    if (evidence.value.length) selEv.value = evidence.value.slice(0, 2).map((item) => item.documentId)
    if (personas.value.length) selPer.value = personas.value.slice(0, 4).map((p) => p.personaId)
    if (packs.value.length) selPack.value = packs.value[0].redTeamPackId
    await loadFacts()
    loadError.value = null
  } catch (e) {
    loadError.value = e
    toast.fromError(e)
  } finally {
    loading.value = false
  }
}
watch(selDoc, () => loadFacts().catch((error) => toast.fromError(error)))
async function verifyFact(fact, verificationStatus) {
  try {
    await api.verifyGroundTruthFact(fact.factId, { verificationStatus, value: fact.value })
    await loadFacts()
  } catch (error) { toast.fromError(error) }
}
// 템플릿에서 넘어오는 list는 이미 언랩된 반응형 배열이므로 .value가 아니라 배열을 직접 변형한다.
function toggle(list, id, max) { const i = list.indexOf(id); if (i >= 0) list.splice(i, 1); else if (list.length < max) list.push(id); else toast.info('선택 한도', `최대 ${max}개`) }
async function submit() {
  if (!canSubmit.value) return; submitting.value = true
  try {
    const res = await api.createAnalysis({ productDocumentId: selDoc.value, evidenceDocumentIds: selEv.value, personaIds: selPer.value, redTeamPackId: selPack.value }, debugControls ? scenario.value : undefined)
    toast.success('분석 요청됨', res.analysisId)
    useJobsStore().track({ kind: 'analysis', id: res.analysisId, name: productName.value || res.analysisId, productId: props.productId })
    router.push(`/analyses/${res.analysisId}`)
  } catch (e) {
    if (e?.errorCode === 'DUPLICATE_ANALYSIS_INPUT' && e.existingAnalysisId) router.push(`/analyses/${e.existingAnalysisId}`)
    else toast.fromError(e)
  } finally { submitting.value = false }
}
</script>

<template>
  <div class="wrap rise">
    <button class="back" @click="router.push(`/products/${productId}`)"><PhArrowLeft :size="15" /> 상품 상세</button>
    <header class="top"><h1 class="d-h1">분석 요청</h1><p class="t-base soft sub">확정 문서와 승인 근거, Persona, Red Team Pack을 묶어 분석을 시작합니다.</p></header>

    <div v-if="loading" class="pad"><GSpinner :size="24" /></div>

    <div v-else-if="loadError" class="load-fail" role="alert">
      <PhWarningCircle :size="22" class="load-fail-i" />
      <div class="grow">
        <p class="t-base fw-semibold">분석 요청 정보를 불러오지 못했습니다</p>
        <p class="t-sm soft">{{ loadError.message || '잠시 후 다시 시도해 주세요.' }}</p>
        <p v-if="loadError.errorCode" class="mono error-code">{{ loadError.errorCode }}</p>
      </div>
      <GButton variant="secondary" size="sm" @click="load">
        <template #icon><PhArrowClockwise :size="15" /></template>다시 시도
      </GButton>
    </div>

    <div v-else-if="!docs.length" class="empty-doc">
      <p class="t-base">확정된 문서가 없습니다. 문서 추출 텍스트를 먼저 확정하세요.</p>
      <GButton variant="secondary" @click="router.push(`/products/${productId}`)">상품으로 이동</GButton>
    </div>

    <template v-else>
      <section class="blk">
        <div class="bh"><span class="mono n">01</span><h2 class="d-h3">분석 대상 문서</h2></div>
        <div class="opts">
          <label v-for="d in docs" :key="d.documentId" class="opt" :class="{ on: selDoc === d.documentId }">
            <input type="radio" name="doc" :value="d.documentId" v-model="selDoc" class="sr-only" />
            <span class="ck round"><PhCheck v-if="selDoc === d.documentId" :size="12" weight="bold" /></span>
            <span class="opt-m"><span class="fw-medium">{{ d.fileName }}</span><span class="mono meta">{{ d.documentId }}</span></span>
          </label>
        </div>
      </section>

      <section class="blk">
        <div class="bh"><span class="mono n">02</span><h2 class="d-h3">승인 근거</h2><GBadge class="bcount" :tone="evOk ? 'ok' : 'high'">{{ selEv.length }}/1-3</GBadge></div>
        <p class="note t-sm mute">법령, 내부준칙 텍스트는 발표용 합성 데이터입니다.</p>
        <div class="opts">
          <label v-for="e in evidence" :key="e.documentId" class="opt" :class="{ on: selEv.includes(e.documentId) }">
            <input type="checkbox" class="sr-only" :checked="selEv.includes(e.documentId)" @change="toggle(selEv, e.documentId, 3)" />
            <span class="ck"><PhCheck v-if="selEv.includes(e.documentId)" :size="12" weight="bold" /></span>
            <span class="opt-m"><span class="fw-medium">{{ e.title }}</span><span class="mono meta">{{ e.sourceType }} · {{ e.version }}</span></span>
          </label>
        </div>
      </section>

      <section class="blk">
        <div class="bh"><span class="mono n">03</span><h2 class="d-h3">공식 상품 사실 확인</h2><GBadge class="bcount" :tone="factsVerified ? 'ok' : 'high'">{{ factsVerified ? 'VERIFIED' : '확인 필요' }}</GBadge></div>
        <div class="opts">
          <div v-for="fact in facts" :key="fact.factId" class="opt fact">
            <span class="opt-m"><span class="fw-medium">{{ fact.label }}</span><span class="t-xs mute">{{ fact.value }} · {{ fact.importance }} · {{ fact.extractionSource || 'API' }}</span></span>
            <GBadge tone="neutral">{{ fact.verificationStatus }}</GBadge>
            <GButton v-if="fact.verificationStatus === 'CANDIDATE'" size="sm" variant="secondary" @click="verifyFact(fact, 'REJECTED')">제외</GButton>
            <GButton v-if="fact.verificationStatus !== 'VERIFIED'" size="sm" variant="primary" @click="verifyFact(fact, 'VERIFIED')">확인</GButton>
          </div>
        </div>
      </section>

      <section class="blk">
        <div class="bh"><span class="mono n">04</span><h2 class="d-h3">분석 대상 Persona</h2><GBadge class="bcount" :tone="perOk ? 'ok' : 'high'">{{ selPer.length }}/1-4</GBadge></div>
        <div class="chips">
          <label v-for="p in personas" :key="p.personaId" class="chip" :class="{ on: selPer.includes(p.personaId) }">
            <input type="checkbox" class="sr-only" :checked="selPer.includes(p.personaId)" @change="toggle(selPer, p.personaId, 4)" />
            <span class="fw-medium">{{ p.name }}</span><span class="t-xs mute">{{ Array.isArray(p.riskFocus) ? p.riskFocus.join(' · ') : p.riskFocus }}</span><span v-if="p.criteria" class="t-xs mute">{{ Object.values(p.criteria).join(' · ') }}</span><span v-if="p.questionSummary" class="t-xs mute">{{ p.questionSummary }}</span>
          </label>
        </div>
      </section>

      <section class="blk">
        <div class="bh"><span class="mono n">05</span><h2 class="d-h3">Red Team Pack</h2></div>
        <div class="opts">
          <label v-for="pk in packs" :key="pk.redTeamPackId" class="opt col" :class="{ on: selPack === pk.redTeamPackId }">
            <div class="opt-top"><span class="ck round"><PhCheck v-if="selPack === pk.redTeamPackId" :size="12" weight="bold" /></span><span class="fw-medium">{{ pk.name }}</span></div>
            <input type="radio" name="pack" :value="pk.redTeamPackId" v-model="selPack" class="sr-only" />
            <div class="rules"><GBadge v-for="rule in pk.rules" :key="rule.ruleCode" tone="neutral">{{ RULE_LABEL[rule.ruleCode] || rule.ruleCode }}</GBadge></div>
          </label>
        </div>
      </section>

      <section v-if="debugControls" class="blk">
        <div class="bh"><span class="mono n">05</span><h2 class="d-h3">데모 시나리오</h2></div>
        <GField hint="demo profile 전용. AI Mock 결과를 강제 선택합니다 (X-Demo-Scenario)."><GSelect v-model="scenario" :options="SCENARIO_OPTIONS" /></GField>
      </section>

      <section v-if="debugControls" class="blk">
        <div class="bh"><span class="mono n">06</span><h2 class="d-h3">분석 엔진</h2></div>
        <label class="engine" :class="{ on: localAi }">
          <input type="checkbox" :checked="localAi" @change="setLocalAi($event.target.checked)" />
          <span class="opt-m"><span class="fw-medium">로컬 AI로 분석 (Ollama · qwen2.5 + RAG)</span><span class="t-xs mute">실제 로컬 LLM으로 분석합니다. 끄면 데모 Mock 결과(score 82). Ollama 실행 필요.</span></span>
        </label>
      </section>

      <div class="submit"><span class="t-sm mute">동일 조건 3회 자동 실행</span><GButton variant="primary" :loading="submitting" :disabled="!canSubmit" @click="submit"><template #icon><PhLightning :size="16" /></template>Persona + Red Team 분석 시작</GButton></div>
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 780px; }
.back { display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--s-16); }
.back:hover { color: var(--ink); }
.top .kicker { margin-bottom: var(--s-10); } .sub { margin-top: var(--s-12); max-width: 56ch; }
.pad { display: grid; place-items: center; padding: var(--s-64); }
.load-fail { margin-top: var(--s-40); padding: var(--s-24); border: 1px solid var(--risk-high-wash); background: var(--risk-high-wash); border-radius: var(--r-lg); display: flex; align-items: center; gap: var(--s-16); }
.load-fail-i { color: var(--risk-high); flex: none; }
.error-code { margin-top: 4px; color: var(--ink-mute); font-size: var(--text-xs); }
.empty-doc { margin-top: var(--s-40); padding: var(--s-28); border: 1px solid var(--line); border-radius: var(--r-lg); display: flex; flex-direction: column; align-items: flex-start; gap: var(--s-16); }
.blk { margin-top: var(--s-40); }
.engine { display: flex; gap: var(--s-12); align-items: flex-start; margin-top: var(--s-16); padding: var(--s-16); border: 1px solid var(--line); border-radius: var(--r); cursor: pointer; }
.engine.on { border-color: var(--accent-line); background: var(--accent-wash); }
.engine input { margin-top: 3px; }
.bh { display: flex; align-items: center; gap: var(--s-12); padding-bottom: var(--s-12); border-bottom: 1px solid var(--line-strong); }
.bh .n { font-size: var(--text-xs); color: var(--ink-faint); }
.bh h2 { flex: 1; }
.bcount { flex: none; }
.note { margin-top: var(--s-12); }
.opts { display: flex; flex-direction: column; margin-top: var(--s-8); }
.opt { display: flex; align-items: center; gap: var(--s-12); padding: var(--s-14, 14px) var(--s-10); border-bottom: 1px solid var(--line); cursor: pointer; transition: background var(--fast) var(--ease); }
.opt:hover { background: var(--surface-2); }
.opt.on { background: var(--accent-wash); }
.opt.col { flex-direction: column; align-items: stretch; gap: var(--s-12); }
.opt.fact { cursor: default; }
.opt-top { display: flex; align-items: center; gap: var(--s-12); }
.ck { display: grid; place-items: center; width: 20px; height: 20px; border: 1px solid var(--line-strong); border-radius: var(--r-xs); color: #fff; flex: none; transition: all var(--fast) var(--ease); }
.ck.round { border-radius: var(--r-pill); }
.opt.on .ck { background: var(--accent); border-color: var(--accent); }
.opt-m { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.meta { font-size: var(--text-xs); color: var(--ink-mute); }
.rules { display: flex; flex-wrap: wrap; gap: 5px; padding-left: 32px; }
.chips { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: var(--s-8); margin-top: var(--s-16); }
.chip { display: flex; flex-direction: column; gap: 3px; padding: var(--s-12) var(--s-14, 14px); border: 1px solid var(--line-strong); border-radius: var(--r-sm); cursor: pointer; transition: all var(--fast) var(--ease); }
.chip:hover { border-color: var(--ink-faint); }
.chip.on { border-color: var(--accent); background: var(--accent-wash); }
.submit { display: flex; align-items: center; justify-content: space-between; gap: var(--s-16); margin-top: var(--s-32); }
</style>
