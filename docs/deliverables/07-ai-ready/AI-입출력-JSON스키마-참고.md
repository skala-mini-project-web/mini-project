# ARGUS AI 입출력 JSON 스키마

## 계약 위치

- FastAPI schema: `ai-service/app/schemas.py`
- Spring outbound DTO: `backend/.../analysis/provider/dto/AnalysisRequest.java`
- Spring inbound DTO: `backend/.../analysis/provider/dto/AnalysisResult.java`, `FindingPayload.java`
- AI endpoint: `POST /internal/v1/risk-analyses`
- Health endpoint: `GET /internal/v1/health`

필드명은 camelCase JSON을 사용한다. 정의되지 않은 필드는 허용하지 않는다.

## AI 분석 요청

```json
{
  "analysisId": 101,
  "scenarioCode": "GUARANTEE_MISUNDERSTANDING_HIGH",
  "confirmedText": "PM이 확정한 상품 판매 문서 전체 텍스트",
  "personaCodes": [
    "FINANCIAL_BEGINNER",
    "SENIOR"
  ],
  "redTeamPackCode": "CORE_FINANCIAL_RISK_V1",
  "ruleCodes": [
    "RETURN_FRAMING",
    "LOSS_SOFTENING",
    "COST_OMISSION",
    "STABILITY_KEYWORD",
    "FORMAL_CONFIRMATION",
    "COGNITIVE_ACCESSIBILITY"
  ],
  "selectedEvidenceDocumentIds": [1, 2, 3],
  "retrievedContexts": [
    {
      "chunkId": 14,
      "evidenceDocumentId": 1,
      "sourceType": "INTERNAL_POLICY",
      "title": "금융상품 중요정보 표시 내부준칙 (데모)",
      "chunkText": "검색된 근거 문서의 정확한 chunk 원문",
      "rank": 1,
      "similarity": 0.82
    }
  ],
  "knownFacts": [
    {
      "factId": 7,
      "text": "확정된 공식 사실"
    }
  ]
}
```

### 요청 필드

- `analysisId`: 양의 정수
- `scenarioCode`: 1~80자 문자열. 실제 Ollama 운영 흐름에서는 분석 유형 식별자로 사용한다.
- `confirmedText`: PM이 확정한 비어 있지 않은 상품 문서 텍스트
- `personaCodes`: 1~4개, 중복 불가
- `redTeamPackCode`: 현재 `CORE_FINANCIAL_RISK_V1`
- `ruleCodes`: 1개 이상, 중복 불가
- `selectedEvidenceDocumentIds`: 1~3개 양의 정수, 중복 불가
- `retrievedContexts`: 1개 이상, rank가 1부터 연속적이고 chunk ID가 중복되지 않아야 함
- `knownFacts`: 분석 요청 시점의 VERIFIED 사실 snapshot. 없으면 빈 배열

### Persona code

```text
FINANCIAL_BEGINNER
SENIOR
LOSS_EXPERIENCED
SHORT_TERM_LIQUIDITY
SELF_EMPLOYED
```

### Red Team rule code

```text
RETURN_FRAMING
LOSS_SOFTENING
COST_OMISSION
STABILITY_KEYWORD
FORMAL_CONFIRMATION
COGNITIVE_ACCESSIBILITY
```

### Evidence source type

```text
INTERNAL_POLICY
REGULATION
PRODUCT_POLICY
```

### retrievedContext 제약

- `chunkId`, `evidenceDocumentId`, `rank`는 양의 정수
- `title`, `chunkText`는 비어 있을 수 없음
- `similarity` 범위는 `-1.0` 이상 `1.0` 이하
- 모든 context의 `evidenceDocumentId`는 `selectedEvidenceDocumentIds` 안에 있어야 함
- `rank`는 `[1, 2, ..., N]` 순서여야 함

## AI 분석 응답

```json
{
  "riskScore": 72,
  "modelVersion": "qwen2.5:7b-instruct",
  "promptVersion": "ollama-rag-grounded-v5",
  "findings": [
    {
      "statement": "안정·보장 표현이 변동 수익과 손실 가능성을 가릴 수 있습니다.",
      "severity": "HIGH",
      "affectedPersonaCodes": [
        "FINANCIAL_BEGINNER",
        "SENIOR"
      ],
      "retrievedContextChunkIds": [14, 22],
      "evidenceSpans": [
        {
          "chunkId": 14,
          "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다."
        }
      ],
      "knownFactIds": [7],
      "recommendation": "안정성 표현 가까이에 손실 가능성과 변동 수익 설명을 함께 표시하세요."
    }
  ]
}
```

### 응답 필드

- `riskScore`: 0~100 정수
- `modelVersion`: 비어 있지 않은 모델 버전
- `promptVersion`: 비어 있지 않은 prompt 버전
- `findings`: 1개 이상

각 Finding:

- `statement`: 1~1000자
- `severity`: `HIGH`, `MEDIUM`, `LOW`
- `affectedPersonaCodes`: 1개 이상, 중복 불가
- `retrievedContextChunkIds`: 1개 이상 양의 정수, 중복 불가
- `evidenceSpans`: 인용한 모든 chunk ID를 포함하는 exact excerpt 배열. excerpt는 해당 retrieved context 원문에 문자 단위로 포함되어야 함
- `knownFactIds`: 선택적 배열, 중복 불가
- `recommendation`: 선택적, 최대 1000자

## grounding 검증 규칙

응답 JSON이 schema를 통과해도 바로 저장하지 않는다.

```text
Finding.affectedPersonaCodes
  ⊆ 요청 personaCodes

Finding.retrievedContextChunkIds
  ⊆ 요청 retrievedContexts.chunkId

Finding.evidenceSpans.chunkId
  = Finding.retrievedContextChunkIds

Finding.evidenceSpans.excerpt
  ⊆ 해당 retrievedContext.chunkText

선택된 chunk의 evidenceDocumentId
  ⊆ 요청 selectedEvidenceDocumentIds

Finding.knownFactIds
  ⊆ 요청 knownFacts.factId
```

검증을 통과하면 backend가 chunk ID를 실제 retrieval snapshot의 문서 ID·원문 excerpt로 해석해 Finding evidence reference에 저장한다.

## 결과 조회 API의 RAG trace

`GET /api/analyses/{analysisId}/result`는 모델 응답만 보여주지 않고 다음 retrieval trace를 제공한다.

```json
{
  "analysisId": 101,
  "status": "COMPLETED",
  "riskScore": 72,
  "retrievalTrace": {
    "queryHash": "sha256...",
    "retrievalVersion": "pgvector-cosine-v1",
    "embeddingModel": "bge-m3:latest",
    "retrievedAt": "2026-09-04T...Z",
    "contexts": [
      {
        "chunkId": 14,
        "evidenceDocumentId": 1,
        "sourceType": "INTERNAL_POLICY",
        "title": "금융상품 중요정보 표시 내부준칙 (데모)",
        "rank": 1,
        "similarity": 0.82,
        "excerpt": "분석 시점에 저장한 exact chunk 원문"
      }
    ]
  },
  "groundTruthFacts": [],
  "findings": []
}
```

이 trace는 분석 시점 immutable snapshot이다. 이후 근거 문서가 바뀌어도 과거 분석의 검색 근거는 바뀌지 않는다.

## AI 오류 응답

```json
{
  "errorCode": "AI_PROVIDER_RESPONSE_INVALID",
  "message": "The provider response does not match the analysis contract.",
  "retryable": false
}
```

주요 분류:

- `AI_SERVICE_TEMPORARY_FAILURE`: Ollama 연결·일시 장애. `retryable: true`
- `AI_PROVIDER_RESPONSE_INVALID`: JSON schema 또는 grounding contract 위반. `retryable: false`
- Spring backend는 분석 상태, error code, retryable 정보를 결과 화면에 별도로 저장·표시한다.

## 의도적으로 허용하지 않는 구조

- 전체 evidence document 본문을 AI에 전달하는 필드
- 모델이 임의로 작성한 evidence excerpt를 신뢰하는 필드
- 검색하지 않은 document ID·chunk ID
- 선택하지 않은 Persona·공식 사실 ID
- JSON Schema 밖의 추가 필드
