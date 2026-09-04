# ARGUS Frontend

Vue 3/Vite 기반의 상품 담당자(PM)·컴플라이언스 검토자 워크스페이스입니다. Compose 빌드는 `VITE_USE_MOCK=false`로 실제 Spring API를 사용합니다.

> `data/demo-corpus/`의 PDF, 상품, 기관, 수치, 정책 및 규정은 모두 100% 합성·데모 전용입니다. 실제 법률·금융 권위가 아니며 법률/재무 판단에 사용하면 안 됩니다.

## genuine RAG 경계

과거의 선택 근거 프롬프트 주입은 검색이 없었으므로 RAG가 아닙니다. 실제 업무 경로에서는 프론트가 선택한 근거 ID와 확정 상품 문서를 Spring에 보내고, 백엔드가 근거를 청크화하여 Ollama `bge-m3:latest` 임베딩 + PostgreSQL/pgvector로 검색합니다. 검색 청크만 AI 서비스의 `qwen2.5:7b-instruct`에 전달됩니다. 모델은 전달받은 불변 검색 스냅샷의 `chunkId`를 Finding 근거로 선택하고, 백엔드는 ID의 스냅샷 소속을 검증한 뒤 정확한 문서 ID와 청크 원문을 해석해 영속합니다. 프론트는 서버가 반환한 검색 스냅샷과 서버 해석 근거를 표시하며 자체 검색 결과를 섞지 않습니다.

## 실행

필수 조건은 Docker Desktop과 로컬 Ollama입니다. 저장소 루트에서 사용할 절차는 다음과 같습니다.

```bash
ollama serve
ollama pull bge-m3:latest
ollama pull qwen2.5:7b-instruct
docker compose up --build -d
docker compose ps
node frontend/scripts/real-e2e-preflight.mjs
```

- Frontend: `http://localhost:5173`
- Backend health/Swagger: `http://localhost:8080/actuator/health`, `http://localhost:8080/swagger-ui/index.html`
- AI health: `http://localhost:8000/internal/v1/health`

Preflight는 Spring/AI health 도달성만 검사하고 모델·검색·업무 흐름을 실행하지 않습니다. 위 명령은 실행 안내이며 이 문서는 성공을 주장하지 않습니다.

프론트만 개발할 때는 `frontend/`에서 `npm install`, `npm run dev`를 사용합니다. `.env`의 `VITE_USE_MOCK=false`와 `VITE_API_BASE=http://localhost:8080`을 사용해야 실제 백엔드 경로입니다. `VITE_USE_MOCK=true`는 인메모리 데모일 뿐 genuine RAG가 아닙니다.

## 정본 파일

PM 업로드 입력:

- `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf`

선택할 검색 근거:

- `data/demo-corpus/documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf`
- `data/demo-corpus/documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf`
- `data/demo-corpus/documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf`

해시·출처는 `data/demo-corpus/manifest.v1.json`, 문서/청크 기준은 `metadata/documents.v1.json`과 `metadata/chunks.v1.jsonl`, 기대 비교는 `expected/analysis-cases.v1.json`과 `expected/rag-cases.v1.json`에 있습니다.

## PM → 검토자 수동 검증

### 1. 상품 담당자

1. 역할 선택에서 상품 담당자(`PRODUCT_MANAGER`, 데모 사용자 `USER-PM-001`)로 시작합니다.
2. 상품을 등록하고 위 `SMART-INCOME-SALES-v1.pdf`를 업로드합니다.
3. 추출 상태가 준비될 때까지 기다린 뒤 판매 원문을 확인·확정합니다.
4. 공식 사실 후보를 확인하고 세 근거 문서를 선택합니다.
5. 분석 요청을 한 번 누르고 상태가 `COMPLETED`가 될 때까지 확인합니다.
6. 결과의 **RAG 검색 근거**에서 검색 버전, `bge-m3:latest`, 검색 시각, 검색어 해시와 rank별 청크를 확인합니다.
7. 각 Finding의 문서/인용을 펼쳐 서버가 해석한 `evidenceDocumentId`와 `excerpt`가 모델이 선택한 검색 청크와 일치하는지 대조합니다.
8. 결과를 컴플라이언스 검토자에게 제출합니다.

### 2. 컴플라이언스 검토자

1. 역할을 컴플라이언스 검토자(`COMPLIANCE_REVIEWER`, 데모 사용자 `USER-CR-001`)로 바꿉니다.
2. 검토함에서 방금 제출한 분석을 열고 판매 원문 → RAG 검색 청크 → Finding 인용의 연결을 재검사합니다.
3. 공식 사실, 선택 근거, rank/similarity와 권고문을 확인한 뒤 승인 또는 반려합니다.
4. 승인 Finding만 Risk Pattern으로 승격되는지 확인합니다.
5. GuardFit 후보를 DRAFT로 만든 뒤 별도 승인합니다. Review 승인과 GuardFit 승인은 같은 결정이 아닙니다.
6. PM으로 돌아와 APPROVED GuardFit만 읽기 전용으로 보이는지 확인합니다.

## 검색 추적 감사

분석 결과 화면의 **RAG 검색 근거**는 서버의 `GET /api/analyses/{analysisId}/result` 응답 `retrievalTrace`를 그대로 표시합니다.

- 실행 메타: `queryHash`, `retrievalVersion`, `embeddingModel`, `retrievedAt`
- 각 검색 결과: `chunkId`, `evidenceDocumentId`, `sourceType`, `title`, `rank`, `similarity`, `excerpt`
- Finding 근거: `findings[].evidenceReferences[]`

정상 감사 조건은 선택하지 않은 문서가 없고, rank가 연속이며, Finding의 서버 해석 문서 ID와 `excerpt`가 선택된 `retrievalTrace` 청크와 일치하는 것입니다. 모델 출력은 검색 스냅샷의 불변 `chunkId`만 선택하며 raw excerpt를 byte-for-byte 재생성하지 않습니다. 백엔드는 해당 ID가 AI 서비스로 보낸 스냅샷에 포함됐는지 검증하고 정확한 청크 원문을 공개 `evidenceReferences[]`로 제공합니다. similarity는 관련도 신호이지 준법 판정이 아닙니다.

## 실제/Mock 검증 분류

| 확인 | 분류 | 보장하지 않는 것 |
|---|---|---|
| 실제 Compose에서 정본 PDF 업로드 → 분석 완료 → `retrievalTrace`/인용 확인 | **genuine RAG E2E** | 법률적 정확성, 모델 결정성 |
| `node frontend/scripts/real-e2e-preflight.mjs` | 실제 서비스 health 도달성 | 임베딩·검색·채팅·UI E2E |
| `node frontend/scripts/smoke.mjs` | 프론트 Mock 계약/Golden 흐름 | Spring, pgvector, Ollama |
| `node frontend/scripts/ai-test.mjs` | 브라우저 RAG/가드레일 단위 확인 | 서버 genuine RAG |
| `node frontend/scripts/real-adapter-e2e.mjs` | 실서버 어댑터를 로컬 stub 응답으로 확인 | 실제 백엔드/모델 |
| `node frontend/scripts/analyze-live.mjs` | 프론트의 직접 Ollama 실험 | Spring pgvector 검색 경로 |
| `npm run build` | 정적 빌드 | 런타임 동작 |

어떤 명령도 이 문서에서 통과했다고 주장하지 않습니다.

## 초기화·재색인과 제한

```bash
docker compose down
docker compose down -v
docker compose up --build -d
```

첫 명령은 DB volume을 유지합니다. `down -v`는 상품·분석·검토·감사·검색 청크/스냅샷을 모두 삭제합니다. 재기동 후 새 분석을 요청하면 선택 근거가 다시 색인됩니다. 별도 reindex 버튼이나 명령은 없습니다.

- 로컬 모델 출력은 비결정적이고 최초 로드가 느릴 수 있습니다.
- top 6은 선택된 활성 근거 안에서만 검색합니다. 작은 합성 코퍼스는 실제 금융 문서 성능을 대표하지 않습니다.
- PDF 추출/OCR 품질은 별도 검토 대상이며 업로드 바이너리는 서버에 영구 보존되지 않습니다.
- `VITE_DEBUG_AI_CONTROLS`의 직접 Ollama/RAG 및 `VITE_EXPERIMENTAL_CLIENT_EXTRACTION`은 격리된 실험 기능입니다. 운영 권위는 백엔드 응답이며 실험 결과를 실제 API 데이터로 취급하면 안 됩니다.
- 모델/네트워크 오류는 fixture 결과로 대체되지 않습니다. 인증, 개인정보, 원격 운영 배포는 이 로컬 데모 범위 밖입니다.
