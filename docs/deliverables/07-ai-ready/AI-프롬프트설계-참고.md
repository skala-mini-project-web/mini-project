# ARGUS AI 프롬프트 설계

## 목적

ARGUS 프롬프트는 판매 문서에서 위험을 자유롭게 추측하게 하는 질문이 아니다. backend가 검색한 RAG context, 선택 Persona, Red Team rule, VERIFIED 공식 사실 범위 안에서만 구조화된 Finding을 반환하게 하는 계약이다.

## 실행 위치와 모델

- Prompt builder: `ai-service/app/service.py`
- AI service: FastAPI
- Chat model: Ollama `qwen2.5:7b-instruct`
- Embedding model: Ollama `bge-m3:latest`
- Prompt version: `ollama-rag-grounded-v6`
- Generation options: `temperature: 0`, `seed: 42`
- Output format: Pydantic `RiskAnalysisResponse` JSON Schema

`temperature: 0`과 `seed: 42`는 같은 입력에서 시연 결과를 최대한 재현 가능하게 만들기 위한 설정이다. 모델 결과는 여전히 서버의 contract validation을 통과해야만 저장된다.

## Prompt 입력 구성

AI service는 user message에 아래 JSON만 넣는다.

```text
- confirmedText: PM이 확정한 상품 문서 텍스트
- personaCodes: 선택된 소비자 Persona 1~4개
- redTeamPackCode: 선택 Red Team Pack
- ruleCodes: 활성 위험 점검 rule
- selectedEvidenceDocumentIds: 선택된 근거 문서 ID 1~3개
- retrievedContexts: pgvector가 검색한 rank별 context
- knownFacts: 분석 시점 VERIFIED 공식 사실 snapshot
```

전체 근거 문서 corpus나 선택하지 않은 문서는 prompt에 넣지 않는다.

## System Prompt의 핵심 제약

현재 system prompt는 다음을 강제한다.

1. 제공된 JSON Schema에 맞는 JSON만 반환한다.
2. `confirmedText`, 선택 Persona·rule, 검색된 context 밖의 사실을 추론하지 않는다.
3. 전체 근거 문서를 요청하거나 사용하지 않는다.
4. Finding의 `retrievedContextChunkIds`에는 전달된 `retrievedContexts`의 `chunkId`만 넣는다.
5. 인용한 각 chunk에는 원문에서 그대로 복사한 `evidenceSpans` excerpt를 하나 이상 넣는다.
6. 상품 원문을 근거 문서처럼 인용하거나, 검색되지 않은 context ID를 만들지 않는다.
7. Finding의 `affectedPersonaCodes`에는 선택된 Persona만 넣는다.
8. `knownFactIds`에는 제공된 공식 사실 ID만 넣고, 확신이 없으면 빈 배열을 쓴다.
9. `modelVersion`, `promptVersion`은 실행 모델과 prompt version으로 반환한다.

## RAG context를 chunk ID로 인용하는 이유

모델에게 근거 문장을 글자 단위로 다시 복사하게 하면 문장부호·공백·표현 차이로 검증이 불안정해질 수 있다.

ARGUS는 모델이 `retrievedContextChunkIds`와 함께 정확한 원문 `evidenceSpans`를 선택하게 한다.

```text
모델
  → “이 Finding의 근거는 chunk 14, chunk 22” 선택
  → 각 chunk의 exact excerpt를 evidenceSpans로 반환

backend
  → 14, 22가 실제 outbound retrieval snapshot에 있는지 검증
  → excerpt가 해당 chunk 원문에 문자 단위로 포함되는지 검증
  → 해당 chunk의 evidenceDocumentId·exact text·rank·similarity를 결과에 연결
```

따라서 모델은 검색하지 않은 근거를 인용하거나 근거와 다른 excerpt를 만들 수 없고, UI에는 서버가 보관한 정확한 snapshot 원문이 표시된다.

## Prompt 요청 형태

Ollama `/api/chat` 요청은 아래 구조다.

```json
{
  "model": "qwen2.5:7b-instruct",
  "messages": [
    {"role": "system", "content": "분석 범위와 grounding 제약"},
    {"role": "user", "content": "RiskAnalysisRequest JSON"}
  ],
  "format": "RiskAnalysisResponse JSON Schema",
  "stream": false,
  "options": {
    "temperature": 0,
    "seed": 42
  }
}
```

`format`에는 Pydantic이 생성한 response JSON Schema를 전달한다. 모델이 자연어 설명이나 Markdown 대신 구조화된 JSON을 반환하도록 제한한다.

## Prompt 후 서버 검증

프롬프트 제약만으로 결과를 신뢰하지 않는다. FastAPI와 Spring backend가 모두 검증한다.

```text
Ollama JSON 응답
→ Pydantic schema validation
→ Persona 범위 검증
→ retrievedContextChunkIds 검증
→ selected evidence 범위 검증
→ knownFactIds 검증
→ Spring provider validation
→ Finding·RAG snapshot·audit 저장
```

다음은 저장하지 않는다.

- 빈 Finding 목록
- schema와 다른 JSON
- 선택하지 않은 Persona code
- 검색되지 않은 chunk ID
- 선택하지 않은 evidence document
- 제공되지 않은 공식 사실 ID
- 중복 chunk ID·중복 fact ID

## 실패 처리

- Ollama 연결 실패 또는 모델 미설치: `AI_SERVICE_TEMPORARY_FAILURE`, 재시도 가능
- JSON schema 위반 또는 grounding 위반: `PROVIDER_RESPONSE_INVALID`, 재시도 불가
- RAG indexing·embedding·검색 실패: 실패 상태와 retryable 여부를 결과 화면에 표시

AI 오류를 fixture 결과로 자동 대체하지 않는다. 실제 Ollama 모드에서는 실제 provider 오류를 그대로 반환한다.

## 변경 시 지켜야 할 원칙

- system prompt, request schema, response schema, backend provider validation을 함께 변경한다.
- RAG context 밖의 근거를 허용하는 fallback을 추가하지 않는다.
- 실제 모델 검증과 fixture/mock 검증을 같은 근거로 표현하지 않는다.
- prompt version을 변경하면 결과와 retrieval snapshot의 재현성 기준도 함께 갱신한다.
