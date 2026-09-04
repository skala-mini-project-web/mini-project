# ARGUS 금융상품 판매 리스크 사전검증 AI 플랫폼

ARGUS는 상품 판매 문서를 분석하고, 검색 근거·사람 검토·운영 조치 이력을 남기는 **로컬 AI 컴플라이언스 데모**입니다.

## 범위와 데이터 고지

- 이 저장소는 로컬 데모·포트폴리오 용도입니다.
- `data/demo-corpus/`의 PDF·정책·규정·수치는 100% 합성 데이터입니다.
- 실제 금융 상품·법령·규제기관·법률 또는 투자 자문을 의미하지 않습니다.
- 실서비스 인증, 개인정보 처리, 원격 배포, 모델 라이선스·자원 운영은 이 데모 범위 밖입니다.

## 무엇을 보여주나

```text
PM
상품 등록 → PDF 업로드 → 텍스트 확정 → 공식 사실 확인
→ Persona + Red Team 분석 → RAG 근거 확인 → 검토 요청

reviewer
검토함 → 원문·Finding·RAG trace 확인 → 승인 또는 반려

승인
선택 Finding → Risk Pattern → GuardFit 승인 → PM 가이드 확인

반려
반려 사유 → PM 수정 → 새 분석 → 새 검토
```

```text
Vue 3 Frontend
        ↓ REST
Spring Boot Backend
  ├─ 상품·문서·분석·검토·감사 workflow
  ├─ PDFBox text extraction
  └─ RAG indexing / retrieval
        ↓                         ↓
PostgreSQL + pgvector       FastAPI AI Service
                                     ↓
                              Local Ollama
                        bge-m3 embedding + qwen analysis
```

AI는 검색 결과만으로 운영 조치를 자동 실행하지 않습니다. reviewer가 승인한 Finding만 Risk Pattern이 되고, GuardFit도 별도 승인해야 PM에게 보입니다.

## 사전 조건

- Docker Desktop과 Docker Compose
- 로컬 Ollama
- `bge-m3:latest`, `qwen2.5:7b-instruct` 모델을 저장할 디스크와 메모리

```bash
ollama serve
ollama pull bge-m3:latest
ollama pull qwen2.5:7b-instruct
ollama list
```

Ollama Desktop이 이미 서버를 실행 중이면 `ollama serve`를 다시 실행할 필요가 없습니다.

## 설정

기본값으로 바로 실행할 수 있습니다. 포트·DB·Ollama 연결을 바꿀 때만 `.env`를 만듭니다.

```bash
cp .env.example .env
```

주요 설정값:

```dotenv
DB_NAME=crosschecklab
DB_USERNAME=crosschecklab
DB_PASSWORD=crosschecklab
DB_PORT=5432
BACKEND_PORT=8080
AI_SERVICE_PORT=8000
FRONTEND_PORT=5173

AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_MODEL=qwen2.5:7b-instruct
OLLAMA_EMBEDDING_MODEL=bge-m3:latest
```

Compose는 Docker 컨테이너에서 host Ollama에 연결합니다. 기본 `host.docker.internal` 설정은 macOS와 Docker host-gateway 환경을 지원합니다. 포트가 이미 사용 중이면 해당 포트 변수만 변경합니다.

## 로컬 실행

```bash
docker compose up --build -d
docker compose ps
```

접속 주소:

- Frontend: http://localhost:5173
- Swagger: http://localhost:8080/swagger-ui/index.html
- Backend health: http://localhost:8080/actuator/health
- AI health: http://localhost:8000/internal/v1/health

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8000/internal/v1/health
```

AI health 응답의 provider가 `ollama`인지 확인합니다. health 응답은 서비스 도달성만 확인하며, 실제 RAG 검색·모델 분석 성공을 보장하지 않습니다.

## 서비스 이용 흐름

### PM

1. 상품을 등록하고 판매 PDF를 업로드합니다.
2. PDFBox가 추출한 텍스트를 확인·확정합니다.
3. 공식 사실 후보를 확인해 필요한 사실을 `VERIFIED`로 만듭니다.
4. 분석 대상 문서, 근거 문서 1~3개, Persona 1~3개, Red Team Pack을 선택합니다.
5. 분석 결과와 RAG 검색 근거를 확인한 뒤 검토 요청을 보냅니다.

### reviewer

1. 검토함의 `PENDING` 요청을 엽니다.
2. 상품 원문, 공식 사실, Finding, RAG retrieval trace, PM 의견을 확인합니다.
3. 승인할 Finding을 선택해 승인하거나, 반려 사유를 입력해 반려합니다.
4. 승인한 경우 Risk Pattern을 활성화하고 GuardFit을 승인합니다.

### 승인과 반려

- 승인: 선택 Finding만 Risk Pattern으로 승격됩니다. GuardFit은 reviewer가 별도로 승인해야 합니다.
- 반려: comment가 필수입니다. Pattern·GuardFit은 생성되지 않으며 PM은 결과 화면에서 반려 사유를 확인합니다.
- 반려된 분석과 review는 삭제하지 않습니다. PM이 문서·사실·표현을 수정한 뒤 새 분석과 새 검토를 요청하는 근거로 보존합니다.
- 기존 Risk Pattern은 새 Pattern이 생겨도 삭제하지 않습니다. 완료 항목만 기본 작업 큐에서 제외됩니다.

## `Persona + Red Team 분석 시작`의 실제 동작

이 버튼은 화면용 점수 계산이나 Mock 결과 선택이 아닙니다.

```text
입력 검증
→ Idempotency-Key와 분석 fingerprint 생성
→ 선택 근거 문서 chunking·embedding
→ pgvector top-6 검색
→ 검색된 chunk만 Ollama에 전달
→ Finding·severity·Persona·chunk ID 응답
→ 서버 검증·immutable snapshot 저장
→ 결과 화면·검토 요청
```

backend는 확정 문서, 활성 근거, VERIFIED 사실, Persona와 Pack 선택 범위를 먼저 검증합니다. 같은 key와 같은 의미의 요청은 기존 analysis ID를 반환하고, 같은 key를 다른 요청에 재사용하면 409 conflict를 반환합니다.

### Persona

Persona는 역할극 문장이 아니라 어떤 소비자 관점에서 위험을 점검할지 정의한 활성 기준 데이터입니다.

- 금융 초보자: 확정수익·원금보장 오해
- 고령 금융소비자: 안정성 표현 오해·인지 접근성
- 손실 경험자: 손실 범위 인식·위험 민감도
- 단기 자금 필요: 중도해지 비용·유동성 제약
- 자영업자: 금리 변동·납부 조건·현금흐름

UI는 실제 API에서 활성 Persona를 읽습니다. backend는 선택된 Persona 1~3개만 분석 요청에 넣고, 모델이 선택하지 않은 Persona code를 반환하면 결과를 저장하지 않습니다.

### Red Team Pack

기본 Pack은 수익 강조 편향, 손실 완화 표현, 비용 누락, 안정·보장 키워드, 형식적 확인, 인지 접근성을 점검합니다. 선택된 Pack의 활성 rule만 retrieval query와 AI 분석 조건에 사용됩니다.

## RAG와 AI 결과 검증

1. 선택된 활성 근거 문서를 한국어 문장·문단 기준으로 chunking합니다.
2. `bge-m3:latest`로 1024차원 embedding을 만들고 pgvector에 저장합니다.
3. 상품 텍스트, Persona, Red Team rule로 query를 만들고 선택 근거 범위에서 top-6을 검색합니다.
4. 검색된 context만 AI service와 `qwen2.5:7b-instruct`에 전달합니다.
5. 모델은 전달받은 `retrievedContextChunkIds`만 Finding 근거로 선택합니다.
6. backend는 선택 ID가 실제 retrieval snapshot에 있는지 검증하고, 해당 chunk의 정확한 문서 ID·원문을 저장합니다.

따라서 모델이 검색하지 않은 문서를 인용하거나, 선택하지 않은 Persona·공식 사실을 결과에 넣으면 결과 저장이 거부됩니다. 분석 시점의 공식 사실과 retrieval 결과는 immutable snapshot으로 보관됩니다.

분석 실행마다 새 `execution_token`을 발급합니다. retry가 시작되면 이전 worker는 새 결과를 덮어쓸 수 없습니다. terminal 상태와 terminal audit event는 같은 DB transaction으로 기록됩니다.

## 검증 방법

### 서비스 도달성

health endpoint와 `frontend/scripts/real-e2e-preflight.mjs`는 서비스·의존성 연결만 확인합니다.

### Frontend build

```bash
(cd frontend && npm run build)
(cd frontend && VITE_USE_MOCK=false npm run build)
```

Vue build와 real API mode build를 확인합니다.

### Backend regression

```bash
./gradlew cleanTest test
```

API·workflow·RAG·audit 회귀를 확인합니다.

### AI contract

```bash
python -m pytest -q
```

`ai-service/`에서 실행하며 FastAPI schema·provider contract를 확인합니다.

### 실제 전체 흐름

`docker compose up --build -d`와 Ollama 모델 준비이 끝난 뒤, 저장소 루트에서 실행합니다.

```bash
PLAYWRIGHT_EXECUTABLE_PATH="/absolute/path/to/Chromium" \
node frontend/scripts/real-rag-full-flow-e2e.mjs
```

`frontend/package.json`은 `playwright-core`를 사용하므로 Chromium 실행 파일 경로를 지정합니다. 일반 `playwright` browser를 별도로 설치한 환경에서는 해당 변수 없이 실행할 수 있습니다.

이 script는 실제 PDF → RAG → Ollama → PM/reviewer → GuardFit을 확인합니다.

실제 전체 흐름 script는 API interception이나 `mockServer`를 사용하지 않습니다. page error, request failure, 예상 밖 4xx/5xx도 실패로 처리합니다.

`frontend/scripts/smoke.mjs`, `frontend/scripts/ai-test.mjs`, `frontend/scripts/real-adapter-e2e.mjs`, backend fake provider 테스트는 빠른 회귀용입니다. 실제 Ollama·pgvector E2E 성공 근거와 구분해야 합니다.

## API와 상세 문서

- API 탐색: http://localhost:8080/swagger-ui/index.html
- Demo API 요청: `X-Demo-User-Id`, `X-Demo-Role` 헤더를 사용합니다.
- 생성 API: `Idempotency-Key`를 사용합니다.
- Backend·DB·RAG 상세: `backend/README.md`
- Frontend·실서버 UI 상세: `frontend/README.md`
- corpus·hash·expected case 상세: `data/demo-corpus/README.md`

## 데모 자료와 데이터 수명

상품 입력 PDF:

- `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf`

시연용 상품 자료:

- `data/demo-showcase-pdfs/01-프라임인컴-판매자료-위험표현.pdf`
- `data/demo-showcase-pdfs/02-그린밸런스-중도해지-안내.pdf`
- `data/demo-showcase-pdfs/03-클리어세이프-균형설명서.pdf`
- `data/demo-showcase-pdfs/04-스테이블리턴-표현검토-시연.pdf`
- `data/demo-showcase-pdfs/05-라이프캐시-유동성안내-시연.pdf`

세 파일은 실제 브라우저 업로드와 PDFBox 추출에 사용할 수 있는 10MB 이하 Korean text-layer PDF입니다. 파일별 시연 초점·페이지·SHA-256은 `data/demo-showcase-pdfs/README.md`에 있습니다.

RAG 근거 PDF:

- `data/demo-corpus/documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf`
- `data/demo-corpus/documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf`
- `data/demo-corpus/documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf`

corpus hash와 logical ID는 `data/demo-corpus/manifest.v1.json`에서 확인합니다.

```bash
docker compose down      # 컨테이너만 종료, DB 유지
docker compose down -v   # DB·RAG index·분석·검토·감사 데이터를 모두 삭제
docker compose up --build -d
```

분석 요청 시 선택 근거를 자동 indexing합니다. 원문 hash, chunking version, embedding model이 같으면 기존 index를 재사용하고 달라지면 새로 indexing합니다.

## 관찰과 문제 해결

```bash
docker compose ps
docker compose logs -f backend ai-service
docker compose logs -f postgres
ollama list
```

- AI health가 실패하면 Ollama daemon과 `OLLAMA_BASE_URL`을 확인합니다.
- 분석이 실패하면 결과 화면의 error code·retryable 상태와 backend/ai-service 로그를 함께 확인합니다.
- 모델을 찾지 못하면 `ollama pull bge-m3:latest`와 `ollama pull qwen2.5:7b-instruct`를 다시 실행합니다.
- 포트 충돌이면 `.env`의 `DB_PORT`, `BACKEND_PORT`, `AI_SERVICE_PORT`, `FRONTEND_PORT`를 변경합니다.
- `down -v`는 모든 로컬 workflow·audit 데이터를 삭제하므로 필요한 경우에만 사용합니다.

## 변경 기여 경계

- 실제 고객·개인정보·실제 규제 문서를 demo corpus에 추가하지 않습니다.
- API, workflow, RAG contract, 모델, corpus를 변경하면 관련 README·manifest·expected case·테스트를 함께 갱신합니다.
- mock 검증 결과를 실제 Ollama·pgvector E2E 성공으로 표현하지 않습니다.
