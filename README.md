# ARGUS

상품 판매 문서를 AI로 분석하고, **근거·검토·승인 이력**을 남기는 로컬 컴플라이언스 데모입니다.

ARGUS의 AI는 결과만 보여주지 않습니다. 승인된 근거 문서를 RAG로 검색하고, 실제로 검색된 chunk만 모델에 전달합니다. 모델의 Finding은 사람 검토를 거쳐야 Risk Pattern과 GuardFit 운영 가이드로 연결됩니다.

> `data/demo-corpus/`의 PDF·정책·규정·수치는 100% 합성 데모 데이터입니다. 실제 금융 상품·법령·규제기관·법률 또는 투자 자문이 아닙니다.

## 핵심 구성

- **Frontend**: Vue 3
- **Backend**: Spring Boot
- **Database**: PostgreSQL + pgvector
- **Embedding**: Ollama `bge-m3:latest` (1024 dimensions)
- **Analysis model**: Ollama `qwen2.5:7b-instruct`
- **Workflow**: PM → reviewer → Risk Pattern → GuardFit → 감사 로그

## 빠른 실행

### 1. Ollama 모델 준비

```bash
ollama serve
ollama pull bge-m3:latest
ollama pull qwen2.5:7b-instruct
```

`ollama serve`는 Ollama Desktop이 이미 실행 중이면 다시 실행할 필요가 없습니다.

### 2. 서비스 실행

```bash
docker compose up --build -d
docker compose ps
```

### 3. 접속 확인

- Frontend: http://localhost:5173
- Swagger: http://localhost:8080/swagger-ui/index.html
- Backend health: http://localhost:8080/actuator/health
- AI health: http://localhost:8000/internal/v1/health

AI health 응답의 provider가 `ollama`인지 확인합니다.

```bash
curl -fsS http://localhost:8000/internal/v1/health
```

## `Persona + Red Team 분석 시작`은 실제로 무엇을 하나

이 버튼은 화면용 점수 계산이나 Mock 결과 선택이 아닙니다.

PM이 다음을 선택합니다.

- 확정된 상품 문서 1개
- 활성 근거 문서 1~3개
- Persona 1~3개
- Red Team Pack 1개

backend는 실제 분석 작업을 시작합니다.

```text
입력 검증
→ Idempotency-Key와 분석 fingerprint 생성
→ 선택 근거 문서 chunking·embedding
→ pgvector top-6 검색
→ 검색된 chunk만 Ollama에 전달
→ Finding·severity·Persona·chunk ID 응답
→ 서버 검증·snapshot 저장
→ 결과 화면과 검토 요청
```

분석 결과에는 다음이 표시됩니다.

- risk score와 Finding
- 영향을 받는 Persona
- 각 Finding의 근거 문서와 서버가 해석한 원문 excerpt
- RAG 검색 버전·embedding model·query hash·검색 시각
- rank·similarity·source가 포함된 retrieval trace

모델이 검색하지 않은 문서를 인용하거나, 선택하지 않은 Persona·공식 사실을 결과에 넣으면 backend가 결과 저장을 거부합니다.

## Persona 구현

Persona는 역할극 문장이 아니라, **어떤 소비자 관점에서 위험을 점검할지 정의한 활성 기준 데이터**입니다.

- 금융 초보자: 확정수익·원금보장 오해
- 고령 금융소비자: 안정성 표현 오해·인지 접근성
- 손실 경험자: 손실 범위 인식·위험 민감도
- 단기 자금 필요: 중도해지 비용·유동성 제약
- 자영업자: 금리 변동·납부 조건·현금흐름

각 Persona는 `persona_templates`에 criteria, risk focus, active 상태로 저장됩니다.

UI는 실제 API에서 활성 Persona를 읽습니다. backend는 선택된 1~3개 Persona만 분석 요청에 넣고, 모델 응답의 Persona code가 이 범위를 벗어나면 저장하지 않습니다.

## Red Team Pack 구현

기본 `CORE_FINANCIAL_RISK_V1` Pack은 다음 위험을 점검합니다.

- 수익 강조 편향
- 손실 완화 표현
- 비용 누락
- 안정·보장 키워드
- 형식적 확인
- 인지 접근성

선택된 Pack의 활성 rule만 retrieval query와 AI 분석 조건에 사용됩니다.

## RAG와 AI 결과 검증 방식

1. 선택된 활성 근거 문서를 한국어 문장·문단 기준으로 chunking합니다.
2. 각 chunk를 `bge-m3:latest`로 embedding하고 pgvector에 저장합니다.
3. 상품 텍스트, Persona, Red Team rule을 query로 만들어 선택된 근거 범위에서 top-6을 검색합니다.
4. 검색된 context만 AI service와 Ollama에 전달합니다.
5. 모델은 원문을 새로 인용하지 않고, 전달받은 `retrievedContextChunkIds`만 선택합니다.
6. backend가 해당 chunk ID가 실제 retrieval snapshot에 포함되는지 검증합니다.
7. 검증된 chunk의 문서 ID·원문·rank·similarity를 결과와 감사 데이터에 저장합니다.

분석 시점의 공식 사실과 RAG retrieval 결과는 immutable snapshot으로 보관됩니다. 근거 문서가 나중에 바뀌어도 과거 분석의 근거를 확인할 수 있습니다.

분석 실행마다 `execution_token`을 새로 발급합니다. retry가 시작되면 이전 worker는 새 결과를 덮어쓸 수 없습니다. terminal 상태와 terminal audit event는 같은 DB transaction으로 기록됩니다.

## 실제 실행과 Mock의 차이

`docker compose` 기본 실행은 실제 API와 Ollama provider를 사용합니다.

- `VITE_USE_MOCK=false`
- `AI_PROVIDER=ollama`
- Ollama 또는 embedding 오류를 fixture 결과로 대체하지 않음

실제 RAG 전체 흐름은 아래 script로 검증합니다.

```bash
PLAYWRIGHT_EXECUTABLE_PATH="<local-chromium-path>" \
node frontend/scripts/real-rag-full-flow-e2e.mjs
```

이 script는 API interception이나 `mockServer`를 사용하지 않습니다.

```text
실제 PDF 업로드
→ PDFBox 추출·텍스트 확정
→ 공식 사실 검증
→ pgvector RAG
→ Ollama 분석
→ PM 검토 요청
→ reviewer 승인
→ Risk Pattern 활성화
→ GuardFit 승인
→ PM 확인
```

`frontend/scripts/smoke.mjs`, `frontend/scripts/ai-test.mjs`, `frontend/scripts/real-adapter-e2e.mjs`, backend fake provider 테스트는 빠른 회귀 검사용입니다. 실제 Ollama·pgvector E2E 성공 근거와 구분해야 합니다.

## 데모 PDF와 근거 자료

상품 입력 PDF:

- `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf`

RAG 근거 PDF:

- `data/demo-corpus/documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf`
- `data/demo-corpus/documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf`
- `data/demo-corpus/documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf`

재현·감사 자료:

- corpus inventory와 hash: `data/demo-corpus/manifest.v1.json`
- 문서 metadata: `data/demo-corpus/metadata/documents.v1.json`
- chunk metadata와 anchor: `data/demo-corpus/metadata/chunks.v1.jsonl`
- RAG·analysis expected case: `data/demo-corpus/expected/`

## 초기화

```bash
docker compose down      # 컨테이너만 종료
docker compose down -v   # DB·RAG index·분석·검토·감사 데이터를 모두 삭제
docker compose up --build -d
```

분석 요청 시 선택 근거를 자동으로 indexing합니다. 원문 hash, chunking version, embedding model이 같으면 기존 index를 재사용하고, 달라지면 새로 indexing합니다.

## 상세 문서

- Backend 실행·DB·API 안내: `backend/README.md`
- Frontend 실행·실서버 UI 안내: `frontend/README.md`
- Demo corpus·hash·제한 사항: `data/demo-corpus/README.md`
