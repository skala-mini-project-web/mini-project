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

### 전송 보안

요청 본문에는 상품 설명 확정 텍스트와 근거 문서 원문이 실린다. 평문 전송을 막기 위해
`base-url` 이 loopback(`localhost` / `127.0.0.0/8` / `::1`) 이 아니면 **`https` 만 허용**하며,
아니면 애플리케이션 기동 시점에 실패한다.

```
ai-service.base-url 은 https 여야 합니다 (http 는 localhost 만 허용): http://ai.example.com
```

호출은 POST 라 Spring 의 `SimpleClientHttpRequestFactory` 가 리다이렉트를 따라가지 않는다
(`setInstanceFollowRedirects("GET".equals(httpMethod))`). 별도 설정이 필요 없다.

## `POST {base-url}/internal/v1/risk-analyses`

### Request

모든 필드는 camelCase이고, 아래에서 선택 사항으로 표시한 `knownFacts` 외에는 필수다. 알 수 없는
필드는 거부한다.

| 필드 | 타입·범위 | 출처·제약 |
| --- | --- | --- |
| `analysisId` | positive integer | `analyses.id` |
| `scenarioCode` | nonblank string, 1..80 | `X-Demo-Scenario` 헤더, 없으면 `default-scenario-code`; 알려진 시나리오는 필요한 `ruleCode`를 포함해야 함 |
| `confirmedText` | nonblank string, 1..20,000 | 분석에 고정된 `product_documents.extracted_text` |
| `personaCodes` | unique enum[1..12] | 선택한 `persona_templates.code` |
| `redTeamPackCode` | enum | 선택한 `red_team_packs.code` |
| `ruleCodes` | unique enum[1..6] | Pack의 활성 `red_team_rules.code` |
| `selectedEvidenceDocumentIds` | unique positive integer[1..3] | 선택한 활성 근거 문서 ID |
| `retrievedContexts` | object[1..20] | 검색 시점에 고정한 rank 순서의 청크 |
| `knownFacts` | object[0..50], 선택(기본 `[]`) | `{factId, text}` 형태의 선택적 provenance |

`retrievedContexts[]`는 `{chunkId, evidenceDocumentId, sourceType, title, chunkText, rank,
similarity}`를 모두 요구한다. ID와 rank는 양수, `title`은 nonblank 1..255, `chunkText`는
nonblank 1..8,000, `similarity`는 -1..1이다. `chunkId`는 중복될 수 없고 rank는 배열 순서대로
1부터 끊김 없이 증가해야 하며, 모든 `evidenceDocumentId`는
`selectedEvidenceDocumentIds`에 속해야 한다.

`knownFacts[]`는 양수 `factId`와 nonblank `text`(1..2,000)를 요구하고 ID가 중복될 수 없다.
시나리오·확정 원문·검색 청크 제목/본문·known fact 본문을 합친 prompt text는 UTF-8
100,000 bytes 이하다.

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
      "policyRuleCode": "STABILITY_KEYWORD",
      "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
      "retrievedContextChunkIds": [11],
      "evidenceSpans": [{ "chunkId": 11, "excerpt": "원금손실 가능성" }],
      "knownFactIds": [7],
      "docClaim": { "excerpt": "확정 문서에 실제로 있는 비판 대상 문구" },
      "recommendation": "..."
    }
  ]
}
```

최상위 및 Finding의 알 수 없는 필드는 거부한다.

| 필드 | 필수 여부 | 타입·범위 |
| --- | --- | --- |
| `riskScore` | 선택, nullable | integer 0..100인 진단 provenance. `findings: []`이면 반드시 null 또는 생략 |
| `modelVersion` | 필수 | nonblank string 1..50 |
| `promptVersion` | 필수 | nonblank string 1..50 |
| `findings` | 필수 | array 0..20; 누락/null/21건 이상 거부 |
| `findings[].statement` | 필수 | nonblank string 1..1,000 |
| `findings[].severity` | 필수 | `HIGH`, `MEDIUM`, `LOW` |
| `findings[].policyRuleCode` | 필수 | 요청 `ruleCodes` 중 정확히 하나 |
| `findings[].affectedPersonaCodes` | 필수 | unique enum[1..12], 요청 `personaCodes`의 부분집합 |
| `findings[].retrievedContextChunkIds` | 필수 | unique positive integer[1..20], 요청 `retrievedContexts[].chunkId`의 부분집합 |
| `findings[].evidenceSpans` | 필수 | object[1..60] |
| `findings[].knownFactIds` | 선택(기본 `[]`) | unique positive integer[0..50], 요청 `knownFacts[].factId`의 부분집합 |
| `findings[].docClaim` | 필수, nonnull | `{excerpt}` |
| `findings[].recommendation` | 선택, nullable | string 0..1,000 |

`evidenceSpans[]`는 positive `chunkId`와 nonblank `excerpt`(1..8,000)를 요구한다. 각 span은
해당 Finding이 지목한 청크만 참조하고 그 청크 원문에 정확히 포함되어야 하며,
`(chunkId, excerpt)` 쌍은 중복될 수 없다. 지목된 모든 청크에는 span이 하나 이상 있어야 한다.
즉 `retrievedContextChunkIds`/`evidenceSpans`는 정책·규정 근거 역할이며 선택된 검색 스냅샷
밖의 인용, 보정 인용, 역할 혼합을 허용하지 않는다.

`docClaim.excerpt`는 nonnull/nonblank이고 **Unicode code point 1..400개**여야 한다. 공백을
trim하거나 문구를 보정하지 않고 요청 `confirmedText`에 그대로 포함되어야 하며, 겹치는
출현까지 세어 정확히 한 번만 나타나야 한다. 앞뒤 공백도 원문과 정확히 일치하면 유효하다.
HTTP 어댑터와 비동기 Job 서비스가 각각 이 계약을 검증하고, Job 서비스는 분석에 고정된 문서
revision 원문에서 같은 범위를 다시 찾은 뒤 그 명시적 범위로 Spring anchor를 만든다.

`knownFactIds`는 선택적 provenance일 뿐 문서 claim anchor가 아니다. 값이 있어도 `docClaim`을
생략할 수 없고, 첫 known fact 또는 다른 fact에서 claim을 추론하지 않는다.

`ollama-rag-grounded-v18` 내부 출력은 공개 응답의 `docClaim` 대신 `docClaimOptionId`를
선택한다. ai-service가 `confirmedText` 전체에서 만든 별도의 400-code-point 문서 window
옵션과 정책 근거용 `evidenceSpanOptionIds`는 서로 다른 선택 집합이다. 서버가 선택 ID를 원문
`docClaim.excerpt`로 매핑해 위 공개 JSON을 만든 뒤 grounding을 검증하며, Spring backend가
고정 revision 원문을 기준으로 다시 검증한다.

### Error `{errorCode, message, retryable}`

| 상황 | ai-service | 백엔드 `analyses.error_code` | retryable |
| --- | --- | --- | --- |
| 일시 장애 | 429/5xx | `AI_SERVICE_TEMPORARY_FAILURE` | `true` |
| 200 응답 계약 위반 | 잘못된 JSON/필드/grounding | `PROVIDER_RESPONSE_INVALID` | `false` |
| 미지원 시나리오 | 404 `SCENARIO_NOT_FOUND` | `PROVIDER_RESPONSE_INVALID` | `false` |
| 요청 스키마 불일치 | 422 `REQUEST_VALIDATION_FAILED` | `PROVIDER_RESPONSE_INVALID` | `false` |
| 연결 실패 / 타임아웃 | (응답 없음) | `AI_SERVICE_TEMPORARY_FAILURE` | `true` |

재시도 여부는 HTTP status가 정본이다. 429/5xx는 재시도 가능, 그 밖의 4xx와 200 응답 계약
위반은 재시도 불가다. 오류 본문의 `errorCode`/`message`는 상세 기록에만 사용한다.

실패는 HTTP 오류로 나가지 않는다. `GET /api/analyses/{id}` 가 **200 + FAILED** 로
`errorCode` / `message` / `retryable` 을 함께 내려주고, `retryable=true` 일 때만 `POST .../retry` 가 허용된다.

## 지원 `scenarioCode`

`GUARANTEE_MISUNDERSTANDING_HIGH`(riskScore 82) · `EARLY_TERMINATION_COST_MEDIUM` ·
`ACCESSIBILITY_LOW` · `PROVIDER_RATE_LIMITED_THEN_SUCCESS`(503) · `PROVIDER_RESPONSE_INVALID`(500)

## Provider 교체

`RiskAnalysisProvider.analyze(AnalysisRequest) → AnalysisResult` 하나만 구현하면 된다.
ai-service의 결정론적 Fixture provider와 Ollama provider가 같은 공개 계약을 사용한다.
provider를 교체해도 백엔드의 검증·고정 revision anchor 계약은 바뀌지 않는다.
