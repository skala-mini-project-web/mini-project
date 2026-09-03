# ai-service 호출 계약 (Backend ↔ ai-service)

Task 2-0 확정 사항. 백엔드는 이 계약만 알고, 구현체는 `HttpRiskAnalysisProvider` 하나다.

## 확정: ai-service 동기 / 백엔드 비동기

ai-service 는 job 을 만들지 않고 **요청 즉시 결과를 반환**한다. 따라서 CREATED→RUNNING→COMPLETED
전이는 **백엔드가 `@Async` 로 직접** 만든다. Frontend 계약(202 + statusUrl Polling)은 그대로 유지된다.

```
POST /api/analyses ──> CREATED 저장 + 커밋 ──> 202 응답
                            │ (AFTER_COMMIT, analysisTaskExecutor)
                            └─> RUNNING 커밋 ─> ai-service 동기 호출 ─> COMPLETED / FAILED 커밋
```

- `GET /api/analyses/{id}` 는 조회만 한다. Polling 횟수가 상태 전이 조건이 되지 않는다.
- 외부 HTTP 호출은 트랜잭션 밖에서 수행한다 (DB 커넥션 점유 금지, RUNNING 가시성 확보).

## 설정 (`application.yml`)

```yaml
ai-service:
  base-url: ${AI_SERVICE_URL:http://localhost:8000}
  connect-timeout: 2s
  read-timeout: 20s
  default-scenario-code: GUARANTEE_MISUNDERSTANDING_HIGH
```

## `POST {base-url}/internal/v1/risk-analyses`

### Request

| 필드 | 타입 | 출처 |
| --- | --- | --- |
| `analysisId` | number | `analyses.id` |
| `scenarioCode` | string | `X-Demo-Scenario` 헤더, 없으면 `default-scenario-code` |
| `confirmedText` | string | `product_documents.extracted_text` (confirmed=true 만) |
| `personaCodes` | string[1..3] | `persona_templates.code` |
| `redTeamPackCode` | string | `red_team_packs.code` |
| `ruleCodes` | string[1..] | Pack 의 활성 `red_team_rules.code` |
| `evidenceDocuments` | object[1..3] | `{id, sourceType, title, content}` |

### Response 200

```json
{
  "riskScore": 82,
  "modelVersion": "mock-risk-v1",
  "promptVersion": "mock-prompt-v1",
  "findings": [
    {
      "statement": "...",
      "severity": "HIGH",
      "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
      "evidenceReferences": [{ "evidenceDocumentId": 1, "excerpt": "..." }],
      "recommendation": "..."
    }
  ]
}
```

### Error `{errorCode, message, retryable}`

| 상황 | ai-service | 백엔드 `analyses.error_code` | retryable |
| --- | --- | --- | --- |
| 일시 장애 | 503 `AI_SERVICE_TEMPORARY_FAILURE` | `AI_SERVICE_TEMPORARY_FAILURE` | `true` |
| 응답 계약 위반 | 500 `PROVIDER_RESPONSE_INVALID` | `PROVIDER_RESPONSE_INVALID` | `false` |
| 미지원 시나리오 | 404 `SCENARIO_NOT_FOUND` | `PROVIDER_RESPONSE_INVALID` | `false` |
| 요청 스키마 불일치 | 422 `REQUEST_VALIDATION_FAILED` | `PROVIDER_RESPONSE_INVALID` | `false` |
| 연결 실패 / 타임아웃 | (응답 없음) | `AI_SERVICE_TEMPORARY_FAILURE` | `true` |

응답 본문의 `retryable` 을 우선 신뢰하고, 본문을 못 읽으면 5xx 만 재시도 가능으로 본다.
백엔드는 응답을 받은 뒤에도 계약을 다시 검증한다 — `findings` 비어 있음, `riskScore` 범위 초과,
**HIGH Finding 에 근거 인용 0건**이면 `PROVIDER_RESPONSE_INVALID`(재시도 불가)로 처리한다.

실패는 HTTP 오류로 나가지 않는다. `GET /api/analyses/{id}` 가 **200 + FAILED** 로
`errorCode` / `message` / `retryable` 을 함께 내려주고, `retryable=true` 일 때만 `POST .../retry` 가 허용된다.

## 지원 `scenarioCode`

`GUARANTEE_MISUNDERSTANDING_HIGH`(riskScore 82) · `EARLY_TERMINATION_COST_MEDIUM` ·
`ACCESSIBILITY_LOW` · `PROVIDER_RATE_LIMITED_THEN_SUCCESS`(503) · `PROVIDER_RESPONSE_INVALID`(500)

## Provider 교체

`RiskAnalysisProvider.analyze(AnalysisRequest) → AnalysisResult` 하나만 구현하면 된다.
현 MVP 의 ai-service 는 실제 LLM/RAG 가 아니라 결정론적 Fixture 를 반환한다(기능 명세서 §1.1).
실제 sLLM 으로 바꿀 때 ai-service 내부만 교체하면 백엔드는 변경이 없다.
