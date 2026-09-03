const SOURCE_TEXT = '최근 안정적인 수익률을 기대할 수 있는 투자상품입니다. 운용 보수가 부과됩니다. 시장 상황에 따라 원금 전액 손실이 발생할 수 있습니다.'

export const GOLDEN_PRODUCT = {
  productId: 1, ownerId: 'USER-PM-001', name: '스마트 인컴 투자상품', productType: 'INVESTMENT',
  description: '월 지급식 인컴형 투자상품', status: 'ANALYZED', createdAt: '2026-09-03T10:00:00+09:00',
}

export const GOLDEN_DOCUMENT = {
  documentId: 11, productId: 1, fileName: '스마트인컴_판매자료.pdf', mediaType: 'application/pdf', fileSize: 2516582,
  storageKey: 'mock://fixtures/v1/smart-income.pdf', checksumSha256: 'a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00',
  extractStatus: 'READY', extractMethod: 'MOCK_FIXTURE', rawExtractedText: SOURCE_TEXT, verifiedText: SOURCE_TEXT,
  confirmed: true, confirmedBy: 'USER-PM-001', confirmedAt: '2026-09-03T10:02:00+09:00', attemptCount: 1, error: null, clockDriven: false,
}

export const GOLDEN_PERSONAS = [
  { personaId: 41, code: 'FINANCIAL_BEGINNER', name: '금융 초보자', criteria: { knowledge: 'LOW', investExperience: 'NONE' }, riskFocus: ['PRINCIPAL_LOSS', 'COST'], questionSummary: '시장 하락 시 원금과 실제 비용 이해', active: true },
  { personaId: 42, code: 'SENIOR', name: '고령 금융소비자', criteria: { knowledge: 'LOW', digitalLiteracy: 'LOW' }, riskFocus: ['PRINCIPAL_LOSS', 'ACCESSIBILITY'], questionSummary: '손실 고지와 핵심 위험 인지', active: true },
]

export const GOLDEN_EVIDENCE = [
  { documentId: 21, title: '스마트 인컴 공식 상품정책', sourceType: 'PRODUCT_POLICY', version: 'DEMO-2026.1', excerpt: '투자자는 원금의 전부를 손실할 수 있습니다.', active: true, synthetic: true },
  { documentId: 22, title: '금융상품 중요정보 표시 내부준칙', sourceType: 'INTERNAL_POLICY', version: 'DEMO-2026.1', excerpt: '원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.', active: true, synthetic: true },
]

export const GOLDEN_FACTS = [
  { factId: 201, documentId: 11, factType: 'PRINCIPAL_LOSS', label: '원금손실 가능성', value: '시장 상황에 따라 원금 전액 손실 가능', importance: 'CRITICAL', verificationStatus: 'VERIFIED', extractionSource: 'MOCK_FIXTURE', sourceReferences: [{ evidenceDocumentId: 21, page: 8, excerpt: '투자자는 원금의 전부를 손실할 수 있습니다.' }], verifiedBy: 'USER-PM-001', verifiedAt: '2026-09-03T10:03:00+09:00' },
  { factId: 202, documentId: 11, factType: 'COST', label: '운용 비용', value: '운용 보수 부과', importance: 'HIGH', verificationStatus: 'VERIFIED', extractionSource: 'MOCK_FIXTURE', sourceReferences: [{ evidenceDocumentId: 21, page: 9, excerpt: '운용 보수가 부과됩니다.' }], verifiedBy: 'USER-PM-001', verifiedAt: '2026-09-03T10:03:00+09:00' },
  { factId: 203, documentId: 11, factType: 'RETURN_STRUCTURE', label: '수익 구조', value: '시장 성과에 따라 수익 변동', importance: 'HIGH', verificationStatus: 'VERIFIED', extractionSource: 'MOCK_FIXTURE', sourceReferences: [{ evidenceDocumentId: 21, page: 4, excerpt: '시장 성과에 따라 수익이 변동됩니다.' }], verifiedBy: 'USER-PM-001', verifiedAt: '2026-09-03T10:03:00+09:00' },
]

export const GOLDEN_RED_TEAM_PACK = [{
  redTeamPackId: 51, code: 'CORE_FINANCIAL_RISK_V1', name: '금융소비자 핵심 위험 Pack', version: '1.0',
  rules: [
    { ruleId: 511, ruleCode: 'STABILITY_KEYWORD', findingType: 'FRAMING', purpose: '안정 표현이 원금 보장으로 오인되는지' },
    { ruleId: 512, ruleCode: 'COST_OMISSION', findingType: 'OMISSION', purpose: '비용 수준이 누락됐는지' },
    { ruleId: 513, ruleCode: 'RETURN_FRAMING', findingType: 'FRAMING', purpose: '수익이 확정처럼 보이는지' },
    { ruleId: 514, ruleCode: 'LOSS_SOFTENING', findingType: 'FRAMING', purpose: '손실 가능성을 완화하는지' },
    { ruleId: 515, ruleCode: 'FORMAL_CONFIRMATION', findingType: 'MISUNDERSTANDING', purpose: '이해 확인이 형식적인지' },
    { ruleId: 516, ruleCode: 'COGNITIVE_ACCESSIBILITY', findingType: 'ACCESSIBILITY', purpose: '인지 접근성이 낮은지' },
  ],
}]

export const GOLDEN_ANALYSIS = {
  analysisId: 61, productDocumentId: 11, productId: 1, status: 'COMPLETED', stage: 'COMPLETED', progress: 100,
  riskScore: 82, providerType: 'MOCK', evidenceDocumentIds: [21, 22], personaIds: [41, 42], redTeamPackId: 51,
  attemptCount: 1, error: null, clockDriven: false, createdAt: '2026-09-03T10:04:00+09:00',
}

export const GOLDEN_REVIEW = {
  reviewId: 91,
  analysisId: 61,
  productId: 1,
  productName: '스마트 인컴 투자상품',
  maxSeverity: 'HIGH',
  status: 'PENDING',
  submittedBy: 'USER-PM-001',
  ownerName: '박서준 대리',
  submittedAt: '2026-09-03T10:10:00+09:00',
  createdAt: '2026-09-03T10:10:00+09:00',
  submissionComment: '고위험 Finding 검토 요청',
  reviewerId: null,
  decidedAt: null,
  comment: null,
  selectedFindingIds: [],
  riskPatternIds: [],
}

export const GOLDEN_AUDIT_LOG = {
  auditId: 901,
  resourceType: 'REVIEW',
  resourceId: 91,
  action: 'REVIEW_SUBMITTED',
  actorId: 'USER-PM-001',
  actorName: '박서준 대리',
  analysisId: 61,
  traceId: 'trc-20260903-0001',
  createdAt: '2026-09-03T10:10:00+09:00',
}

export function buildGoldenOutcome(analysisId = 61, documentId = 11, personaIds = [41, 42], groundTruthFactIds = [201, 202], evidenceDocumentIds = [21, 22], sourceText = SOURCE_TEXT) {
  const seeded = Number(analysisId) === 61
  const policyEvidenceId = evidenceDocumentIds.includes(22) ? 22 : evidenceDocumentIds[0]
  const stabilityExcerpt = sourceText.includes('최근 안정적인 수익률을 기대할 수 있는 투자상품입니다.') ? '최근 안정적인 수익률을 기대할 수 있는 투자상품입니다.' : sourceText.slice(0, 120)
  const costExcerpt = sourceText.includes('운용 보수가 부과됩니다.') ? '운용 보수가 부과됩니다.' : sourceText.slice(0, 120)
  const selected = personaIds.map((id) => GOLDEN_PERSONAS.find((persona) => String(id) === String(persona.personaId)) || {
    personaId: id,
    code: String(id).replace(/^PERSONA-/, ''),
    name: String(id),
  })
  const personaRuns = selected.flatMap((persona, personaIndex) => [1, 2, 3].map((repetitionNo, index) => ({
    runId: seeded ? 1001 + personaIndex * 10 + index : analysisId * 100 + personaIndex * 10 + repetitionNo, analysisId, personaId: persona.personaId, personaCode: persona.code,
    repetitionNo, summary: index < 2 || persona.code === 'FINANCIAL_BEGINNER' ? '안정성 표현을 원금 보호로 이해했습니다.' : '원금 손실 가능성을 인지했습니다.',
    questionResults: [{ questionCode: 'Q_PRINCIPAL_LOSS_01', dimension: 'PRINCIPAL_LOSS', question: '시장 하락 시 원금은 어떻게 된다고 이해하셨나요?', answer: index < 2 || persona.code === 'FINANCIAL_BEGINNER' ? '대부분 보호되는 것으로 이해했습니다.' : '전액 손실될 수 있습니다.', understood: !(index < 2 || persona.code === 'FINANCIAL_BEGINNER'), score: index < 2 || persona.code === 'FINANCIAL_BEGINNER' ? 25 : 90, rationale: '안정성 표현과 손실 고지를 함께 평가했습니다.' }],
    misunderstandingCandidates: index < 2 || persona.code === 'FINANCIAL_BEGINNER' ? [{ categoryCode: 'PRINCIPAL_PROTECTION_MISUNDERSTANDING', statement: '원금이 대부분 보호된다고 인식함' }] : [],
  })))
  const sourceRunIds = personaRuns.filter((run) => run.misunderstandingCandidates.length).map((run) => run.runId)
  const redTeamResults = [
    { redTeamResultId: seeded ? 3001 : analysisId * 100 + 1, analysisId, ruleId: 511, ruleCode: 'STABILITY_KEYWORD', triggered: true, findingType: 'FRAMING', statement: '안정성 표현이 손실 고지보다 먼저 강조되었습니다.', sourceReferences: [{ documentId, page: 1, slide: null, excerpt: stabilityExcerpt }], evidenceReferences: [{ evidenceDocumentId: policyEvidenceId, sourceType: 'INTERNAL_POLICY', title: '금융상품 중요정보 표시 내부준칙', version: 'DEMO-2026.1', page: 12, excerpt: '원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.' }] },
    { redTeamResultId: seeded ? 3002 : analysisId * 100 + 2, analysisId, ruleId: 512, ruleCode: 'COST_OMISSION', triggered: true, findingType: 'OMISSION', statement: '비용의 구체적인 수준이 누락되었습니다.', sourceReferences: [{ documentId, page: 1, slide: null, excerpt: costExcerpt }], evidenceReferences: [{ evidenceDocumentId: policyEvidenceId, sourceType: 'INTERNAL_POLICY', title: '금융상품 중요정보 표시 내부준칙', version: 'DEMO-2026.1', page: 13, excerpt: '비용 정보는 수익 표현과 함께 제시해야 합니다.' }] },
  ]
  const findings = [
    { findingId: seeded ? 401 : analysisId * 10 + 1, statement: '안정성 표현이 원금보장으로 오인될 가능성이 있습니다.', severity: 'HIGH', affectedPersonaCodes: selected.map((persona) => persona.code), evidenceReferences: [redTeamResults[0].evidenceReferences[0]], recommendation: '안정성 표현과 같은 영역에 원금손실 가능성을 명시하세요.', aiDetail: { findingType: 'MISUNDERSTANDING', categoryCode: 'PRINCIPAL_PROTECTION_MISUNDERSTANDING', ruleCode: 'STABILITY_KEYWORD', confidence: 0.94, taxonomyVersion: '1.0', sourceReferences: redTeamResults[0].sourceReferences, groundTruthFactIds: groundTruthFactIds.slice(0, 1), sourceRunIds, redTeamResultIds: [redTeamResults[0].redTeamResultId], caseReferences: [{ knowledgeSourceId: 31, sourceType: 'COMPLAINT_CASE', title: '안정성 표현 관련 합성 민원 사례', excerpt: '안정적이라는 설명을 원금 보장으로 이해했다.', similarityScore: 0.88 }] } },
    { findingId: seeded ? 402 : analysisId * 10 + 2, statement: '총비용 수준이 명확히 표시되지 않았습니다.', severity: 'MEDIUM', affectedPersonaCodes: selected.slice(0, 1).map((persona) => persona.code), evidenceReferences: [redTeamResults[1].evidenceReferences[0]], recommendation: '총비용 예시를 수익 표현 인접 위치에 표시하세요.', aiDetail: { findingType: 'OMISSION', categoryCode: 'COST_OMISSION', ruleCode: 'COST_OMISSION', confidence: 0.9, taxonomyVersion: '1.0', sourceReferences: redTeamResults[1].sourceReferences, groundTruthFactIds: groundTruthFactIds.slice(1, 2), sourceRunIds: sourceRunIds.slice(0, 3), redTeamResultIds: [redTeamResults[1].redTeamResultId], caseReferences: [] } },
  ]
  const totalRunCount = personaRuns.length
  const occurrenceCount = sourceRunIds.length
  const vulnerabilityPatterns = [{ patternId: seeded ? 501 : analysisId * 10 + 5, analysisId, patternKey: 'PRINCIPAL_PROTECTION_MISUNDERSTANDING', title: '안정성 표현에 따른 원금보장 오해', severity: 'HIGH', occurrenceCount, totalRunCount, consistencyRate: Number((occurrenceCount / totalRunCount).toFixed(4)), stabilityThreshold: 0.67, stable: occurrenceCount / totalRunCount >= 0.67, affectedPersonaCodes: selected.map((persona) => persona.code), findingIds: [findings[0].findingId] }]
  const guardFitSuggestions = [{ suggestionId: seeded ? 601 : analysisId * 10 + 6, patternId: vulnerabilityPatterns[0].patternId, actionType: 'WARNING', priority: 'HIGH', label: '원금손실 가능성 고지', placement: '상품 소개 문구 바로 아래', required: true, reason: `${totalRunCount}회 중 ${occurrenceCount}회에서 원금보장 오해가 재현되었습니다.`, beforeText: '최근 안정적인 수익률을 기대할 수 있는 투자상품입니다.', afterText: '시장 상황에 따라 원금 전액 손실이 발생할 수 있는 고위험 투자상품입니다.', evidenceDocumentIds: [policyEvidenceId], status: 'PROPOSED' }]
  return { personaRuns, redTeamResults, findings, vulnerabilityPatterns, guardFitSuggestions }
}

export function validateGoldenFixture() {
  const outcome = buildGoldenOutcome()
  const ids = (items, key) => new Set(items.map((item) => item[key]))
  const runIds = ids(outcome.personaRuns, 'runId')
  const redTeamIds = ids(outcome.redTeamResults, 'redTeamResultId')
  const findingIds = ids(outcome.findings, 'findingId')
  const personaIds = ids(GOLDEN_PERSONAS, 'personaId')
  const evidenceIds = ids(GOLDEN_EVIDENCE, 'documentId')
  const factIds = ids(GOLDEN_FACTS, 'factId')
  const patternIds = ids(outcome.vulnerabilityPatterns, 'patternId')
  const errors = []
  if (!sameFixtureId(GOLDEN_DOCUMENT.productId, GOLDEN_PRODUCT.productId)) errors.push(`productId:${GOLDEN_DOCUMENT.productId}`)
  if (!sameFixtureId(GOLDEN_ANALYSIS.productDocumentId, GOLDEN_DOCUMENT.documentId)) errors.push(`documentId:${GOLDEN_ANALYSIS.productDocumentId}`)
  if (!sameFixtureId(GOLDEN_REVIEW.analysisId, GOLDEN_ANALYSIS.analysisId)) errors.push(`reviewAnalysisId:${GOLDEN_REVIEW.analysisId}`)
  if (!sameFixtureId(GOLDEN_REVIEW.productId, GOLDEN_PRODUCT.productId)) errors.push(`reviewProductId:${GOLDEN_REVIEW.productId}`)
  if (GOLDEN_REVIEW.maxSeverity !== 'HIGH' || !outcome.findings.some((finding) => finding.severity === GOLDEN_REVIEW.maxSeverity)) errors.push(`reviewMaxSeverity:${GOLDEN_REVIEW.maxSeverity}`)
  for (const id of GOLDEN_REVIEW.selectedFindingIds) if (!findingIds.has(id)) errors.push(`reviewFindingId:${id}`)
  if (!sameFixtureId(GOLDEN_AUDIT_LOG.resourceId, GOLDEN_REVIEW.reviewId)) errors.push(`auditReviewId:${GOLDEN_AUDIT_LOG.resourceId}`)
  if (!sameFixtureId(GOLDEN_AUDIT_LOG.analysisId, GOLDEN_ANALYSIS.analysisId)) errors.push(`auditAnalysisId:${GOLDEN_AUDIT_LOG.analysisId}`)
  for (const id of GOLDEN_ANALYSIS.personaIds) if (!personaIds.has(id)) errors.push(`personaId:${id}`)
  for (const id of GOLDEN_ANALYSIS.evidenceDocumentIds) if (!evidenceIds.has(id)) errors.push(`evidenceDocumentId:${id}`)
  for (const run of outcome.personaRuns) if (!personaIds.has(run.personaId) || !sameFixtureId(run.analysisId, GOLDEN_ANALYSIS.analysisId)) errors.push(`personaRun:${run.runId}`)
  for (const result of outcome.redTeamResults) {
    if (!sameFixtureId(result.analysisId, GOLDEN_ANALYSIS.analysisId)) errors.push(`redTeamAnalysisId:${result.analysisId}`)
    for (const reference of result.evidenceReferences) if (!evidenceIds.has(reference.evidenceDocumentId)) errors.push(`evidenceDocumentId:${reference.evidenceDocumentId}`)
  }
  for (const finding of outcome.findings) {
    for (const id of finding.aiDetail.sourceRunIds) if (!runIds.has(id)) errors.push(`sourceRunId:${id}`)
    for (const id of finding.aiDetail.redTeamResultIds) if (!redTeamIds.has(id)) errors.push(`redTeamResultId:${id}`)
    for (const id of finding.aiDetail.groundTruthFactIds) if (!factIds.has(id)) errors.push(`groundTruthFactId:${id}`)
    for (const reference of finding.evidenceReferences) if (!evidenceIds.has(reference.evidenceDocumentId)) errors.push(`evidenceDocumentId:${reference.evidenceDocumentId}`)
  }
  for (const pattern of outcome.vulnerabilityPatterns) for (const id of pattern.findingIds) if (!findingIds.has(id)) errors.push(`findingId:${id}`)
  for (const suggestion of outcome.guardFitSuggestions) if (!patternIds.has(suggestion.patternId)) errors.push(`patternId:${suggestion.patternId}`)
  const numeric = [GOLDEN_PRODUCT.productId, GOLDEN_DOCUMENT.documentId, GOLDEN_ANALYSIS.analysisId, GOLDEN_REVIEW.reviewId, GOLDEN_AUDIT_LOG.auditId, ...outcome.findings.map((item) => item.findingId)]
  if (numeric.some((id) => typeof id !== 'number')) errors.push('entity-id:not-number')
  if (errors.length) throw new Error(`Golden Fixture 참조 오류: ${errors.join(', ')}`)
  return true
}

function sameFixtureId(left, right) {
  return String(left) === String(right)
}
