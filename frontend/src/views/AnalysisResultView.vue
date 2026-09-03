<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowLeft, PhArrowClockwise, PhWarningCircle, PhClipboardText, PhCheckCircle, PhXCircle, PhFileText, PhScales } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useToastStore } from '@/stores/toast'
import { useJobsStore } from '@/stores/jobs'
import { usePolling } from '@/composables/usePolling'
import { FINDING_TYPE_LABEL, RULE_LABEL, personaName, formatDateTime } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GSeverityBadge from '@/components/ui/GSeverityBadge.vue'
import GBadge from '@/components/ui/GBadge.vue'
import GProgress from '@/components/ui/GProgress.vue'
import GSpinner from '@/components/ui/GSpinner.vue'

const props = defineProps({ analysisId: { type: String, required: true } })
const router = useRouter()
const session = useSessionStore()
const toast = useToastStore()

const status = ref(null)
const result = ref(null)
const loading = ref(true)
const retrying = ref(false)
const requesting = ref(false)
const reviewRequested = ref(false)
const reviewInfo = ref(null)

const isRunning = computed(() => ['CREATED', 'RUNNING'].includes(status.value?.status))
const isFailed = computed(() => status.value?.status === 'FAILED')
const isDone = computed(() => status.value?.status === 'COMPLETED')
const band = computed(() => {
  const s = result.value?.riskScore ?? 0
  if (s >= 70) return { tone: 'high', label: '고위험' }
  if (s >= 40) return { tone: 'med', label: '중위험' }
  if (s > 0) return { tone: 'low', label: '저위험' }
  return { tone: 'ok', label: '위험 없음' }
})

const { polling, timedOut, start } = usePolling(() => api.getAnalysis(props.analysisId), {
  onResult: async (res, err) => { if (err) return toast.fromError(err); status.value = res; if (res.status === 'COMPLETED') await loadResult() },
})

onMounted(async () => {
  try {
    status.value = await api.getAnalysis(props.analysisId)
    if (isRunning.value) start((r) => ['COMPLETED', 'FAILED'].includes(r.status))
    else if (isDone.value) await loadResult()
  } catch (e) { toast.fromError(e) } finally { loading.value = false }
})
async function loadResult() { try { result.value = await api.getAnalysisResult(props.analysisId); reviewInfo.value = await api.getReviewByAnalysis(props.analysisId).catch(() => null) } catch (e) { toast.fromError(e) } }
async function retry() {
  retrying.value = true
  try { await api.retryAnalysis(props.analysisId); toast.info('분석 재시도'); status.value = await api.getAnalysis(props.analysisId); start((r) => ['COMPLETED', 'FAILED'].includes(r.status)) }
  catch (e) { toast.fromError(e) } finally { retrying.value = false }
}
async function requestReview() {
  requesting.value = true
  try {
    const res = await api.createReview({ analysisId: props.analysisId, submissionComment: '검토 요청' })
    reviewRequested.value = true
    reviewInfo.value = { status: 'PENDING' }
    toast.success('검토 요청됨', res.reviewId)
    useJobsStore().track({ kind: 'review', id: res.reviewId, name: result.value?.sourceDocument?.fileName || props.analysisId })
  } catch (e) {
    if (e?.errorCode === 'REVIEW_ALREADY_EXISTS') reviewRequested.value = true
    toast.fromError(e)
  } finally {
    requesting.value = false
  }
}
const idx = (i) => 'F.' + String(i + 1).padStart(2, '0')
</script>

<template>
  <div class="wrap rise">
    <button class="back" @click="router.back()"><PhArrowLeft :size="15" /> 뒤로</button>

    <header class="top">
      <div>
        <h1 class="d-h1 mono aid">{{ analysisId }}</h1>
      </div>
      <GStatusPill v-if="status" :status="status.status" />
    </header>

    <div v-if="loading" class="pad"><GSpinner :size="24" /></div>

    <!-- Running -->
    <div v-else-if="isRunning" class="run">
      <div class="run-head">
        <GSpinner :size="20" />
        <span class="t-base fw-medium">AI가 표현 리스크를 분석하고 있습니다</span>
        <span class="mono run-pct">{{ String(status.progress ?? 0).padStart(2, '0') }}%</span>
      </div>
      <GProgress :value="status.progress ?? 0" tone="neutral" />
      <p class="t-sm mute run-note">{{ timedOut ? '자동 확인을 멈췄습니다.' : polling ? '상태를 1초 간격으로 확인합니다.' : '' }}</p>
      <GButton v-if="timedOut" variant="secondary" size="sm" @click="start((r) => ['COMPLETED','FAILED'].includes(r.status))">상태 다시 확인</GButton>
    </div>

    <!-- Failed -->
    <div v-else-if="isFailed" class="fail">
      <PhWarningCircle :size="22" class="fail-i" />
      <div class="grow">
        <p class="t-base fw-semibold">분석 실패</p>
        <p class="t-sm soft"><span class="mono">{{ status.error?.errorCode }}</span> · {{ status.error?.message }}</p>
      </div>
      <GButton v-if="status.error?.retryable" variant="secondary" size="sm" :loading="retrying" @click="retry">
        <template #icon><PhArrowClockwise :size="15" /></template>재시도
      </GButton>
    </div>

    <!-- Completed -->
    <template v-else-if="isDone && result">
      <div class="score" :class="`b-${band.tone}`">
        <div class="score-num">
          <span class="mono sn">{{ result.riskScore }}</span>
          <span class="mono su">/100</span>
        </div>
        <div class="score-mid">
          <span class="band" :class="`tone-${band.tone}`">{{ band.label }}</span>
          <GProgress class="score-bar" :value="result.riskScore" :tone="band.tone" />
          <div class="score-src">
            <span class="t-sm soft"><PhFileText :size="14" /> {{ result.sourceDocument?.fileName }}</span>
            <span class="t-sm mute"><PhScales :size="14" /> 근거 {{ result.groundingDocuments?.length || 0 }}건</span>
          </div>
        </div>
        <div v-if="session.isPM" class="score-act">
          <template v-if="!reviewInfo">
            <p class="t-xs mute act-note">사람 승인 전까지 승격되지 않습니다.</p>
            <GButton variant="primary" :loading="requesting" @click="requestReview"><template #icon><PhClipboardText :size="15" /></template>검토 요청</GButton>
          </template>
          <span v-else-if="reviewInfo.status === 'PENDING'" class="requested" style="color:var(--ink-mute)"><PhClipboardText :size="16" /> 검토 대기 중</span>
          <span v-else-if="reviewInfo.decision === 'APPROVED'" class="requested"><PhCheckCircle :size="16" weight="fill" /> 승인됨</span>
          <span v-else class="requested" style="color:var(--risk-high)"><PhXCircle :size="16" weight="fill" /> 반려됨</span>
        </div>
      </div>

      <div v-if="reviewInfo && reviewInfo.status && reviewInfo.status !== 'PENDING'" class="rev-result" :class="reviewInfo.decision === 'APPROVED' ? 'ok' : 'rej'">
        <div class="rr-head">
          <component :is="reviewInfo.decision === 'APPROVED' ? PhCheckCircle : PhXCircle" :size="18" weight="fill" class="rr-i" />
          <span class="fw-semibold">{{ reviewInfo.decision === 'APPROVED' ? '검토 승인' : '검토 반려 · 수정 필요' }}</span>
          <span class="mono mute rr-meta">{{ reviewInfo.reviewerId }} · {{ formatDateTime(reviewInfo.decidedAt) }}</span>
        </div>
        <p v-if="reviewInfo.comment" class="rr-comment">{{ reviewInfo.comment }}</p>
        <p v-if="reviewInfo.decision === 'APPROVED' && reviewInfo.riskPatternIds?.length" class="t-xs mute mono">승격된 패턴: {{ reviewInfo.riskPatternIds.join(', ') }}</p>
      </div>

      <section v-if="result.groundingDocuments?.length" class="grounding">
        <div class="fh"><h2 class="d-h3">검색된 근거 (RAG)</h2><span class="mono mute">{{ result.groundingDocuments.length }}건</span></div>
        <ul class="glist">
          <li v-for="g in result.groundingDocuments" :key="g.documentId" class="grow">
            <span class="mono gt">{{ g.sourceType || '근거' }}</span>
            <span class="gtitle">{{ g.title }}</span>
            <span class="mono gid">{{ g.documentId }}</span>
            <span v-if="g.score != null" class="mono gscore">유사도 {{ g.score }}</span>
          </li>
        </ul>
      </section>

      <div class="fh">
        <h2 class="d-h3">Finding</h2>
        <span class="mono mute">{{ result.findings.length }} 건 탐지</span>
      </div>

      <div v-if="!result.findings.length" class="nofind"><PhCheckCircle :size="18" weight="fill" /> 탐지된 위험 표현이 없습니다.</div>

      <ol v-else class="finds">
        <li v-for="(f, i) in result.findings" :key="f.findingId" class="find">
          <div class="gutter" :class="`tone-${f.severity === 'HIGH' ? 'high' : f.severity === 'MEDIUM' ? 'med' : 'low'}`">
            <span class="bar" />
            <span class="mono fidx">{{ idx(i) }}</span>
          </div>
          <div class="fbody">
            <div class="frow">
              <GSeverityBadge :severity="f.severity" />
              <GBadge tone="neutral">{{ FINDING_TYPE_LABEL[f.findingType] || f.findingType }}</GBadge>
              <GBadge tone="neutral">{{ RULE_LABEL[f.ruleCode] || f.ruleCode }}</GBadge>
              <span class="mono fid">{{ f.findingId }}</span>
            </div>
            <p class="t-lg fmsg">{{ f.message }}</p>

            <blockquote class="quote">
              <span class="mono qp">p.{{ f.sourceReference.page ?? '-' }}</span>{{ f.sourceReference.excerpt }}
            </blockquote>

            <div class="fmeta">
              <div class="fcol">
                <span class="mono ml">영향 PERSONA</span>
                <div class="chips"><GBadge v-for="pc in f.affectedPersonaCodes" :key="pc" tone="neutral" :mono="false">{{ personaName(pc) }}</GBadge></div>
              </div>
              <div class="fcol">
                <span class="mono ml">근거 문서</span>
                <p v-for="(ev, j) in f.evidenceReferences" :key="j" class="t-sm ev"><span class="mono evt">{{ ev.sourceType }}</span> {{ ev.excerpt }}</p>
                <p v-if="!f.evidenceReferences?.length" class="t-sm mute">근거 없음</p>
              </div>
            </div>

            <p class="reco"><span class="mono ml">권고</span>{{ f.recommendation }}</p>
          </div>
        </li>
      </ol>
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 940px; }
.back { display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--s-16); }
.back:hover { color: var(--ink); }
.top { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-16); }
.aid { margin-top: var(--s-10); letter-spacing: -0.01em; }
.pad { display: grid; place-items: center; padding: var(--s-64); }

.run { margin-top: var(--s-40); padding: var(--s-28); border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); display: flex; flex-direction: column; gap: var(--s-16); }
.run-head { display: flex; align-items: center; gap: var(--s-12); }
.run-pct { margin-left: auto; font-size: var(--text-h3); color: var(--ink-mute); }
.run-note { margin-top: -6px; }

.fail { margin-top: var(--s-40); padding: var(--s-24); border: 1px solid var(--risk-high-wash); background: var(--risk-high-wash); border-radius: var(--r-lg); display: flex; align-items: center; gap: var(--s-16); }
.fail-i { color: var(--risk-high); flex: none; }

/* Score hero */
.score { margin-top: var(--s-40); display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--s-40); padding: var(--s-32); border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); }
.score.b-high { box-shadow: inset 3px 0 0 var(--risk-high); }
.score.b-med { box-shadow: inset 3px 0 0 var(--risk-med); }
.score.b-low { box-shadow: inset 3px 0 0 var(--risk-low); }
.score.b-ok { box-shadow: inset 3px 0 0 var(--ok); }
.score-num { display: flex; align-items: baseline; gap: 4px; }
.sn { font-size: 76px; font-weight: var(--fw-semibold); line-height: 0.9; letter-spacing: -0.05em; }
.b-high .sn { color: var(--risk-high); } .b-med .sn { color: var(--risk-med); } .b-low .sn { color: var(--ink); } .b-ok .sn { color: var(--ok); }
.su { font-size: var(--text-lg); color: var(--ink-faint); }
.score-mid { display: flex; flex-direction: column; gap: var(--s-12); min-width: 0; }
.band { align-self: flex-start; color: var(--fg); background: var(--bg); font-size: var(--text-xs); font-weight: var(--fw-semibold); padding: 4px 9px; border-radius: var(--r-xs); }
.score-src { display: flex; gap: var(--s-20); flex-wrap: wrap; }
.score-src span { display: inline-flex; align-items: center; gap: 6px; }
.score-act { display: flex; flex-direction: column; align-items: flex-end; gap: var(--s-8); flex: none; }
.act-note { max-width: 18ch; text-align: right; }
.requested { display: inline-flex; align-items: center; gap: 6px; color: var(--ok); font-weight: var(--fw-semibold); font-size: var(--text-sm); }

.fh { display: flex; align-items: baseline; justify-content: space-between; margin-top: var(--s-48); padding-bottom: var(--s-16); border-bottom: 1px solid var(--line-strong); }
.nofind { margin-top: var(--s-24); display: inline-flex; align-items: center; gap: 8px; color: var(--ok); }

.finds { list-style: none; }
.find { display: grid; grid-template-columns: 64px 1fr; gap: var(--s-20); padding: var(--s-28) 0; border-bottom: 1px solid var(--line); }
.gutter { display: flex; flex-direction: column; align-items: flex-start; gap: var(--s-10); }
.gutter .bar { width: 100%; max-width: 40px; height: 3px; background: var(--fg); border-radius: 2px; }
.fidx { font-size: var(--text-xs); color: var(--ink-mute); }
.fbody { min-width: 0; }
.frow { display: flex; align-items: center; gap: var(--s-8); flex-wrap: wrap; }
.fid { margin-left: auto; font-size: 11px; color: var(--ink-faint); }
.fmsg { margin-top: var(--s-12); letter-spacing: -0.01em; }
.quote { margin-top: var(--s-16); padding: var(--s-12) var(--s-16); background: var(--surface-2); border-radius: var(--r-sm); line-height: 1.6; }
.qp { color: var(--ink-mute); margin-right: 8px; font-size: var(--text-xs); }
.rev-result { margin-top: var(--s-16); padding: var(--s-16) var(--s-20); border-radius: var(--r-lg); border: 1px solid var(--line); display: flex; flex-direction: column; gap: var(--s-8); }
.rev-result.rej { background: var(--risk-high-wash); border-color: transparent; }
.rev-result.ok { background: var(--surface-2); }
.rr-head { display: flex; align-items: center; gap: var(--s-10); }
.rr-i { flex: none; } .rev-result.rej .rr-i { color: var(--risk-high); } .rev-result.ok .rr-i { color: var(--ok); }
.rr-meta { margin-left: auto; font-size: var(--text-xs); }
.rr-comment { color: var(--ink); line-height: 1.6; }
.grounding { margin-top: var(--s-32); }
.glist { list-style: none; margin-top: var(--s-12); border-top: 1px solid var(--line); }
.grow { display: flex; align-items: center; gap: var(--s-12); padding: var(--s-12) var(--s-8); border-bottom: 1px solid var(--line); }
.gt { font-size: 10.5px; letter-spacing: 0.08em; color: var(--ink-mute); border: 1px solid var(--line-strong); border-radius: var(--r-xs); padding: 2px 6px; flex: none; }
.gtitle { font-weight: var(--fw-medium); min-width: 0; }
.gid { font-size: var(--text-xs); color: var(--ink-faint); }
.gscore { margin-left: auto; font-size: var(--text-xs); color: var(--accent); flex: none; }
.fmeta { display: grid; grid-template-columns: 1fr 1.4fr; gap: var(--s-28); margin-top: var(--s-20); }
.fcol { display: flex; flex-direction: column; gap: var(--s-8); }
.ml { font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-mute); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.ev { line-height: 1.55; } .evt { color: var(--ink-mute); margin-right: 5px; font-size: 11px; }
.reco { margin-top: var(--s-20); display: flex; flex-direction: column; gap: 6px; color: var(--ink-2); font-size: var(--text-sm); line-height: 1.6; }
@media (max-width: 760px) { .score { grid-template-columns: 1fr; gap: var(--s-20); } .score-act { align-items: flex-start; } .act-note { text-align: left; } .fmeta { grid-template-columns: 1fr; } }
</style>
