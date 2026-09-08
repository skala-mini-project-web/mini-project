# ARGUS Backend: pgvector RAG

이 백엔드가 genuine RAG의 검색 경계를 소유합니다. 과거처럼 선택한 근거 전체를 단순히 프롬프트에 넣는 것은 RAG가 아닙니다. 현재 경로는 **근거 텍스트 청크화 → Ollama `bge-m3:latest` 임베딩 → PostgreSQL/pgvector 코사인 top-k 검색 → 검색 청크만 AI 서비스의 `qwen2.5:7b-instruct`에 전달**입니다.

`data/demo-corpus/`의 모든 자료는 100% 합성·데모 전용이며 실제 법률·금융 권위가 아닙니다.

## 실행 구성

저장소 루트의 Compose는 다음을 기본 사용합니다.

- PostgreSQL: `pgvector/pgvector:pg16`, DB `crosschecklab`
- Backend: `real-extraction` 프로필, `http://localhost:8080`
- AI service: 실제 Ollama provider, `http://localhost:8000`
- 임베딩: `bge-m3:latest`(1024차원)
- 채팅: `qwen2.5:7b-instruct`
- Ollama 주소: 컨테이너에서 `http://host.docker.internal:11434`

```bash
ollama pull bge-m3:latest
ollama pull qwen2.5:7b-instruct
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8000/internal/v1/health
curl -fsS http://localhost:8080/actuator/health
node frontend/scripts/real-e2e-preflight.mjs
```

필요할 때 `.env.example`을 `.env`로 복사해 DB와 포트를 바꿉니다. Ollama 설정도 바꿔야 한다면 `.env`에 `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `OLLAMA_EMBEDDING_MODEL`을 추가합니다. Health/preflight 성공은 의존성 도달성만 뜻하며 임베딩·검색·채팅 성공을 대신하지 않습니다. Swagger는 `http://localhost:8080/swagger-ui/index.html`입니다.

## 색인·검색 계약

- 선택된 활성 근거 문서만 색인하고 검색합니다.
- 청크 규칙은 `korean-boundary-v1-1200-200`: 최대 1,200 code point, 200 overlap입니다.
- 청크, 원문 SHA-256, 청크 SHA-256, 청크 버전, 모델명과 vector는 `evidence_document_chunks`에 버전별로 보존됩니다.
- 분석 질의는 확정된 상품 텍스트와 선택 persona/rule/근거 ID에서 만들며, `PgVectorEvidenceRetriever`가 코사인 거리로 최대 6개를 반환합니다.
- AI 서비스에는 rank가 매겨진 검색 청크만 전달합니다. 모델은 Finding마다 전달받은 불변 검색 청크의 `chunkId`를 선택하며 원문 인용을 생성하거나 복사하지 않습니다. 백엔드는 선택된 각 ID가 AI 서비스로 보낸 바로 그 검색 스냅샷에 속하는지 검증하고, 해당 스냅샷의 `evidenceDocumentId`와 정확한 `chunkText`를 Finding 근거로 영속합니다. 텍스트 보정, 추측, fallback은 없습니다.
- 분석 시점의 질의 해시, 검색/모델 버전, 시각, rank, similarity, 청크 본문은 `analysis_rag_runs`와 `analysis_rag_retrieval_snapshots`에 불변 스냅샷으로 남습니다.

분석은 비동기입니다. `POST /api/analyses`의 ID를 폴링한 뒤 완료된 `GET /api/analyses/{analysisId}/result`에서 `retrievalTrace`를 확인합니다. 응답 필드는 다음과 같습니다.

```text
retrievalTrace
  queryHash
  retrievalVersion
  embeddingModel
  retrievedAt
  contexts[]
    chunkId, evidenceDocumentId, sourceType, title, rank, similarity, excerpt
findings[].evidenceReferences[]
  evidenceDocumentId, sourceType, excerpt
```

AI 서비스의 구조화 출력은 `findings[].retrievedContextChunkIds[]`로 검색 청크를 지목합니다. 이 내부 선택 필드는 공개 결과의 인용문을 대체하지 않습니다. 백엔드가 검증·해석한 뒤 공개 `findings[].evidenceReferences[]`에는 기존과 동일하게 문서 메타데이터/ID와 선택 청크의 정확한 원문 `excerpt`가 담깁니다. 감사자는 각 Finding 근거가 동일 `chunkId`의 `retrievalTrace.contexts[]` 문서 ID 및 `excerpt`와 일치하는지 확인해야 합니다. HTTP 호출에는 Swagger에 표시된 데모 인증 헤더 `X-Demo-User-Id`, `X-Demo-Role`과 분석 생성 시 `Idempotency-Key`가 필요합니다.

## 정본 데이터와 재색인

상품 입력은 `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf`, 근거 입력은 아래 3개입니다.

- `data/demo-corpus/documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf`
- `data/demo-corpus/documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf`
- `data/demo-corpus/documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf`

해시/출처는 `data/demo-corpus/manifest.v1.json`, 문서와 청크 기준은 `metadata/documents.v1.json` 및 `metadata/chunks.v1.jsonl`, 기대 사례는 `expected/analysis-cases.v1.json` 및 `expected/rag-cases.v1.json`에 있습니다. PDF 자체가 런타임 DB를 자동으로 채우는 것이 아니라 Flyway의 합성 seed 텍스트가 근거 레코드를 만들며, manifest/metadata는 PDF와 seed의 정합성을 감사하는 정본입니다.

별도 reindex API/명령은 없습니다. 분석 준비 단계에서 선택 근거를 색인합니다. 같은 원문 해시·청크 버전·모델의 완전한 집합은 재사용하며 원문 또는 모델이 달라지면 새 색인이 생깁니다.

```bash
docker compose down          # postgres-data 유지
docker compose down -v       # 모든 DB 업무 기록·청크·검색 스냅샷 삭제
docker compose up --build -d # Flyway 및 합성 seed 재생성
```

완전 초기화 후 첫 새 분석 요청이 청크를 다시 임베딩합니다. `down -v`는 파괴적입니다.

## 문서 처리 안전 경계

- PDF OCR·페이지 미리보기는 이미지 할당 전에 렌더 크기 검사. 40,000,000픽셀 초과 또는 유효하지 않은 페이지 치수 거부. 일반 A4 300DPI는 허용하며 자동 축소하지 않음
- 배치 소속 문서는 단건 `POST /api/documents/{documentId}/retry`에서 `409 DOCUMENT_NOT_RETRYABLE` 반환. 배치의 자동 재시도·lease/fence 경로만 추출 소유권 유지
- OCR worker는 페이지 처리 한 건만 동시 실행. 추가 요청은 대기열 누적 대신 `503 OCR_BUSY`와 `retryable: true` 반환. 실제 동기 OCR 처리는 API 이벤트 루프 밖에서 실행
- 5분 이상 오래된 CREATED·RUNNING 분석은 현재 token과 상태를 잠금 안에서 재검증한 뒤 재시도 가능한 FAILED로 전이. 없는 실행 기록을 만들어내거나 자동 재실행하지 않으며, 개별 복구 실패는 다음 항목 처리를 막지 않음
- 분석 이벤트에 수락 당시 execution token 포함. 이전 요청의 지연 이벤트가 새 재시도를 이전 scenario로 실행하지 못하도록 차단
- 단건 문서는 수락 token·5분 dispatch lease를 저장하고, 실행 시작 시 새 worker token·10분 lease로 회전. 성공·실패·추출 이력 저장 전에 token, 만료 시각, 배치 소속 여부를 잠금 안에서 재검증. 만료된 수락·실행은 재시도 가능한 FAILED로만 복구하며 수동 재시도 의미 유지
- 단건·배치의 문서 전체 처리 예산은 `document-batch.extraction-timeout` / `DOCUMENT_BATCH_EXTRACTION_TIMEOUT`으로 공통 설정, 기본 5분. `OCR HTTP timeout < 전체 처리 timeout ≤ 10분 lease − 30초` 검증. 무제한 페이지 처리나 만료된 원격 OCR의 물리적 중단은 보장하지 않으며 늦은 결과 게시를 fence로 차단
- V25 적용 전 모든 구버전 애플리케이션 worker 중단 필수. 기존 미완료 단건 작업은 만료 claim으로 편입하고 신규 worker가 실패 복구. 구·신 worker 혼합 rolling 배포 금지
- RAG metadata·embedding·query HTTP는 DB 트랜잭션 밖에서 실행. 청크 게시와 분석 RAG 결과 저장은 짧은 트랜잭션에서 원문 hash·현재 token 재검증

## 분석 결과와 점수 계약

- `findings`는 필수 배열이며 0~20건 허용. 누락·null·상한 초과·일부 항목의 잘못된 인용은 전체 응답 거부. 유효하지 않은 결과를 빈 결과로 대체하지 않음
- Provider `riskScore`는 nullable 진단 이력. 빈 결과는 반드시 null이며 공개 점수나 위험 없음 판정으로 사용하지 않음. V26은 성공 실행의 nullable provenance만 허용하고 기존 불변 이력 유지
- 빈 실행의 검토 승인 허용. 승인 전 `PENDING_REVIEW`, 승인 뒤 `NOT_SCORED / NO_APPROVED_FINDING`, 숫자는 null. Finding 결정·Risk Pattern·점수 원장 항목을 만들어내지 않음
- 점수 정책 1.1.0: 동일 문서 revision·원문 범위·규칙의 중복 지적은 한 건, 서로 다른 규칙은 같은 문장에서도 별개 위험. 원본 Finding과 검토 이력 보존
- 기존 규칙별 가중치와 noisy-OR 합산 유지. 안정성·보장 표현 규칙의 기여도 100%에 따른 종합 점수 포화도 유지하며, 이번 수정은 가중치 보정이나 실제 위험 확률의 검증이 아님

## 검증 분류와 제한

- Genuine RAG E2E는 실제 Compose에서 정본 상품 PDF를 업로드하고 분석 결과의 `retrievalTrace`와 인용을 직접 감사하는 절차입니다.
- 백엔드 테스트는 PostgreSQL/pgvector 구조와 검색 계약을 검사할 수 있어도 fake 분석 provider 또는 embedding 대역을 사용하므로 실제 두 Ollama 모델의 E2E 증거가 아닙니다.
- AI 서비스 pytest, 프론트 smoke/AI/adapter 스크립트도 fixture·monkeypatch·로컬 stub 기반입니다. `real-e2e-preflight.mjs`는 health 확인뿐입니다.
- 모델 출력은 비결정적이고 최초 로드는 느릴 수 있습니다. top 6 유사도는 관련도일 뿐 법적 정확성이나 승인 판정이 아닙니다.
- 선택하지 않은 문서는 검색하지 않으며 작은 합성 코퍼스의 예상 결과는 실제 문서 일반화 성능을 증명하지 않습니다.
- 모델 부재, Ollama 연결 실패, 임베딩 차원 불일치는 분석 실패로 드러나며 fixture fallback하지 않습니다.

위 명령은 실행 안내이며 이 문서는 통과를 주장하지 않습니다.
