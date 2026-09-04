# ARGUS 정본 합성 데모 코퍼스

이 디렉터리는 로컬 genuine RAG를 재현·감사하기 위한 고정 코퍼스입니다. **모든 상품, 회사, 기관, 사람, 지수, 수치, 규정 및 문서는 100% 합성했으며 데모 전용입니다.** 실제 법령·감독규정·회사 정책·금융상품을 나타내지 않고, 법률 또는 재무 판단의 권위 있는 출처로 사용하면 안 됩니다. `authoritativeForDemo`는 이 데모의 기대값에 대한 정본이라는 뜻일 뿐 현실의 권위를 뜻하지 않습니다.

## 정본 PDF

분석할 상품 입력:

- `data/demo-corpus/documents/product/SMART-INCOME-SALES-v1.pdf` — 판매자료 3쪽

pgvector 검색의 근거 출처:

- `data/demo-corpus/documents/evidence/SMART-INCOME-PRODUCT-POLICY-v1.pdf` — 합성 상품 운영정책 2쪽
- `data/demo-corpus/documents/evidence/IMPORTANT-INFO-DISPLAY-POLICY-v2026.1.pdf` — 합성 중요정보 표시정책 2쪽
- `data/demo-corpus/documents/evidence/FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1.pdf` — 합성 설명의무 규정 발췌 2쪽

경로는 저장소 루트 기준입니다. 상품 PDF는 PM 업로드 입력이고, `documents/evidence/`의 세 PDF만 검색 근거입니다. 서로 바꾸어 사용하지 않습니다.

## 감사 파일

- `data/demo-corpus/manifest.v1.json`: 코퍼스 ID, 합성 선언, 논리 ID, 버전, 라이선스, PDF 상대 경로, SHA-256, byte size, page count, DB에 영속된 상품-근거 매핑
- `data/demo-corpus/metadata/documents.v1.json`: 문서별 메타데이터와 seed 원문 해시
- `data/demo-corpus/metadata/chunks.v1.jsonl`: 기대 청크 버전/순번/해시/code-point 범위와 PDF page/section/exact-text 앵커
- `data/demo-corpus/expected/analysis-cases.v1.json`: 판매 문구별 기대 위험 분석 사례
- `data/demo-corpus/expected/rag-cases.v1.json`: 질의별 기대 검색 문서·청크 사례

PDF 무결성은 `manifest.v1.json`의 SHA-256과 비교합니다. 텍스트/청크 감사 시 `documents.v1.json`의 `contentSourceSha256`과 `chunks.v1.jsonl`의 `sourceHash`, `chunkHash`, 앵커를 함께 대조합니다. expected 파일은 회귀 기대값이지 법률 정답이나 모델 품질 보증이 아닙니다.

## 런타임 관계

Flyway `backend/src/main/resources/db/migration/V10__seed_demo_corpus_evidence.sql`이 세 합성 근거의 텍스트와 아티팩트 메타데이터를 DB에 시드합니다. 이 migration은 덮어쓸 V2 `evidence_documents`의 ID와 `source_type`을 먼저 검증하므로 기존 행이 없거나 예상 타입과 다르면 실패합니다. `demo_corpus_artifacts`는 논리 ID를 PDF 및 근거 DB ID에 연결하고, `demo_corpus_evidence_mappings`는 상품 논리 ID에서 선택 근거 논리 ID와 `evidence_documents.id`로 이어지는 순서 있는 매핑을 영속합니다. manifest와 문서 metadata의 `selectedEvidence`가 이 테이블의 세 행과 일치합니다.

영속된 선택 순서는 `evidence.smart-income.product-policy` → DB ID 3, `evidence.important-info-display-policy` → DB ID 1, `evidence.financial-consumer-explanation-duty-excerpt` → DB ID 2입니다. 이 연결은 합성 데모의 검색 선택 관계일 뿐 현실의 권위나 승인을 뜻하지 않습니다. 백엔드는 분석 요청 시 선택한 근거를 `korean-boundary-v1-1200-200`으로 청크화하고 `bge-m3:latest`로 임베딩하여 pgvector에 저장합니다. 검색된 top 6 청크만 `qwen2.5:7b-instruct` 채팅 단계에 전달됩니다. 단순 선택 근거 프롬프트 주입과 달리 이 단계에는 실제 벡터 검색이 있습니다.

PDF 바이너리를 DB가 직접 읽어 근거를 만드는 구조는 아닙니다. PDF는 사람이 보는 정본 아티팩트이고, seed 텍스트 및 metadata의 해시/앵커가 양쪽의 정합성을 연결합니다. 상품 PDF 업로드 바이너리도 처리 후 서버에 영구 보존되지 않습니다.

## 사용 순서

1. `manifest.v1.json`에서 네 PDF의 경로와 해시를 확인합니다.
2. PM으로 상품 PDF를 업로드하고 추출 텍스트를 확정합니다.
3. `demo_corpus_evidence_mappings` 및 manifest의 논리 ID→DB ID 매핑에 따라 세 근거 문서를 선택하여 분석을 요청합니다.
4. 결과 `retrievalTrace.contexts[]`의 `chunkId`, 문서 ID, rank, similarity, `excerpt`를 `chunks.v1.jsonl`과 PDF 앵커에 대조합니다.
5. 모델은 전달받은 불변 검색 스냅샷의 `chunkId`만 Finding 근거로 선택합니다. 백엔드가 그 ID의 스냅샷 소속을 검증하고 정확한 문서 ID와 청크 원문을 해석해 영속하므로, 공개 Finding의 `evidenceDocumentId`와 `excerpt`가 선택 청크와 일치하는지 확인합니다. 모델이 원문을 byte-for-byte 복사하는 단계는 없습니다.
6. `expected/rag-cases.v1.json`과 `expected/analysis-cases.v1.json`은 기대 회귀 비교에만 사용하고, 차이를 자동으로 법적 오류라 판정하지 않습니다.

DB와 색인을 완전히 새로 만들 때는 저장소 루트에서 `docker compose down -v` 후 `docker compose up --build -d`를 사용하고 새 분석을 요청합니다. 별도 reindex 명령은 없으며 첫 분석 준비 중 재색인됩니다. 이 작업은 기존 상품·분석·검토·감사 데이터까지 삭제합니다. 이 문서는 해당 명령의 실행 성공을 주장하지 않습니다.

## 제한

- 작은 고정 합성 코퍼스라 실제 금융 문서의 언어·길이·OCR 노이즈를 대표하지 않습니다.
- expected 사례는 현재 정본/청크 규칙용이며 모델 버전, 질의, 청크 규칙 변경 시 재검토해야 합니다.
- similarity와 모델 답변은 사실성·준법성·승인을 보장하지 않습니다. 사람 검토가 필수입니다.
