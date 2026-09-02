# GuardLab AI 분석 설계 (RiskAnalysisProvider)

> 이 문서는 **AI 확장 지점**의 구현 규격이다. 프론트엔드는 Interface First 원칙에 따라
> 결과 JSON 계약만 소비하므로, Mock이든 실제 LLM이든 **화면 코드는 바뀌지 않는다.**
> 실제 LLM 호출과 RAG 색인은 **백엔드(Spring)** 의 `RiskAnalysisProvider` 구현이 담당한다.
> (FE는 API 키를 다루지 않는다. 키 노출·CORS 문제 때문에 클라이언트 직접 호출은 금지.)

## 1. Provider 인터페이스 (교체 지점)

```
interface RiskAnalysisProvider {
  AnalysisResult analyze(AnalysisRequest request);   // 동기 계약, 비동기 잡으로 감싼다
}
```

| 구현 | 상태 | 비고 |
|---|---|---|
| `MockRiskAnalysisProvider` | 현재 (FE mock 포함) | 결정론적 Fixture. `scenarioCode`로 선택 |
| `SpringAiRiskAnalysisProvider` | 실제 (P2) | 외부 LLM(OpenAI, Anthropic 등). 아래 프롬프트·스키마·가드레일 사용 |
| `LocalLlmRiskAnalysisProvider` | 실제 (P2) | 로컬 LLM. 동일 계약 |

교체는 설정만으로: `AI_PROVIDER=mock|openai|anthropic`, `AI_MODEL`, `AI_API_KEY`, `AI_TEMPERATURE`
(코드/화면 변경 없음). FE는 `VITE_USE_MOCK=false`면 `/api`로 프록시할 뿐이다.

## 2. 역할 부여(System) 프롬프트

```
너는 금융소비자 보호 관점의 '표현 리스크 분석가'다. 입력으로 주어진 금융상품 설명 문서의
확정 텍스트에서, 소비자가 오인할 수 있는 표현을 찾아 구조화된 JSON으로만 보고한다.

원칙:
- 오직 입력 문서(sourceText)에 실제로 존재하는 문구만 근거(excerpt)로 인용한다. 문서에 없는
  표현을 지어내지 않는다.
- 각 지적(finding)은 제공된 Red Team 규칙(ruleCodes) 중 하나에 매핑한다.
- 각 지적의 영향 대상은 제공된 Persona 집합 안에서만 고른다.
- 심각도가 HIGH이면 제공된 근거 문서(evidence)에서 최소 1건을 인용한다.
- 법령·내부준칙 인용은 제공된 근거 문서 범위 안에서만 한다. 법률 자문을 창작하지 않는다.
- 확신이 없으면 지적을 만들지 말고 비운다(NO_FINDING). 과잉 지적보다 정확성이 우선이다.
- 출력은 아래 JSON 스키마를 100% 준수한다. 스키마 밖 텍스트/설명/마크다운을 출력하지 않는다.
- riskScore는 네가 임의로 정하지 않는다(§5 점수 정책은 서버가 계산).
```

## 3. 입력(Input) 스키마 — 서버가 Provider에 전달

```json
{
  "sourceText": "확정된 상품 설명 텍스트 (verifiedText)",
  "sourceDocument": { "documentId": "PDOC-001", "fileName": "…pdf", "mediaType": "application/pdf" },
  "personas": [
    { "code": "FINANCIAL_BEGINNER", "name": "금융 초보자", "riskFocus": "수익 보장 오해·비용 누락" }
  ],
  "ruleCodes": ["RETURN_FRAMING","LOSS_SOFTENING","COST_OMISSION","STABILITY_KEYWORD","FORMAL_CONFIRMATION","COGNITIVE_ACCESSIBILITY"],
  "evidence": [
    { "documentId": "POLICY-003", "title": "금융상품 중요정보 표시 내부준칙 (데모)", "excerpts": ["원금손실 가능성은 안정성 표현과 인접해 표시해야 합니다."] }
  ]
}
```

## 4. 출력(Output) 스키마 — `AnalysisResult` (FE가 소비하는 계약과 동일)

```json
{
  "findings": [
    {
      "findingType": "FRAMING | OMISSION | MISUNDERSTANDING | ACCESSIBILITY",
      "ruleCode": "<ruleCodes 중 하나>",
      "severity": "HIGH | MEDIUM | LOW",
      "message": "사용자가 이해 가능한 1~1000자 진술",
      "sourceReference": { "page": 1, "excerpt": "<sourceText의 실제 부분 문자열>" },
      "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
      "evidenceReferences": [ { "documentId": "POLICY-003", "excerpt": "<evidence의 실제 부분 문자열>", "sourceType": "INTERNAL_POLICY" } ],
      "recommendation": "구체적 위치·표현 방향 (1~1000자)"
    }
  ]
}
```

- `findingId`, `sourceReference.documentId`, `riskScore`는 **서버가 채운다**(LLM이 만들지 않는다).
- `findings`가 비면 정상 저위험(NO_FINDING), riskScore=0.

## 5. 점수 정책 (LLM이 아니라 서버가 계산 · 결정론)

```
severityBase = max(HIGH=60, MEDIUM=35, LOW=15)
personaBonus = min(15, 5 * distinctAffectedPersonaCount)
ruleBonus    = min(12, 3 * distinctTriggeredRuleCount)
groundingBonus = 6  (HIGH가 있고 모든 HIGH가 근거를 가질 때)
riskScore = min(100, severityBase + personaBonus + ruleBonus + groundingBonus)
```

## 6. 가드레일 (검증 실패 시 처리)

Provider 응답을 서버가 후처리·검증하고, 위반 시 `AnalysisStatus=FAILED`,
`errorCode=PROVIDER_RESPONSE_INVALID`, `retryable=false`로 저장한다(400을 내지 않는다).

- [ ] **스키마 검증**: JSON 파싱 + `analysis-result.schema.json` 통과. 스키마 밖 텍스트 → 실패.
- [ ] **근거 실재성(anti-hallucination)**: 모든 `sourceReference.excerpt`는 `sourceText`의
      **부분 문자열**이어야 한다(정규화 후). 아니면 그 finding을 폐기(또는 전체 실패).
- [ ] **규칙 범위**: `ruleCode ∈ 요청 ruleCodes`.
- [ ] **Persona 범위**: `affectedPersonaCodes ⊆ 요청 personas`, 최소 1개.
- [ ] **근거 범위**: `evidenceReferences[].documentId ∈ 요청 evidence`. HIGH는 ≥1건.
- [ ] **길이/필드**: message·recommendation 1~1000자, 필수 필드 존재.
- [ ] **점수 무시**: 응답에 riskScore가 있어도 버리고 §5로 재계산.
- [ ] **PII·비밀·원문 전문**을 로그에 남기지 않는다. 프롬프트/응답 원문 로깅 금지.
- [ ] **재현성**: temperature 낮게(≤0.2), 동일 입력+모델은 가능한 한 동일 결과. 실제
      LLM은 완전 결정론이 아니므로 `scorePolicyVersion`/`promptVersion`을 결과에 기록.

## 7. RAG 계획 (P2)

- **색인**: `ProductDocument`(대상 문서)와 승인된 `EvidenceDocument`(법령·내부준칙)를
  **별도 컬렉션**으로 임베딩 색인한다. 상품 문서를 근거로 오염시키지 않기 위해 분리한다.
- **검색**: 분석 시 대상 문서의 문단을 쿼리로 근거 컬렉션에서 top-k 발췌를 검색해
  프롬프트의 `evidence.excerpts`로 주입한다. LLM은 주입된 근거 안에서만 인용한다.
- **인용 추적**: 검색된 발췌의 `documentId`를 그대로 `evidenceReferences.documentId`로 반환해
  결과에서 원본 근거로 역추적 가능하게 한다.
- **현재 상태**: RAG는 미구현(P2). 지금은 Mock/시드 근거를 주입하며, 위 §6 근거-범위 가드레일이
  RAG 도입 후에도 동일하게 인용을 강제한다.

## 8. 비동기 계약 (변하지 않음)

`POST /api/analyses` → 202 + statusUrl. Provider 실행은 워커에서 수행하고, GET 상태는
read-only. 완료 시 `GET /api/analyses/{id}/result`가 위 `AnalysisResult`를 반환한다.
FE 폴링(1초, 30초 후 중단) 및 결과 화면은 Mock/실제와 무관하게 동일하다.
