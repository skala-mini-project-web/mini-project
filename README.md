# ARGUS 로컬 Genuine RAG 데모

금융상품 판매 문구를 근거 문서와 대조하는 로컬 데모입니다. 예전의 **사용자가 선택한 근거를 프롬프트에 넣는 방식은 검색 단계가 없으므로 RAG가 아니었습니다.** 이 브랜치는 Spring 백엔드가 근거 문서를 결정론적으로 청크화하고, Ollama `bge-m3:latest` 임베딩과 PostgreSQL/pgvector 코사인 검색으로 상위 청크를 고른 뒤, 그 청크만 AI 서비스의 Ollama `qwen2.5:7b-instruct` 채팅 프롬프트에 전달합니다.

> `data/demo-corpus/`의 상품·회사·기관·수치·규정·PDF·메타데이터는 모두 100% 합성한 데모 전용 자료입니다. 실제 법률·규제·금융 사실이나 법률/재무 판단의 권위 있는 근거가 아닙니다.

## 정본 데모 자료

상품 입력 PDF:

- `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf`

검색 대상 근거 PDF:

- `data/demo-corpus/documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf`
- `data/demo-corpus/documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf`
- `data/demo-corpus/documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf`

감사 보조 파일:

- 아티팩트 해시·버전·출처: `data/demo-corpus/manifest.v1.json`
- 문서 메타데이터: `data/demo-corpus/metadata/documents.v1.json`
- 예상 청크/페이지 앵커: `data/demo-corpus/metadata/chunks.v1.jsonl`
- 분석 기대 사례: `data/demo-corpus/expected/analysis-cases.v1.json`
- 검색 기대 사례: `data/demo-corpus/expected/rag-cases.v1.json`

## 로컬 실행

필수 조건은 Docker Desktop(Compose 포함), 로컬 Ollama, 두 모델을 받을 디스크/메모리입니다. 저장소 루트에서 실행할 명령은 다음과 같습니다.

```bash
ollama serve
ollama pull bge-m3:latest
ollama pull qwen2.5:7b-instruct
ollama list
```

`ollama serve`는 이미 데스크톱 앱이 서버를 제공 중이면 중복 실행하지 않습니다. 모델 준비 후 별도 터미널에서:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8000/internal/v1/health
curl -fsS http://localhost:8080/actuator/health
node frontend/scripts/real-e2e-preflight.mjs
```

Preflight는 Spring과 AI 서비스의 도달 가능성만 검사합니다. 모델 호출, pgvector 검색, 업무 E2E 성공을 보증하지 않습니다. 기본 주소는 Frontend `http://localhost:5173`, Swagger `http://localhost:8080/swagger-ui/index.html`, AI health `http://localhost:8000/internal/v1/health`입니다. Compose 기본값은 실제 API 모드, `AI_PROVIDER=ollama`, `bge-m3:latest`, `qwen2.5:7b-instruct`이며 Ollama 오류를 fixture로 숨기지 않습니다.

## `Persona + Red Team 분석 시작`을 누르면 일어나는 일

이 버튼은 화면용 점수 계산이나 Mock 결과 선택이 아닙니다. PM이 확정 상품 문서, 활성 근거 문서 1~3개, Persona 1~3개, Red Team Pack을 선택하면 backend가 `POST /api/analyses`를 받고 비동기 분석을 시작합니다.

```text
선택·검증
  → 분석 입력 fingerprint + Idempotency-Key
  → 선택 근거 chunk indexing
  → 상품 텍스트·Persona·rule 기반 pgvector top-6 retrieval
  → 검색 chunk만 Ollama에 전달
  → 구조화된 Finding + retrievedContextChunkIds
  → backend scope 검증·immutable snapshot 저장
  → 결과 화면·검토 요청
```

1. **입력 검증**: 확정되지 않은 문서, 비활성 근거, 1~3개 범위를 벗어난 Persona/근거, VERIFIED 사실이 없는 요청은 분석 전에 거부합니다.
2. **중복 방지**: `Idempotency-Key`와 의미적으로 정규화한 입력 fingerprint를 함께 사용합니다. 같은 key와 같은 요청은 기존 analysis ID를 재생하고, 같은 key를 다른 요청에 재사용하면 409입니다. 근거 문서의 source hash도 fingerprint에 포함하므로 근거 본문이 바뀌면 예전 입력과 동일하게 취급하지 않습니다.
3. **검색**: 선택된 활성 근거만 결정론적으로 chunking·embedding하고, 확정 상품 텍스트 + Persona code + Red Team rule code로 query를 만들어 pgvector cosine top-6을 검색합니다. 전체 근거 문서를 LLM 프롬프트에 넣지 않으며, 검색 결과가 없거나 embedding이 실패하면 분석은 실패 상태와 retryable 정보를 남깁니다.
4. **AI 분석**: FastAPI AI service는 검색된 `chunkId`, 문서 ID, rank, similarity, 원문만 Ollama에 전달합니다. 모델은 위험 statement·severity·대상 Persona·recommendation과 함께 **전달받은 `retrievedContextChunkIds`만** 선택해야 합니다.
5. **서버 검증과 결과**: backend는 모델이 선택한 chunk ID가 실제 outbound retrieval snapshot 안에 있는지, Persona/공식 사실 ID가 요청 범위 안에 있는지 검증합니다. 통과한 ID는 서버가 immutable chunk snapshot의 정확한 문서 ID·원문으로 해석해 Finding과 `retrievalTrace`에 저장합니다. 모델이 근거 문장을 새로 만들거나, 검색하지 않은 문서를 인용할 수 없습니다.
6. **사람 결정**: PM은 결과와 RAG 검색 근거를 보고 검토 요청을 제출합니다. reviewer는 같은 retrieval trace를 확인하고 Finding을 승인 또는 반려합니다. 승인 Finding만 Risk Pattern이 되고, GuardFit은 reviewer가 별도로 승인해야 PM에게 보입니다.

분석 실행마다 새 `execution_token`을 발급합니다. retry가 시작된 뒤 이전 worker가 늦게 끝나도 새 결과를 덮어쓸 수 없습니다. `COMPLETED`/`FAILED` 상태와 해당 terminal audit event는 같은 DB transaction으로 기록합니다.

## Persona는 무엇이고 어떻게 구현했나

Persona는 LLM에게 임의의 역할극 문장을 붙이는 기능이 아니라, **어떤 소비자 관점에서 오해·손실·접근성 위험을 점검할지 제한하는 활성 기준 데이터**입니다.

| Persona | 저장된 criteria | 위험 초점 |
| --- | --- | --- |
| 금융 초보자 | 금융 이해도 낮음, 투자 경험 없음 | 확정수익·원금보장 오해 |
| 고령 금융소비자 | 디지털 이해도 낮음, 안정성 선호 높음 | 안정성 표현 오해·인지 접근성 |
| 손실 경험자 | 위험 민감도·손실 회복 욕구 높음 | 손실 범위 인식·위험 민감도 |
| 단기 자금 필요 | 유동성 필요 높음, 투자 기간 짧음 | 중도해지 비용·유동성 제약 |
| 자영업자 | 소득 안정성 낮음, 현금흐름 민감 | 금리 변동·납부 조건·현금흐름 |

- 기준 데이터는 `persona_templates`에 `code`, `criteria`, `risk_focus`, `active`로 저장됩니다.
- UI는 `GET /api/persona-templates`의 실제 활성 템플릿을 읽어 보여주며, 분석 요청에는 template ID를 보냅니다.
- backend는 활성 template만 허용하고 code로 변환해 AI request에 넣습니다.
- 모델이 Finding의 `affectedPersonaCodes`에 선택하지 않은 Persona를 넣으면 provider contract 위반으로 결과를 저장하지 않습니다.
- Persona 선택 1~3개와 Red Team rule은 retrieval query와 LLM 분석 조건에 모두 포함됩니다. 즉 결과의 Persona 표시는 화면 장식이 아니라 서버가 검증한 분석 입력·출력 범위입니다.

## Red Team Pack은 무엇을 점검하나

기본 `CORE_FINANCIAL_RISK_V1` Pack은 다음 6개 rule을 활성 기준으로 제공합니다.

- `RETURN_FRAMING`: 수익을 위험보다 먼저·크게 강조하는지
- `LOSS_SOFTENING`: 손실 가능성을 추상적으로 완화하는지
- `COST_OMISSION`: 수수료·해지 비용이 누락·분산되는지
- `STABILITY_KEYWORD`: 안정·보장 유사 표현으로 오인을 유발하는지
- `FORMAL_CONFIRMATION`: 체크박스만으로 이해를 간주하는지
- `COGNITIVE_ACCESSIBILITY`: 긴 문장·작은 글씨·전문용어가 집중되는지

Pack과 rule도 DB의 활성 기준 데이터입니다. 분석 요청에 선택된 Pack의 활성 rule만 AI service로 전달되며, 모델이 반환한 결과는 선택 Persona·rule·검색 chunk·공식 사실 범위를 동시에 벗어날 수 없습니다.

## 실제 서비스인지, Mock인지 확인하는 방법

Compose 기본 실행은 `VITE_USE_MOCK=false`, `AI_PROVIDER=ollama`입니다. `http://localhost:8000/internal/v1/health`가 `{"status":"UP","provider":"ollama"}`를 반환하는지 먼저 확인합니다. fixture provider는 명시적으로 test/mock mode를 선택한 경우에만 사용하며, Ollama·embedding 오류를 fixture 결과로 대체하지 않습니다.

실제 동작의 재현 가능한 증거는 `frontend/scripts/real-rag-full-flow-e2e.mjs`입니다. 이 스크립트는 API interception이나 `mockServer` 없이 canonical PDF를 실제 file chooser로 업로드하고, PDFBox 추출 → 사실 검증 → pgvector RAG → Ollama 분석 → 독립 PM/reviewer browser context → Risk Pattern → GuardFit → PM 확인까지 실행합니다. page error, request failure, 예상 밖 4xx/5xx도 실패로 처리합니다. 검토가 아직 없을 때의 `GET /api/analyses/{id}/review` 404 `REVIEW_NOT_FOUND`만 정상적인 초기 상태로 기록합니다.

## PM → 검토자 확인 순서

1. Frontend에서 상품 담당자(`PRODUCT_MANAGER`)로 들어가 상품을 등록합니다.
2. 위 상품 입력 PDF를 업로드하고 추출 완료 후 텍스트를 확인·확정합니다.
3. 공식 사실 후보를 확인하고, 위 근거 문서 3개를 선택해 분석을 한 번 요청합니다.
4. 상태가 `COMPLETED`가 될 때까지 확인한 뒤 결과의 **RAG 검색 근거**에서 검색 버전, 임베딩 모델, 검색어 해시, 검색 시각과 rank별 청크를 검사합니다.
5. 각 Finding의 `evidenceDocumentId`와 `excerpt`가 모델이 선택한 검색 청크의 문서 ID와 불변 원문 전체로 서버에서 해석되었는지 대조하고 검토 요청을 제출합니다.
6. 역할을 컴플라이언스 검토자(`COMPLIANCE_REVIEWER`)로 바꾸어 검토함의 요청을 열고, 판매 원문 → 검색 청크 → Finding 인용을 다시 대조한 뒤 승인 또는 반려합니다.
7. 승인 시에도 Risk Pattern 승격과 GuardFit DRAFT/APPROVED는 별도의 사람 결정임을 확인합니다.

API 감사 시 `GET /api/analyses/{analysisId}/result` 응답의 `retrievalTrace`를 봅니다. `queryHash`, `retrievalVersion`, `embeddingModel`, `retrievedAt`, `contexts[]`의 `chunkId`, 문서 ID, rank, similarity, `excerpt`가 분석 시점 스냅샷입니다. 모델은 이 검색 스냅샷에 포함된 불변 `chunkId`만 Finding 근거로 선택하고, 백엔드는 선택 ID가 AI 서비스로 보낸 바로 그 스냅샷에 속하는지 검증한 뒤 해당 청크의 정확한 문서 ID와 원문을 영속합니다. 모델이 원문 인용을 다시 생성하거나 byte-for-byte 복사하는 계약은 아닙니다. UI와 API는 종전과 같이 서버가 해석한 `evidenceDocumentId`와 `excerpt`를 노출하므로 소스 코드를 읽지 않고 검색 경로를 감사할 수 있습니다.

## 초기화와 재색인

```bash
docker compose down                  # 컨테이너만 종료, postgres-data 유지
docker compose down -v               # DB·청크·검색 스냅샷까지 완전 삭제
docker compose up --build -d          # Flyway 스키마/합성 seed 재생성
```

별도 reindex 명령은 없습니다. 분석 시작 시 선택 근거를 색인합니다. 같은 원문 해시·청크 버전·임베딩 모델의 완전한 청크 집합은 재사용하고, 원문 또는 모델이 달라지면 새 버전으로 색인합니다. 깨끗한 재색인이 필요하면 `down -v` 후 재기동하고 새 분석을 요청합니다. `down -v`는 상품, 분석, 검토, 감사 기록도 모두 지우는 파괴적 동작입니다.

## 실제 확인과 Mock 확인 구분

- **실제 RAG 확인:** 위 Compose 스택에서 실제 PDF 업로드 → 분석 완료 → `retrievalTrace`/Finding 인용 감사. Ollama 두 모델과 pgvector를 모두 사용합니다.
- **도달성만 확인:** `frontend/scripts/real-e2e-preflight.mjs`. health endpoint만 확인합니다.
- **Mock/격리 회귀:** `frontend/scripts/smoke.mjs`, `frontend/scripts/ai-test.mjs`, `frontend/scripts/real-adapter-e2e.mjs`, 백엔드 테스트의 fake provider/embedding 대역, AI 서비스 pytest의 fixture 또는 HTTP monkeypatch. 실제 Ollama+pgvector E2E 통과 증거가 아닙니다.
- **별도 실험:** `frontend/scripts/analyze-live.mjs`와 브라우저 직접 AI 옵션은 프론트 로컬 실험이며 서버의 genuine RAG 경로를 검증하지 않습니다.

여기 적힌 명령은 실행 절차이며, 이 문서는 실행 성공을 주장하지 않습니다.

## 알려진 제한

- 로컬 모델 출력은 비결정적이며 하드웨어에 따라 최초 모델 로드와 분석 시간이 길 수 있습니다.
- 검색은 사용자가 선택한 활성 근거 문서 안에서만 top 6을 고르며, 작은 합성 코퍼스의 기대 사례가 실제 문서 품질을 대표하지 않습니다.
- PDF 바이너리는 업로드 뒤 서버에 보존되지 않고 추출 텍스트/체크섬 중심으로 처리됩니다. 스캔 품질과 OCR은 별도 검증 대상입니다.
- similarity는 관련도 신호이지 법적 정확성, 사실성, 승인 여부가 아닙니다. 결과와 GuardFit은 검토자 승인 전 후보입니다.
- `host.docker.internal` 연결은 로컬 Docker/Ollama 설정의 영향을 받습니다. 원격·운영 배포, 인증, 개인정보, 모델 라이선스/자원 계획은 이 데모 범위 밖입니다.
