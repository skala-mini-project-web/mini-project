// =============================================================================
// Deterministic analysis scenarios (Mock 데이터 명세서 §6).
// Same input + scenarioCode always yields the same AnalysisResult.
// The real AI provider must implement the same AnalysisResult DTO + score policy.
// =============================================================================

// riskScore = min(100, severityBase + personaBonus + ruleBonus + groundingBonus)
export function computeRiskScore(findings) {
  if (!findings || findings.length === 0) return 0
  const sevBase = { HIGH: 60, MEDIUM: 35, LOW: 15 }
  const severityBase = Math.max(...findings.map((f) => sevBase[f.severity] ?? 0))
  const personas = new Set(findings.flatMap((f) => f.affectedPersonaCodes ?? []))
  const rules = new Set(findings.map((f) => f.ruleCode))
  const personaBonus = Math.min(15, 5 * personas.size)
  const ruleBonus = Math.min(12, 3 * rules.size)
  const highs = findings.filter((f) => f.severity === 'HIGH')
  const everyHighGrounded =
    highs.length > 0 && highs.every((f) => (f.evidenceReferences ?? []).length > 0)
  const groundingBonus = everyHighGrounded ? 6 : 0
  return Math.min(100, severityBase + personaBonus + ruleBonus + groundingBonus)
}

// Finding factory keeps sourceReference.documentId bound to the analyzed doc.
function finding(documentId, f) {
  return { ...f, sourceReference: { ...f.sourceReference, documentId } }
}

// Normal scenarios: return findings for a given source documentId.
export const NORMAL_SCENARIOS = {
  GUARANTEE_MISUNDERSTANDING_HIGH: {
    label: '원금보장 오인 (HIGH)',
    schedule: { createdToRunningMs: 900, runningToCompletedMs: 2200 },
    findings: (docId) => [
      finding(docId, {
        findingId: 'FND-001', findingType: 'FRAMING', ruleCode: 'STABILITY_KEYWORD',
        message: '안정성 표현이 원금보장으로 오인될 가능성이 있습니다.', severity: 'HIGH',
        sourceReference: { page: 1, excerpt: '최근 안정적인 수익률을 기록한 투자상품입니다.' },
        affectedPersonaCodes: ['FINANCIAL_BEGINNER', 'SENIOR'],
        evidenceReferences: [{ documentId: 'POLICY-003', excerpt: '원금손실 가능성은 안정성 표현과 인접해 표시해야 합니다.', sourceType: 'INTERNAL_POLICY' }],
        recommendation: '같은 영역에 원금 손실 가능성을 명시하세요.',
      }),
      finding(docId, {
        findingId: 'FND-002', findingType: 'OMISSION', ruleCode: 'COST_OMISSION',
        message: '총비용 및 운용 보수가 인접 위치에 표시되지 않았습니다.', severity: 'MEDIUM',
        sourceReference: { page: 2, excerpt: '운용 보수' },
        affectedPersonaCodes: ['FINANCIAL_BEGINNER'],
        evidenceReferences: [{ documentId: 'POLICY-003', excerpt: '비용 정보는 수익 표현과 함께 제시해야 합니다.', sourceType: 'INTERNAL_POLICY' }],
        recommendation: '총비용 예시를 수익 표현 인접 위치에 표시하세요.',
      }),
    ],
  },
  COST_OMISSION_MEDIUM: {
    label: '비용 누락 (MEDIUM)',
    schedule: { createdToRunningMs: 700, runningToCompletedMs: 1800 },
    findings: (docId) => [
      finding(docId, {
        findingId: 'FND-101', findingType: 'OMISSION', ruleCode: 'COST_OMISSION',
        message: '수수료 및 총비용 정보가 누락되었습니다.', severity: 'MEDIUM',
        sourceReference: { page: 1, excerpt: '수수료 안내' },
        affectedPersonaCodes: ['FINANCIAL_BEGINNER', 'LOW_LITERACY', 'DIGITAL_NOVICE'],
        evidenceReferences: [{ documentId: 'POLICY-003', excerpt: '비용 정보는 수익 표현과 함께 제시해야 합니다.', sourceType: 'INTERNAL_POLICY' }],
        recommendation: '총비용 예시와 수수료 항목을 명시하세요.',
      }),
    ],
  },
  ACCESSIBILITY_LOW: {
    label: '접근성 (LOW)',
    schedule: { createdToRunningMs: 600, runningToCompletedMs: 1500 },
    findings: (docId) => [
      finding(docId, {
        findingId: 'FND-201', findingType: 'ACCESSIBILITY', ruleCode: 'COGNITIVE_ACCESSIBILITY',
        message: '전문용어가 많아 저문해 소비자에게 인지 부담이 큽니다.', severity: 'LOW',
        sourceReference: { page: 1, excerpt: '기초자산 변동성' },
        affectedPersonaCodes: ['LOW_LITERACY'],
        evidenceReferences: [],
        recommendation: '핵심 용어에 쉬운 설명을 병기하세요.',
      }),
    ],
  },
  NO_FINDING: {
    label: '정상 저위험 (Finding 없음)',
    schedule: { createdToRunningMs: 500, runningToCompletedMs: 1200 },
    findings: () => [],
  },
}

// Error scenarios (Mock 데이터 명세서 §6).
export const ERROR_SCENARIOS = {
  RATE_LIMIT_THEN_SUCCESS: {
    label: '재시도 후 성공 (retryable)',
    kind: 'FAIL_THEN_SUCCESS',
    schedule: { createdToRunningMs: 700, runningToFailedMs: 1600 },
    error: { errorCode: 'PROVIDER_RATE_LIMITED', message: '제공자 호출이 일시적으로 제한되었습니다.', retryable: true },
    successScenario: 'GUARANTEE_MISUNDERSTANDING_HIGH',
  },
  PROVIDER_TIMEOUT_EXHAUSTED: {
    label: '타임아웃 소진 (재시도 불가)',
    kind: 'FAIL',
    schedule: { createdToRunningMs: 700, runningToFailedMs: 1800 },
    error: { errorCode: 'PROVIDER_TIMEOUT', message: '자동 재시도가 모두 소진되었습니다.', retryable: false },
  },
  INVALID_PROVIDER_RESPONSE: {
    label: '응답 스키마 오류 (재시도 불가)',
    kind: 'FAIL',
    schedule: { createdToRunningMs: 600, runningToFailedMs: 1400 },
    error: { errorCode: 'PROVIDER_RESPONSE_INVALID', message: '제공자 응답 스키마가 유효하지 않습니다.', retryable: false },
  },
}

export const SCENARIO_OPTIONS = [
  { value: 'GUARANTEE_MISUNDERSTANDING_HIGH', label: 'GUARANTEE_MISUNDERSTANDING_HIGH · 원금보장 오인 (score 82)' },
  { value: 'COST_OMISSION_MEDIUM', label: 'COST_OMISSION_MEDIUM · 비용 누락' },
  { value: 'ACCESSIBILITY_LOW', label: 'ACCESSIBILITY_LOW · 접근성' },
  { value: 'NO_FINDING', label: 'NO_FINDING · 정상 저위험' },
  { value: 'RATE_LIMIT_THEN_SUCCESS', label: 'RATE_LIMIT_THEN_SUCCESS · 재시도 후 성공' },
  { value: 'PROVIDER_TIMEOUT_EXHAUSTED', label: 'PROVIDER_TIMEOUT_EXHAUSTED · 재시도 불가 실패' },
]

export function isErrorScenario(code) {
  return code in ERROR_SCENARIOS
}
