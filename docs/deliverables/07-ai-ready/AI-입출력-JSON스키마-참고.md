# ARGUS AI 입출력 JSON 스키마

## 계약 위치

- FastAPI schema: `ai-service/app/schemas.py`
- Spring provider DTO/validation: `backend/.../analysis/provider/`
- FastAPI endpoint: `POST /internal/v1/risk-analyses`
- health endpoint: `GET /internal/v1/health`

camelCase JSON만 사용하며 정의되지 않은 필드는 허용하지 않는다. 모델·FastAPI·Spring의 역할을 분리한다.

```text
Spring → FastAPI: RiskAnalysisRequest
FastAPI → Ollama: OllamaRiskAnalysisResponse (option ID만 선택)
FastAPI → Spring: RiskAnalysisResponse (option을 exact excerpt로 materialize)
Spring: execution / scope / anchor 검증 후 저장
```

## Spring → FastAPI 분석 요청

```json
{
  "analysisId": 101,
  "scenarioCode": "GUARANTEE_MISUNDERSTANDING_HIGH",
  "confirmedText": "PM이 현재 run/text-hash로 확정한 상품 문서 텍스트",
  "personaCodes": ["FINANCIAL_BEGINNER", "LOSS_RECOVERY_PRESSURE"],
  "redTeamPackCode": "CORE_FINANCIAL_RISK_V1",
  "ruleCodes": ["STABILITY_KEYWORD", "LOSS_SOFTENING"],
  "selectedEvidenceDocumentIds": [1, 2],
  "retrievedContexts": [
    {
      "chunkId": 14,
      "evidenceDocumentId": 1,
      "sourceType": "INTERNAL_POLICY",
      "title": "합성 내부 정책",
      "chunkText": "immutable retrieval snapshot의 exact chunk 원문",
      "rank": 1,
      "similarity": 0.82
    }
  ],
  "knownFacts": [{"factId": 7, "text": "VERIFIED fact snapshot"}]
}
```

### 요청 제약

- `confirmedText`: 비어 있지 않고 최대 20,000자
- `personaCodes`: 1개 이상, 중복 불가, active selected persona 범위
- `ruleCodes`: 1개 이상, 중복 불가, Red Team scenario와 호환
- `selectedEvidenceDocumentIds`: 1~3개, active evidence만 허용
- `retrievedContexts`: contiguous rank, unique `chunkId`, selected evidence 범위
- `knownFacts`: 요청 시점 VERIFIED fact snapshot, unique ID
- prompt aggregate text는 100,000 UTF-8 bytes를 넘을 수 없다.

## Ollama가 선택하는 응답

Ollama는 exact excerpt를 자유 생성하지 않는다. 다음처럼 `evidenceSpanOptionIds`만 선택한다.

```json
{
  "riskScore": 72,
  "modelVersion": "qwen2.5:7b-instruct",
  "promptVersion": "ollama-rag-grounded-v6",
  "findings": [
    {
      "statement": "안정·보장 표현이 변동 수익과 손실 가능성을 가릴 수 있습니다.",
      "severity": "HIGH",
      "policyRuleCode": "STABILITY_KEYWORD",
      "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
      "retrievedContextChunkIds": [14],
      "evidenceSpanOptionIds": ["chunk-14-option-2"],
      "knownFactIds": [7],
      "recommendation": "안정성 표현 가까이에 손실 가능성과 변동 수익 설명을 함께 표시하세요."
    }
  ]
}
```

## FastAPI → Spring materialized response

FastAPI는 option ID가 현재 request의 retrieved context 안에 있는지 확인하고, 서버가 만든 exact excerpt로 변환한다.

```json
{
  "riskScore": 72,
  "modelVersion": "qwen2.5:7b-instruct",
  "promptVersion": "ollama-rag-grounded-v6",
  "findings": [
    {
      "statement": "...",
      "severity": "HIGH",
      "policyRuleCode": "STABILITY_KEYWORD",
      "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
      "retrievedContextChunkIds": [14],
      "evidenceSpans": [
        {"chunkId": 14, "excerpt": "retrieval snapshot에서 선택된 exact excerpt"}
      ],
      "knownFactIds": [7],
      "recommendation": "..."
    }
  ]
}
```

`riskScore`는 provider output trace일 뿐 운영 점수가 아니다. 운영용 score는 reviewer APPROVED 이후 Spring이 Policy v1로 재계산해 ledger로 저장한다.

## Spring grounding·execution 검증

```text
Finding.affectedPersonaCodes       ⊆ requested personaCodes
Finding.policyRuleCode             ∈ requested ruleCodes
Finding.retrievedContextChunkIds   ⊆ requested retrievedContexts.chunkId
Finding.evidenceSpans.chunkId      ⊆ retrievedContextChunkIds
Finding.evidenceSpans.excerpt      ⊆ exact retrieved chunk text
Finding.knownFactIds               ⊆ requested knownFacts.factId

current analysis execution + confirmed document + selected evidence
+ exact anchor/revision scope를 모두 만족해야 저장
```

검증 후 Spring은 `analysis_executions`, retrieval/fact snapshots, `finding_evidence_anchors`, finding revision을 저장한다. 모델이 만든 free-text excerpt, 임의 document/chunk/persona/fact ID, schema 밖 필드는 저장하지 않는다.

## 결과 조회와 score

`GET /api/analyses/{analysisId}/result`는 immutable retrieval trace, finding evidence, review 상태, score state를 제공한다.

- `PENDING_REVIEW`: 사람이 승인하기 전, score 미산출
- `SCORED`: reviewer-approved current execution/exact anchor로 deterministic score ledger 생성
- `NOT_SCORED`: review rejection 등 promotion 조건 미충족

## 오류 계약

- `AI_SERVICE_TEMPORARY_FAILURE`: Ollama 연결·일시 장애, retryable
- `AI_PROVIDER_RESPONSE_INVALID`: JSON schema/option/grounding contract 위반, non-retryable
- RAG indexing·embedding·retrieval failure: analysis 상태와 retryable metadata로 표시
- provider 오류를 fixture 결과로 자동 대체하지 않는다.

## 의도적으로 허용하지 않는 구조

- 전체 evidence document 본문을 prompt에 넣는 필드
- 모델이 작성한 free-text evidence excerpt를 신뢰하는 필드
- retrieval snapshot 밖 chunk / 선택되지 않은 evidence/persona/fact
- raw LLM risk score를 reviewer 승인 전 운영 점수로 쓰는 구조
- JSON Schema 밖의 추가 필드
