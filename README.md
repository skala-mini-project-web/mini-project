# ARGUS

## 금융상품 판매 리스크 사전검증 AI 플랫폼

![ARGUS 메인 화면](docs/assets/argus-main-page.gif)

- 판매 문서의 위험 표현을 분석하고, 근거·검토·보호조치 이력을 연결하는 로컬 AI 컴플라이언스 데모
- 업무 흐름: 상품 담당자(PM) 분석 → 컴플라이언스 reviewer 검토 → Risk Pattern → GuardFit 적용 가이드
- 구성: Vue 3 SPA, Spring Boot backend, FastAPI AI service, PostgreSQL + pgvector, Ollama
- 실행 형태: 단일 업무 backend와 독립 AI service를 Docker Compose로 구동
- MSA, 원격 운영 서비스, 실제 금융·법률 자문 서비스가 아님

## 요약

### 해결하는 문제

- 판매 자료에서 수익·안정성 표현이 손실 가능성·비용·유동성 제약보다 강조되는 문제
- AI 결과의 문서 근거와 담당자·검토자 승인 이력을 추적하기 어려운 문제
- 승인된 개선 조치가 판매 화면의 적용 가이드까지 이어지지 않는 문제

### 핵심 흐름

```text
PM
상품 등록 → PDF 업로드·텍스트 확정 → 공식 사실 확인
→ Persona·Red Team 분석 → Finding·RAG trace 확인 → 검토 요청

reviewer
원문·공식 사실·Finding·RAG trace 확인 → 승인 또는 반려

승인
선택 Finding → Risk Pattern → GuardFit 승인 → PM 적용 가이드

반려
comment 기록 → Pattern·GuardFit 미생성 → PM 수정 필요 상태
```

- reviewer가 승인한 Finding만 Risk Pattern으로 승격
- reviewer가 승인한 GuardFit만 PM 적용 가이드에 노출
- 반려 시 comment가 필수이며 Risk Pattern과 GuardFit은 생성하지 않음
- 분석이 생성된 원문은 수정하거나 확정을 해제할 수 없음

## 범위와 데이터 고지

- `data/demo-corpus/`, `data/demo-showcase-pdfs/`의 상품명·PDF·정책·규정·수치는 100% 합성 데이터
- 실제 금융상품·회사·고객·규제기관·법령을 의미하지 않음
- 실제 투자 판단, 법률 판단, 금융 판매 자료로 사용 금지
- 개인정보, 상용 인증, 원격 배포, 운영 모델 라이선스 관리는 구현 범위 밖
- 이미지형 스캔 PDF OCR은 실제 서버에서 지원하지 않음
- 실제 서버의 PDF 추출은 PDFBox text layer 기준

## 시스템 아키텍처

![ARGUS 시스템 아키텍처](docs/assets/system-architecture.png)

- Frontend: Vue 3, Vite, Vue Router, Pinia 기반 PM·reviewer 화면과 route guard
- Backend: Java 21, Spring Boot, PDFBox 기반 업무 workflow·RAG·감사 처리
- Database: PostgreSQL 16, pgvector, Flyway, HNSW cosine index
- AI service: FastAPI, Pydantic, backend 전용 bearer token 검증
- Local AI: Ollama `bge-m3:latest` embedding, `qwen2.5:7b-instruct` 분석
- Runtime: Docker Compose, Nginx

```text
Frontend
  → Backend
      ├─ PostgreSQL + pgvector
      ├─ PDFBox
      └─ AI service → Ollama
```

### 로컬 모델 선정 기록

#### 결정 상태

- 현재 기준선은 generator `qwen2.5:7b-instruct`, embedder `bge-m3:latest`입니다.
- 이는 합성 데이터 기반 데모의 **구성 기준선**이며, 보안·한국어 품질·금융 판단·B2B 적합성을 인증하거나 보장하지 않습니다.
- `latest`와 Ollama tag는 immutable artifact 식별자가 아닙니다. B2B 배포 전에는 upstream revision, Ollama manifest digest, quantization, Ollama version, prompt·container digest, license notice를 고정·기록해야 합니다.

#### 실제 데이터·보안 경계

- 현재 경로는 `backend → AI service → host Ollama`입니다. Ollama에는 확정 문서 텍스트, 선택 Persona/rule/ID, 검색된 context chunk, VERIFIED fact, 서버가 만든 evidence option이 전달됩니다.
- 전체 근거 문서를 그대로 전달하지 않지만, “검색 chunk만 전달한다”는 표현도 정확하지 않습니다.
- 모델은 evidence option ID를 선택하고 AI service가 해당 option을 exact excerpt로 매핑·검증합니다.
- local execution 또는 모델 제조사 이름만으로 보안을 판단하지 않습니다. 현재 Compose는 loopback 공개, container 간 HTTP, demo bearer token을 사용합니다. TLS, secret manager, egress control, 운영 인증·감사는 별도 B2B 보안 과제입니다.

#### 왜 Qwen2.5 7B + BGE-M3인가

| 구성 | 현재 선택 근거 | 아직 증명되지 않은 사항 |
| --- | --- | --- |
| [Qwen2.5 7B Instruct](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct) | 공식 model card상 7.61B parameter, Apache-2.0, 한국어를 포함한 다국어와 structured/JSON 개선을 표방합니다. 현재 system prompt·JSON schema·temperature 0·seed 42 계약에 가장 적은 변경으로 연결됩니다. | ARGUS 한국어 Finding 정확도, citation fidelity, p95 latency, 실제 메모리, 양자화 artifact 품질 |
| [BGE-M3](https://huggingface.co/BAAI/bge-m3) | 공식 model card상 MIT, 1024차원, 100개 이상 언어, dense/sparse/multi-vector를 지원합니다. 현재 pgvector `vector(1024)`·dense cosine retrieval 계약과 맞습니다. | ARGUS 한국어/혼합 언어 retrieval quality, dense-only가 hybrid/rerank보다 나은지, 실제 latency |

- parameter 수나 다운로드 크기만으로 RAM·latency를 판단하지 않습니다. quantization, context KV cache, concurrency, output token, runtime이 함께 결정합니다.
- Qwen 7B는 현재 structured JSON contract에 맞는 **낮은 통합 위험의 기준선**입니다. “가장 좋은 모델”이라는 결론은 아직 내리지 않았습니다.

#### DeepSeek·상위 모델 대안

| 후보 | 평가 관점 | 현재 결론 |
| --- | --- | --- |
| 설치된 `deepseek-r1:8b` | [DeepSeek-R1 공식 문서](https://github.com/deepseek-ai/DeepSeek-R1)는 distill 모델의 prompt·temperature·thinking output 권고가 현재 Qwen JSON 계약과 다름을 설명합니다. reasoning token이 latency를 늘릴 수 있고, base model license·정확한 artifact provenance도 확인해야 합니다. | 제조사만으로 insecure라고 판단하지 않습니다. 동일 local boundary라면 외부 전송 여부는 배포 경계가 결정합니다. 별도 prompt profile과 동등 contract 평가를 통과한 challenger만 채택합니다. |
| Qwen2.5 14B | 동일 family의 scale-up 후보로 JSON contract 변경 위험이 작습니다. | 7B 대비 메모리·latency budget 안에서 Korean/grounding 품질 이득이 재현될 때만 채택합니다. |

#### 채택 전 실측 평가

- 고정 held-out manifest: 한국어 40, 한·영 혼합 40, 영어 40의 synthetic case와 gold rule·chunk·exact excerpt·non-finding을 분리 보관합니다.
- 비교 arm: 현재 Qwen 7B + BGE-M3 dense, DeepSeek-R1 8B의 권장 decoding profile, Qwen 14B, 필요 시 BGE-M3 hybrid/rerank입니다.
- 필수 측정: JSON schema first-pass/repair/acceptance, option ID·citation entailment, rule별 precision/recall, Retrieval Recall@6/MRR, p50/p95 latency, cold start, token throughput, peak memory, timeout·error rate, artifact/license manifest 일치율입니다.
- 동일 contract·governance gate를 모두 통과하고 언어·rule별 citation fidelity를 낮추지 않으며 승인된 latency·memory budget 안에서 통계적으로 유의한 개선이 있을 때만 기준선을 바꿉니다.

#### 공식 근거

- [Qwen2.5 7B Instruct model card](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct)
- [BGE-M3 model card](https://huggingface.co/BAAI/bge-m3)
- [DeepSeek-R1 repository·license·local-use guidance](https://github.com/deepseek-ai/DeepSeek-R1)
- [Ollama Qwen tags](https://ollama.com/library/qwen2.5)
- [Ollama DeepSeek-R1 tags](https://ollama.com/library/deepseek-r1)

### 설계 이미지

![핵심 ERD](docs/assets/erd-core.png)

![전체 ERD](docs/assets/erd-v3.png)

![프로젝트 폴더 구조](docs/assets/project-folder-structure.png)

## RAG 근거 추적 계약

```text
확정 판매 문서 + VERIFIED 공식 사실 snapshot
+ Persona 1~4개 + 활성 근거 문서 1~3개 + Red Team rule
→ 근거 문서 chunking
→ pgvector cosine top-6 검색
→ 검색 chunk만 Ollama에 전달
→ Finding + chunk ID + exact evidence span 반환
→ backend 검증
→ retrieval snapshot·Finding·audit 저장
```

![AI 로직 흐름](docs/assets/ai-logic-flow.png)

- 전체 근거 문서를 prompt에 넣지 않으며 lexical·full-document fallback을 사용하지 않음
- 모델은 `retrievedContextChunkIds`와 `evidenceSpans`를 함께 반환
- `evidenceSpans.excerpt`는 검색된 chunk 원문에 정확히 포함돼야 함
- 선택하지 않은 Persona·근거 문서·공식 사실 ID가 포함되면 저장을 거부
- embedding tag는 Ollama digest를 포함한 immutable model identity로 저장·검색
- 완료된 retrieval snapshot은 변경 불가
- 분석 terminal state와 terminal audit event는 같은 transaction으로 저장
- AI 내부 분석 endpoint는 shared bearer token이 없으면 401 응답

![AI 프롬프트 설계](docs/assets/ai-prompt-design.png)

![AI 입출력 JSON 스키마](docs/assets/ai-input-output-json-schema.png)

![AI 확장 지점](docs/assets/ai-extension-points.png)

## 주요 기능

- PDF 업로드, PDFBox text extraction, 사용자 텍스트 확정
- VERIFIED 공식 사실 관리
- Persona·Red Team Pack 기반 구조화 분석
- exact evidence span·chunk ID 기반 RAG provenance
- PM → reviewer 승인·반려 workflow
- 승인 Finding → Risk Pattern → 승인 GuardFit workflow
- Idempotency-Key, execution token, terminal audit event
- stale `RUNNING` 분석의 `FAILED` 전환과 retry 경로
- 실제 API 모드와 Mock UX 모드 분리

## 로컬 실행

### 사전 조건

- Docker Desktop
- Ollama
- 로컬 모델을 저장·실행할 수 있는 디스크와 메모리

### 1. 모델 준비

```bash
ollama serve
ollama pull bge-m3:latest
ollama pull qwen2.5:7b-instruct
ollama list
```

### 2. 환경 설정과 서비스 실행

프로젝트 루트에서 실행:

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
```

- Compose container는 기본적으로 `http://host.docker.internal:11434`의 host Ollama에 연결
- `.env.example`의 `AI_SERVICE_INTERNAL_TOKEN`은 로컬 합성 기본값이며 운영 secret이 아님

### 3. 접속과 상태 확인

- Frontend: http://localhost:5173
- Swagger: http://localhost:8080/swagger-ui/index.html
- Backend health: http://localhost:8080/actuator/health
- AI health: http://localhost:8000/internal/v1/health

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8000/internal/v1/health
```

- AI health에서 `provider: ollama` 확인
- health 응답은 의존성 도달성만 확인하며 실제 RAG 분석 성공은 아래 E2E로 검증

## 검증 명령

### Backend

```bash
cd backend
./gradlew cleanTest test
```

- API·workflow·RAG·audit·migration 회귀 검증

### AI service

```bash
cd ai-service
python -m pytest -q
```

- schema·bearer token·prompt·provider contract 검증

### Frontend

```bash
cd frontend
npm run build
VITE_USE_MOCK=false npm run build
```

- Mock mode와 실제 API mode build 검증

### 실제 RAG E2E

프로젝트 루트에서 실행:

```bash
PLAYWRIGHT_EXECUTABLE_PATH="/absolute/path/to/Chromium" \
node frontend/scripts/real-rag-full-flow-e2e.mjs
```

- 실제 PDF upload → PDFBox extraction → official fact verification → pgvector retrieval → Ollama analysis 검증
- PM 검토 요청 → reviewer 승인 → Risk Pattern → GuardFit 전체 흐름 검증
- PM 확정 텍스트 수정·원문 불변성, 분석 후 수정 `409` 차단, reviewer read-only, malformed PDF 실패 화면·오류 metadata 검증
- API interception과 `mockServer`를 사용하지 않음
- page error, request failure, 예상 밖 4xx/5xx 응답은 실패 처리
- 상세 coverage와 미지원 범위: [`docs/reports/real-browser-e2e-coverage.md`](docs/reports/real-browser-e2e-coverage.md)

## 제출 산출물

- 전체 목록: [`docs/deliverables/`](docs/deliverables/)
- 최종 발표·Use-Case: 발표 슬라이드와 PM·reviewer 업무 흐름
- 설계: 실제 화면 기반 Wireframe, ERD·DB schema, API·Postman Mock, AI prompt·JSON 규격
- 구현: FE·BE scaffolding, 메인·핵심 UI 구현 증빙
- 검증·형상관리: 실제 RAG E2E·회귀 테스트 결과와 GitHub repository 설정

## 시연 데이터

- 판매 PDF: `data/demo-showcase-pdfs/`
- canonical 상품 입력: `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf`
- 근거 PDF: `data/demo-corpus/documents/evidence/`
- corpus hash·logical ID: `data/demo-corpus/manifest.v1.json`
- expected RAG case: `data/demo-corpus/expected/`
- 다페이지 합성 금융 문서 corpus: [`data/synthetic-financial-corpus/`](data/synthetic-financial-corpus/)
  - 6개 합성 상품군 × 5개 문서 유형, 30개 PDF·102페이지
  - PDFBox 실제 추출·SHA-256·source revision 로컬 검증 완료
  - 향후 batch·PDF OCR의 실제 workload regression으로 사용

## 운영 명령

```bash
docker compose ps
docker compose logs -f backend ai-service
docker compose logs -f postgres
ollama list
```

- AI 연결 실패: Ollama daemon, `OLLAMA_BASE_URL`, 모델 설치 상태 확인
- 포트 충돌: `.env`의 port 변수 변경
- 데이터 유지 종료: `docker compose down`
- DB·RAG index·분석·검토·감사 데이터 삭제: `docker compose down -v`

## 고도화 진행 현황

- 구현 계획: [`docs/plans/ARGUS-위험우선순위-배치-OCR-구현계획.md`](docs/plans/ARGUS-위험우선순위-배치-OCR-구현계획.md)
- 상태 표기: `[완료]` 검증·`develop` 반영 완료 / `[진행 중]` feature branch에서 구현·검증 중 / `[예정]` 설계는 확정됐지만 미구현
- 근거 기반 문서 위험 우선순위·상황 기반 Persona
  - `[완료]` 정책 v1·synthetic TEVV fixture, 불변 source revision, execution-bound Finding, exact evidence anchor, Finding별 reviewer decision 이력
  - `[완료]` 실제 Docker/browser RAG E2E에서 immutable anchor·review decision 저장 확인
  - `[예정]` 결정론 score engine·score ledger·score UI·TEVV calibration: [#85](https://github.com/skala-mini-project-web/mini-project/issues/85)
- 대량 파일 자동 처리
  - `[완료]` PostgreSQL durable queue·storage·retry/cancel/quarantine 설계 확정
  - `[예정]` one-item 호환 pipeline, item별 retry·cancel·quarantine·error report, browser E2E: [#86](https://github.com/skala-mini-project-web/mini-project/issues/86)
- PDF 한글 OCR
  - `[완료]` PDF-only OCR worker·page routing·confidence·원본 대조·PM confirmation 설계 확정
  - `[예정]` pinned `kor+eng` OCR worker, page artifact, critical-field review, actual Compose/browser E2E: [#87](https://github.com/skala-mini-project-web/mini-project/issues/87)
- 실서비스 검증·합성 corpus
  - `[완료]` 실제 browser E2E: PM 수정·확정, 분석 후 수정 409, reviewer read-only, malformed PDF 실패 화면, RAG→review→Risk Pattern→GuardFit
  - `[완료]` 6개 합성 상품군·30개 PDF·102페이지 corpus의 PDFBox extraction·SHA-256·source revision 검증

## 팀 구성

| 이름 | 역할 |
| --- | --- |
| 김지원 | PM, API 설계 |
| 최도한 | Backend, Data Architecture |
| 손서현 | Backend, DevOps |
| 신주용 | Frontend, UI/UX |
| 정다운 | Frontend, UI/UX |

## 개발 타임라인

![프로젝트 타임라인](docs/assets/project-timeline.png)

## 협업 및 형상관리

- 작업 단위: Issue → feature·docs·fix branch → PR → review → `develop` merge
- 릴리스 단위: 검증된 `develop` → release PR → `main` merge
- `main`, `develop` 직접 push 금지
- PR review·자동 리뷰 의견·수정 반영 후 작성자가 merge

![협업 규칙](docs/assets/collaboration-rules.png)

![PR 브랜치 그래프](docs/assets/collaboration-pr-branch-graph.png)

## 상세 문서

- Backend·DB·RAG: `backend/README.md`
- Frontend·실서버 UI: `frontend/README.md`
- corpus·hash·제한: `data/demo-corpus/README.md`
