import { computeRiskScore } from '../api/mock/scenarios.js'

// AI(LLM) 원시 출력 → 안전하게 검증·정규화된 Finding 집합으로 변환하는 가드레일.
// docs/ai-provider.md §6 규칙을 코드로 강제한다. riskScore는 LLM이 아니라 여기서 재계산.

const norm = (s) => (s || '').replace(/\s+/g, '').toLowerCase()
const FINDING_TYPES = ['FRAMING', 'OMISSION', 'MISUNDERSTANDING', 'ACCESSIBILITY']
const SEVERITIES = ['HIGH', 'MEDIUM', 'LOW']

export function applyGuardrails(rawFindings, ctx) {
  const { sourceText, ruleCodes = [], personaCodes = [], evidenceIds = [] } = ctx || {}
  const src = norm(sourceText)
  const ruleSet = new Set(ruleCodes)
  const personaSet = new Set(personaCodes)
  const evidenceSet = new Set(evidenceIds)
  const violations = []
  const findings = []

  for (const f of Array.isArray(rawFindings) ? rawFindings : []) {
    if (!f || typeof f !== 'object') { violations.push('finding 형식 오류'); continue }
    const sourceReferences = Array.isArray(f.sourceReferences) ? f.sourceReferences : []
    const excerpt = sourceReferences[0]?.excerpt || ''
    // 근거 실재성: 발췌는 원문의 실제 부분 문자열이어야 한다(환각 차단)
    if (!excerpt || !src.includes(norm(excerpt))) { violations.push(`근거 실재성 위반: "${excerpt}"`); continue }
    if (!ruleSet.has(f.ruleCode)) { violations.push(`규칙 범위 밖: ${f.ruleCode}`); continue }
    const personas = (f.affectedPersonaCodes || []).filter((p) => personaSet.has(p))
    if (!personas.length) { violations.push('영향 persona 없음/범위 밖'); continue }
    const severity = SEVERITIES.includes(f.severity) ? f.severity : 'LOW'
    const evidenceReferences = (f.evidenceReferences || [])
      .filter((e) => e && evidenceSet.has(e.evidenceDocumentId))
      .map((e) => ({ evidenceDocumentId: e.evidenceDocumentId, excerpt: String(e.excerpt || '').slice(0, 500), sourceType: e.sourceType || 'INTERNAL_POLICY' }))
    // HIGH는 근거 1건 이상 필수
    if (severity === 'HIGH' && !evidenceReferences.length) { violations.push('HIGH 근거 누락'); continue }

    findings.push({
      findingType: FINDING_TYPES.includes(f.findingType) ? f.findingType : 'FRAMING',
      categoryCode: /^[A-Z][A-Z0-9_]*$/.test(f.categoryCode || '') ? f.categoryCode : 'UNCLASSIFIED',
      ruleCode: f.ruleCode,
      severity,
      statement: String(f.statement || '').slice(0, 1000),
      sourceReferences: sourceReferences.map((reference) => ({
        page: Number.isInteger(reference?.page) ? reference.page : 1,
        slide: Number.isInteger(reference?.slide) ? reference.slide : null,
        excerpt: String(reference?.excerpt || '').slice(0, 500),
      })),
      affectedPersonaCodes: personas,
      evidenceReferences,
      recommendation: String(f.recommendation || '').slice(0, 1000),
    })
  }

  return { findings, riskScore: computeRiskScore(findings), violations }
}
