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

## 검증 분류와 제한

- Genuine RAG E2E는 실제 Compose에서 정본 상품 PDF를 업로드하고 분석 결과의 `retrievalTrace`와 인용을 직접 감사하는 절차입니다.
- 백엔드 테스트는 PostgreSQL/pgvector 구조와 검색 계약을 검사할 수 있어도 fake 분석 provider 또는 embedding 대역을 사용하므로 실제 두 Ollama 모델의 E2E 증거가 아닙니다.
- AI 서비스 pytest, 프론트 smoke/AI/adapter 스크립트도 fixture·monkeypatch·로컬 stub 기반입니다. `real-e2e-preflight.mjs`는 health 확인뿐입니다.
- 모델 출력은 비결정적이고 최초 로드는 느릴 수 있습니다. top 6 유사도는 관련도일 뿐 법적 정확성이나 승인 판정이 아닙니다.
- 선택하지 않은 문서는 검색하지 않으며 작은 합성 코퍼스의 예상 결과는 실제 문서 일반화 성능을 증명하지 않습니다.
- 모델 부재, Ollama 연결 실패, 임베딩 차원 불일치는 분석 실패로 드러나며 fixture fallback하지 않습니다.

위 명령은 실행 안내이며 이 문서는 통과를 주장하지 않습니다.
