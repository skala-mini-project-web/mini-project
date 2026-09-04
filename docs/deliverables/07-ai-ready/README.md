# AI-Ready

- `AI-프롬프트설계-참고.md`: Ollama structured prompt와 RAG grounding 규칙
- `AI-입출력-JSON스키마-참고.md`: request·response JSON 규격
- `ai-logic-flow.png`: 분석·retrieval 처리 흐름
- `ai-prompt-design.png`: prompt boundary와 모델 출력 계약
- `ai-input-output-json-schema.png`: 입출력 schema 시각화

## 핵심 계약

- pgvector 검색 결과 chunk만 모델에 전달
- full-document·keyword fallback 없음
- Finding은 `retrievedContextChunkIds`와 exact `evidenceSpans`를 함께 반환
- backend가 chunk membership·excerpt containment·Persona·공식 사실 ID를 검증
- AI 내부 endpoint는 backend shared bearer token이 없으면 401 거부
