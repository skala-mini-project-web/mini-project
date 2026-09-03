// 실제 로컬 AI 분석 오케스트레이터: RAG 근거 검색 → 프롬프트 → 로컬 LLM → 가드레일 → 점수.
// docs/ai-provider.md 규격을 코드로 구현. riskScore는 LLM이 아니라 가드레일에서 재계산.
import { retrieveEvidence, retrieveEvidenceEmbed } from './rag.js'
import { chatJson, llmAvailable } from './llm.js'
import { applyGuardrails } from './guardrails.js'

const SYSTEM = [
  '너는 금융소비자 보호 관점의 표현 리스크 분석가다. 입력 문서에서 소비자가 오인할 수 있는 표현을 찾아 JSON으로만 보고한다.',
  '원칙:',
  '- excerpt는 반드시 입력 문서(sourceText)에 있는 문구를 그대로 복사한다. 없는 표현을 지어내지 않는다.',
  '- ruleCode는 제공된 목록 중에서만 고른다. affectedPersonaCodes는 제공된 persona 코드 중에서만 고른다.',
  '- severity가 HIGH면 제공된 evidence의 documentId를 evidenceReferences에 최소 1건 넣는다.',
  '- 확신이 없으면 findings를 비운다. 과잉 지적보다 정확성이 우선.',
  '- 출력은 스키마만. 설명/마크다운 금지.',
].join('\n')

function buildUserPrompt(sourceText, personaCodes, ruleCodes, grounding) {
  return [
    `[분석 대상 문서]\n${sourceText}`,
    `[ruleCodes]\n${ruleCodes.join(', ')}`,
    `[personaCodes]\n${personaCodes.join(', ')}`,
    `[evidence]\n${grounding.map((g) => `- ${g.documentId} (${g.sourceType}): ${g.excerpt}`).join('\n')}`,
    '[출력 스키마]',
    '{"findings":[{"findingType":"FRAMING|OMISSION|MISUNDERSTANDING|ACCESSIBILITY","ruleCode":"<목록 중>","severity":"HIGH|MEDIUM|LOW","message":"...","sourceReference":{"page":1,"excerpt":"<문서 원문 그대로>"},"affectedPersonaCodes":["..."],"evidenceReferences":[{"documentId":"<evidence 중>","excerpt":"...","sourceType":"INTERNAL_POLICY|LAW"}],"recommendation":"..."}]}',
  ].join('\n\n')
}

export async function analyzeDocument({ sourceText, personaCodes = [], ruleCodes = [], useEmbedding = true }) {
  const grounding = useEmbedding
    ? await retrieveEvidenceEmbed(sourceText, 3).catch(() => retrieveEvidence(sourceText, 3))
    : retrieveEvidence(sourceText, 3)
  const raw = await chatJson([
    { role: 'system', content: SYSTEM },
    { role: 'user', content: buildUserPrompt(sourceText, personaCodes, ruleCodes, grounding) },
  ])
  const rawFindings = Array.isArray(raw) ? raw : raw?.findings || []
  const result = applyGuardrails(rawFindings, {
    sourceText,
    ruleCodes,
    personaCodes,
    evidenceIds: grounding.map((g) => g.documentId),
  })
  return { ...result, grounding, provider: 'LOCAL_OLLAMA' }
}

export { llmAvailable }
