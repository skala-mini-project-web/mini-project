<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowLeft, PhArrowClockwise, PhCheck, PhCheckCircle, PhWarningCircle, PhXCircle, PhSealCheck } from '@phosphor-icons/vue'
import { api } from '@/api'
import { useToastStore } from '@/stores/toast'
import { FINDING_TYPE_LABEL, RULE_LABEL, personaName, formatDateTime } from '@/lib/format'
import GButton from '@/components/ui/GButton.vue'
import GStatusPill from '@/components/ui/GStatusPill.vue'
import GSeverityBadge from '@/components/ui/GSeverityBadge.vue'
import GBadge from '@/components/ui/GBadge.vue'
import GField from '@/components/ui/GField.vue'
import GTextarea from '@/components/ui/GTextarea.vue'
import GSpinner from '@/components/ui/GSpinner.vue'

const props = defineProps({ reviewId: { type: String, required: true } })
const REVIEW_COMMENT_MAXIMUM_COUNT = 500
const router = useRouter()
const toast = useToastStore()

const loading = ref(true)
const loadError = ref(null)
const review = ref(null)
const result = ref(null)
const selected = ref([])
const comment = ref('')
const deciding = ref(null)
const isPending = computed(() => review.value?.status === 'PENDING')

onMounted(load)
async function load() {
  loading.value = true
  try {
    const nextReview = await api.getReview(props.reviewId)
    const nextResult = await api.getAnalysisResult(nextReview.analysisId)
    review.value = nextReview
    result.value = nextResult
    selected.value = nextResult.findings.filter((f) => f.severity === 'HIGH').map((f) => f.findingId)
    loadError.value = null
  } catch (e) {
    loadError.value = e
    toast.fromError(e)
  } finally {
    loading.value = false
  }
}

function toggle(id) { const i = selected.value.indexOf(id); i >= 0 ? selected.value.splice(i, 1) : selected.value.push(id) }
async function decide(status) {
  if (comment.value.length > REVIEW_COMMENT_MAXIMUM_COUNT) {
    return toast.push({ type: 'error', title: '검토 의견 입력 오류', message: `검토 의견은 ${REVIEW_COMMENT_MAXIMUM_COUNT}자 이하로 입력하세요.` })
  }
  if (status === 'APPROVED' && !selected.value.length && result.value?.findings?.length !== 0) return toast.fromError({ status: 400, errorCode: 'INVALID_FINDING_SELECTION', message: '승격할 Finding을 1개 이상 선택하세요.' })
  if (status === 'REJECTED' && !comment.value.trim()) return toast.fromError({ status: 400, errorCode: 'COMMENT_REQUIRED', message: '반려 사유를 입력하세요.' })
  deciding.value = status
  try {
    const res = await api.decideReview(props.reviewId, { status, comment: comment.value, selectedFindingIds: status === 'APPROVED' ? selected.value : [] })
    if (status === 'APPROVED') toast.success('승인 완료', `선택한 Finding ${res.riskPatternIds.length}건이 Risk Pattern으로 승격되었습니다. Risk Library 상세에서 GuardFit 초안을 생성하세요.`)
    else toast.success('반려 완료', '담당자 대시보드에 수정 필요로 표시됩니다')
    router.push(status === 'APPROVED' ? '/risk-library' : '/reviews')
  } catch (e) { toast.fromError(e) } finally { deciding.value = null }
}
const idx = (i) => 'F.' + String(i + 1).padStart(2, '0')
</script>

<template>
  <div class="wrap rise">
    <button class="back" @click="router.push('/reviews')"><PhArrowLeft :size="15" /> 검토함</button>
    <div v-if="loading" class="pad"><GSpinner :size="24" /></div>

    <div v-else-if="loadError" class="load-fail" role="alert">
      <PhWarningCircle :size="22" class="load-fail-i" />
      <div class="grow">
        <p class="t-base fw-semibold">검토 내용을 불러오지 못했습니다</p>
        <p class="t-sm soft">잠시 후 다시 시도해 주세요.</p>
      </div>
      <GButton variant="secondary" size="sm" @click="load">
        <template #icon><PhArrowClockwise :size="15" /></template>다시 시도
      </GButton>
    </div>

    <template v-else-if="review && result">
      <header class="top">
        <div>
          <p class="kicker mono">{{ review.reviewId }}</p>
          <h1 class="d-h1">{{ review.productName }}</h1>
          <p class="mono meta">{{ review.ownerName }} · {{ review.analysisId }} · SCORE {{ result.riskScore }}</p>
        </div>
        <div class="top-r"><GSeverityBadge :severity="review.maxSeverity" /><GStatusPill :status="review.status" /></div>
      </header>

      <p v-if="review.submissionComment" class="submit-note"><span class="mono ml">제출 의견</span>{{ review.submissionComment }}</p>

      <div class="fh">
        <h2 class="d-h3">Finding</h2>
        <span v-if="isPending" class="t-sm mute">체크한 Finding만 <b class="fw-semibold" style="color:var(--ink)">Risk Library</b>로 승격됩니다</span>
      </div>

      <ol class="finds">
        <li v-for="(f, i) in result.findings" :key="f.findingId" class="find" :class="{ picked: isPending && selected.includes(f.findingId) }">
          <div class="gutter">
            <button v-if="isPending" class="pick" :class="{ on: selected.includes(f.findingId) }" @click="toggle(f.findingId)" :aria-pressed="selected.includes(f.findingId)">
              <PhCheck v-if="selected.includes(f.findingId)" :size="13" weight="bold" />
            </button>
            <span class="mono fidx">{{ idx(i) }}</span>
          </div>
          <div class="fbody">
            <div class="frow">
              <GSeverityBadge :severity="f.severity" />
              <GBadge tone="neutral">{{ FINDING_TYPE_LABEL[f.findingType || f.aiDetail?.findingType] || f.findingType || f.aiDetail?.findingType }}</GBadge>
              <GBadge v-if="f.categoryCode || f.aiDetail?.categoryCode" tone="neutral">{{ f.categoryCode || f.aiDetail?.categoryCode }}</GBadge>
              <GBadge tone="neutral">{{ RULE_LABEL[f.ruleCode || f.aiDetail?.ruleCode] || f.ruleCode || f.aiDetail?.ruleCode }}</GBadge>
            </div>
            <p class="t-lg fmsg">{{ f.statement }}</p>
            <blockquote v-for="(source, j) in (f.sourceReferences || f.aiDetail?.sourceReferences || [])" :key="j" class="quote"><span class="mono qp">p.{{ source.page ?? '-' }}</span>{{ source.excerpt }}</blockquote>
            <div class="mini">
              <span>{{ f.affectedPersonaCodes.map(personaName).join(', ') }}</span>
              <span class="mute">근거 {{ f.evidenceReferences?.length || 0 }}건</span>
              <span v-if="result.vulnerabilityPatterns?.find((pattern) => pattern.findingIds?.includes(f.findingId))" class="mute">재현율 {{ Math.round(result.vulnerabilityPatterns.find((pattern) => pattern.findingIds?.includes(f.findingId)).consistencyRate * 100) }}%</span>
            </div>
            <details v-if="f.aiDetail" class="trace-detail">
              <summary>판단 근거와 실행 추적</summary>
              <p v-for="evidenceItem in f.evidenceReferences || []" :key="evidenceItem.evidenceDocumentId" class="t-sm soft">근거 {{ evidenceItem.evidenceDocumentId }} · {{ evidenceItem.excerpt }}</p>
              <p v-for="factId in f.aiDetail.groundTruthFactIds || []" :key="factId" class="t-sm soft">공식 사실 {{ factId }} · {{ result.groundTruthFacts?.find((fact) => fact.factId === factId)?.value || '-' }}</p>
              <p v-for="runId in f.aiDetail.sourceRunIds || []" :key="runId" class="t-sm soft">Persona Run {{ runId }}</p>
              <p v-for="caseItem in f.aiDetail.caseReferences || []" :key="caseItem.knowledgeSourceId" class="t-sm soft">민원/분쟁 사례 · {{ caseItem.excerpt }}</p>
            </details>
            <p class="reco"><span class="mono ml">권고</span>{{ f.recommendation }}</p>
          </div>
        </li>
      </ol>

      <section v-if="result.guardFitSuggestions?.length" class="suggestions">
        <div class="fh"><h2 class="d-h3">GuardFit Suggestion 미리보기</h2><span class="mono mute">PROPOSED</span></div>
        <div v-for="suggestion in result.guardFitSuggestions" :key="suggestion.suggestionId" class="suggestion">
          <p class="fw-semibold">{{ suggestion.label }} · {{ suggestion.actionType }}</p>
          <p class="t-sm soft">{{ suggestion.beforeText }} → {{ suggestion.afterText }}</p>
        </div>
      </section>

      <!-- Decision -->
      <div v-if="isPending" class="decide">
        <div class="dec-head"><h2 class="d-h3">검토 결정</h2><span class="mono mute">{{ selected.length }} / {{ result.findings.length }} 승격 선택</span></div>
        <GField label="의견" hint="반려 시 필수. 승인 시 권장." for-id="cmt" :current-count="comment.length" :maximum-count="REVIEW_COMMENT_MAXIMUM_COUNT">
          <GTextarea id="cmt" v-model="comment" :rows="3" :maximum-count="REVIEW_COMMENT_MAXIMUM_COUNT" placeholder="결정 사유와 수정 방향을 남기세요" />
        </GField>
        <div class="dec-act">
          <GButton variant="danger" :loading="deciding === 'REJECTED'" :disabled="!!deciding" @click="decide('REJECTED')"><template #icon><PhXCircle :size="16" /></template>반려</GButton>
          <GButton variant="primary" :loading="deciding === 'APPROVED'" :disabled="!!deciding" @click="decide('APPROVED')"><template #icon><PhSealCheck :size="16" /></template>승인 · {{ selected.length }}건 승격</GButton>
        </div>
      </div>

      <div v-else class="decided">
        <PhCheckCircle v-if="review.status === 'APPROVED'" :size="18" weight="fill" class="ok" />
        <PhXCircle v-else :size="18" weight="fill" class="no" />
        <span class="t-base fw-semibold">{{ review.status === 'APPROVED' ? '승인됨' : '반려됨' }}</span>
        <span class="mono mute dm">{{ review.reviewerId }} · {{ formatDateTime(review.decidedAt) }}</span>
        <p v-if="review.comment" class="t-sm soft dc">{{ review.comment }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.wrap { max-width: 900px; }
.back { display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--ink-mute); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--s-16); }
.back:hover { color: var(--ink); }
.pad { display: grid; place-items: center; padding: var(--s-64); }
.load-fail { margin-top: var(--s-24); padding: var(--s-24); border: 1px solid var(--risk-high-wash); background: var(--risk-high-wash); border-radius: var(--r-lg); display: flex; align-items: center; gap: var(--s-16); }
.load-fail-i { color: var(--risk-high); flex: none; }
.top { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-16); }
.top .meta { margin-top: var(--s-10); color: var(--ink-mute); font-size: var(--text-xs); }
.top-r { display: flex; align-items: center; gap: var(--s-12); flex: none; }
.submit-note { margin-top: var(--s-24); padding: var(--s-14, 14px) var(--s-16); background: var(--surface-2); border-radius: var(--r); display: flex; flex-direction: column; gap: 6px; font-size: var(--text-sm); }
.ml { font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-mute); }
.fh { display: flex; align-items: baseline; justify-content: space-between; gap: var(--s-12); margin-top: var(--s-40); padding-bottom: var(--s-16); border-bottom: 1px solid var(--line-strong); }

.finds { list-style: none; }
.find { display: grid; grid-template-columns: 56px 1fr; gap: var(--s-16); padding: var(--s-24) 0; border-bottom: 1px solid var(--line); transition: background var(--fast) var(--ease); }
.find.picked { background: var(--accent-wash); }
.gutter { display: flex; flex-direction: column; align-items: center; gap: var(--s-10); }
.pick { width: 24px; height: 24px; border: 1px solid var(--line-strong); background: var(--surface); border-radius: var(--r-xs); color: #fff; cursor: pointer; display: grid; place-items: center; transition: all var(--fast) var(--ease); }
.pick.on { background: var(--accent); border-color: var(--accent); }
.fidx { font-size: 11px; color: var(--ink-mute); }
.fbody { min-width: 0; }
.frow { display: flex; align-items: center; gap: var(--s-8); flex-wrap: wrap; }
.fmsg { margin-top: var(--s-10); letter-spacing: -0.01em; }
.quote { margin-top: var(--s-12); padding: var(--s-10) var(--s-16); background: var(--surface-2); border-radius: var(--r-sm); line-height: 1.6; }
.qp { color: var(--accent); margin-right: 8px; font-size: var(--text-xs); }
.mini { display: flex; gap: var(--s-16); margin-top: var(--s-12); font-size: var(--text-sm); color: var(--ink-soft); flex-wrap: wrap; }
.reco { margin-top: var(--s-12); display: flex; flex-direction: column; gap: 5px; color: var(--ink-2); font-size: var(--text-sm); line-height: 1.6; }
.trace-detail { margin-top: var(--s-12); font-size: var(--text-sm); }
.trace-detail summary { cursor: pointer; color: var(--accent); }
.suggestions { margin-top: var(--s-32); }
.suggestions > .fh { margin-top: 0; }
.suggestion { padding: var(--s-16) 0; border-bottom: 1px solid var(--line); }

.decide { margin-top: var(--s-40); padding: var(--s-28); border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); display: flex; flex-direction: column; gap: var(--s-20); }
.dec-head { display: flex; align-items: baseline; justify-content: space-between; }
.dec-act { display: flex; justify-content: flex-end; gap: var(--s-10); }
.decided { margin-top: var(--s-40); padding: var(--s-24); border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); display: flex; align-items: center; gap: var(--s-10); flex-wrap: wrap; }
.decided .ok { color: var(--ok); } .decided .no { color: var(--risk-high); }
.decided .dm { margin-left: auto; }
.decided .dc { flex-basis: 100%; margin-top: 4px; }
</style>
