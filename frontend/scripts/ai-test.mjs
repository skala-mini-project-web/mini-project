import { retrieveEvidence } from '../src/lib/rag.js'
import { applyGuardrails } from '../src/lib/guardrails.js'

let pass = 0, fail = 0
const ok = (n, c) => { c ? pass++ : fail++; console.log(`${c ? 'ok  ' : 'FAIL'} ${n}`) }

// --- RAG retrieval ---
const r = retrieveEvidence('안정적인 수익률을 기록한 상품, 원금 손실 가능성', 2)
ok('rag: 결과 있음', r.length > 0)
ok('rag: POLICY-003 최상위(원금손실/안정성)', r[0]?.documentId === 'POLICY-003')

// --- Guardrails (환각/범위밖/근거누락 차단) ---
const sourceText = '본 상품은 최근 안정적인 수익률을 기록한 투자상품입니다. 원금 손실 가능성이 있습니다.'
const ctx = {
  sourceText,
  ruleCodes: ['STABILITY_KEYWORD', 'RETURN_FRAMING', 'COST_OMISSION'],
  personaCodes: ['FINANCIAL_BEGINNER', 'SENIOR'],
  evidenceIds: ['POLICY-003', 'LAW-014'],
}
const raw = [
  { findingType: 'FRAMING', categoryCode: 'PRINCIPAL_PROTECTION_MISUNDERSTANDING', ruleCode: 'STABILITY_KEYWORD', severity: 'HIGH', statement: '안정성 표현 오인', sourceReferences: [{ page: 1, excerpt: '안정적인 수익률을 기록한' }], affectedPersonaCodes: ['FINANCIAL_BEGINNER'], evidenceReferences: [{ evidenceDocumentId: 'POLICY-003', excerpt: '원금 손실 가능성은 안정성 표현과 인접', sourceType: 'INTERNAL_POLICY' }], recommendation: '원금손실 명시' },
  { findingType: 'FRAMING', categoryCode: 'RETURN_GUARANTEE_MISUNDERSTANDING', ruleCode: 'RETURN_FRAMING', severity: 'HIGH', statement: '환각 발췌', sourceReferences: [{ page: 1, excerpt: '연 10% 수익을 보장' }], affectedPersonaCodes: ['FINANCIAL_BEGINNER'], evidenceReferences: [{ evidenceDocumentId: 'LAW-014' }] },
  { findingType: 'OMISSION', categoryCode: 'UNCLASSIFIED', ruleCode: 'FOO_BAR', severity: 'LOW', statement: '범위밖 룰', sourceReferences: [{ page: 1, excerpt: '원금 손실 가능성' }], affectedPersonaCodes: ['SENIOR'] },
  { findingType: 'FRAMING', categoryCode: 'PRINCIPAL_PROTECTION_MISUNDERSTANDING', ruleCode: 'RETURN_FRAMING', severity: 'HIGH', statement: 'HIGH 근거없음', sourceReferences: [{ page: 1, excerpt: '원금 손실 가능성' }], affectedPersonaCodes: ['SENIOR'], evidenceReferences: [] },
]
const g = applyGuardrails(raw, ctx)
ok('guardrails: 유효 finding 1개만 통과', g.findings.length === 1)
ok('guardrails: 위반 3건 기록', g.violations.length === 3)
ok('guardrails: score 계산됨(>0)', g.riskScore > 0)

console.log(`\n== ai-test: ${pass} passed, ${fail} failed ==`)
process.exit(fail ? 1 : 0)
