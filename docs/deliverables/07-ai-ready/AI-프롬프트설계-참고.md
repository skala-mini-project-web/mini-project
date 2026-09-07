# ARGUS AI 프롬프트 설계

## 목적

ARGUS 프롬프트는 위험을 자유롭게 추측하게 하는 질문이 아니다. backend가 고정한 confirmed document, selected evidence, retrieved context, persona/rule, VERIFIED fact snapshot 안에서 Finding을 반환하는 structured contract다.

## 실행 위치와 모델

- Prompt builder / option mapping: `ai-service/app/service.py`
- contract: `ai-service/app/schemas.py`
- AI service: FastAPI internal boundary
- chat model: Ollama `qwen2.5:7b-instruct`
- embedding selector: `bge-m3:latest`
- prompt version: `ollama-rag-grounded-v6`
- generation: `temperature: 0`, `seed: 42`
- response format: Pydantic JSON Schema

`latest`는 selector이며 immutable artifact claim이 아니다. backend retrieval snapshot은 runtime model metadata/digest가 제공되면 함께 기록한다.

## Prompt 입력

```text
- confirmedText: PM이 current extraction run/text hash로 확정한 문서
- personaCodes: active selected persona
- redTeamPackCode / ruleCodes
- selectedEvidenceDocumentIds: active evidence 1~3개
- retrievedContexts: pgvector ranked exact snapshot
- knownFacts: analysis 시점 VERIFIED fact snapshot
```

전체 corpus, 선택하지 않은 document, raw PDF binary, OCR artifact는 prompt에 넣지 않는다.

## 모델이 할 수 있는 일과 할 수 없는 일

Ollama는 각 Finding에 다음을 반환한다.

```text
statement / severity / policyRuleCode / affectedPersonaCodes
retrievedContextChunkIds / evidenceSpanOptionIds
knownFactIds / recommendation
```

- `evidenceSpanOptionIds`는 backend/FastAPI가 제공한 option 중에서만 고른다.
- 모델은 free-text excerpt를 새로 만들지 않는다.
- 모델은 raw `riskScore`를 response trace로 낼 수 있으나, 운영 score를 결정하지 않는다.

```text
Ollama option ID
  → FastAPI가 exact retrieved excerpt로 materialize
  → Spring이 execution/document/persona/fact/evidence scope 검증
  → immutable finding evidence anchor 저장
```

## System prompt 제약

1. JSON Schema 밖 자연어/Markdown/추가 필드를 반환하지 않는다.
2. selected persona/rule/fact/evidence/retrieved context 밖의 ID를 만들지 않는다.
3. `policyRuleCode`는 요청 rule 범위에 있어야 한다.
4. `retrievedContextChunkIds`는 전달된 context ID만 사용할 수 있다.
5. `evidenceSpanOptionIds`는 전달된 option만 사용할 수 있다.
6. document 원문을 evidence corpus처럼 인용하지 않는다.
7. certainty가 없으면 provided fact ID를 만들지 않는다.
8. duplicate context/persona/fact/option selection을 하지 않는다.

## Prompt 후 검증·저장

```text
Ollama JSON
→ Pydantic schema / option validation
→ exact excerpt materialization
→ Spring provider validation
→ current execution / document confirmation / selected input scope validation
→ immutable execution, retrieval/fact snapshot, finding anchor 저장
→ reviewer decision
→ reviewer-approved current anchor만 deterministic score ledger 입력
```

다음은 저장하지 않는다.

- 빈 Finding
- schema 위반 JSON
- retrieval snapshot 밖 chunk 또는 option
- 선택하지 않은 persona/evidence/fact/rule
- 임의 free-text evidence excerpt
- reviewer 승인 전 operating score

## 실패 처리

- Ollama 연결·모델 미설치: temporary failure, retryable
- schema/option/grounding 위반: provider response invalid, non-retryable
- RAG indexing/embedding/retrieval failure: analysis 상태·retryable metadata 표시
- provider 오류를 fixture output으로 자동 대체하지 않는다.

## 변경 원칙

- prompt, Ollama schema, FastAPI materialization, Spring validation, browser E2E를 함께 변경한다.
- RAG context 밖 fallback, free-text evidence, pre-review score promotion을 추가하지 않는다.
- model/prompt/retrieval version 변화는 execution snapshot과 QA evidence에 함께 반영한다.
