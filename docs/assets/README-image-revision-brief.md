# README 이미지 재생성 확정 입력

이 문서는 `README.md`와 실제 구현이 일치하도록 이미지 재생성 시 사용하는 단일 사실 기준이다. 아래 항목 외 기능은 이미지에 추가하지 않는다. 모든 예시 문서·상품·규정은 `SYNTHETIC_ONLY`임을 표기한다.

## 공통 스타일

- 기존 ARGUS 서체·밝은 배경·역할 색상 체계를 유지한다.
- 실선은 사용자/DB 요청, 주황 점선은 AI/OCR 호출, 자주색은 reviewer 승인 경계를 뜻한다.
- `latest` tag를 artifact-pinned model이라고 표현하지 않는다.
- OCR confidence를 정확성 또는 PM 확정으로 표현하지 않는다.
- `PM`과 `Compliance Reviewer` 권한을 명확히 분리한다.
- prompt version 문자열은 `ai-service/app/service.py`의 `OLLAMA_PROMPT_VERSION` 현재 값(`ollama-rag-grounded-v13`)을 그대로 쓴다. 현재 `ai-prompt-design.png`는 `v6`로 표기되어 있어 다음 재생성 대상이다.

## `system-architecture.png`

### 배치할 구성요소

```text
PM / Compliance Reviewer
        ↓ browser
Vue 3 frontend (PM management / reviewer read-only)
        ↓ REST
Spring Boot backend
  ├─ PDFBox-first page router
  │    ├─ born-digital page → PDFBOX_TEXT
  │    └─ scan/mixed page render PNG → private OCR worker (kor+eng)
  ├─ durable batch worker (PostgreSQL SKIP LOCKED lease/fence)
  ├─ RAG retrieval / execution validation / reviewer-gated score
  ├─ audit log
  ├─ PostgreSQL + pgvector
  └─ AI service → local Ollama
```

- OCR worker는 Docker internal network만 사용하며 host port를 노출하지 않는다.
- OCR worker에는 full PDF가 아닌 rendered page image만 전달한다.
- backend ↔ OCR worker에는 bearer token을 표시한다.
- PostgreSQL 상자는 document extraction run/page provenance, batch attempt audit, immutable execution/anchor, score ledger를 업무 데이터와 함께 표기한다.
- AI service는 DB write 권한이 없고 shared bearer token으로만 호출됨을 표시한다.

## `erd-core.png`

기존 “핵심 테이블 5개” 그림을 대체한다. 모든 컬럼을 한 장에 넣지 말고 아래 4개 cluster와 핵심 FK만 표기한다.

1. **Document/OCR**
   - `products` 1:N `product_documents`
   - `product_documents` 1:N `document_source_revisions`
   - `product_documents.current_extraction_run_id` → `document_extraction_runs`
   - `document_extraction_runs` 1:N `document_extraction_pages`
   - page 핵심: `page_number`, `selected_method`, `source_hash`, `render_artifact_hash`, `text_hash`, `confidence`, `engine/model/language`

2. **Durable batch**
   - `products` 1:N `document_batches`
   - `document_batches` 1:N `document_batch_items`
   - `document_batch_items` 1:N append-only `document_batch_item_attempts`
   - item → `product_documents`는 `(document_id, product_id)` ownership FK
   - item 핵심: status, due/lease/fence/attempt, terminal reason

3. **Analysis/provenance**
   - `analyses` 1:N `analysis_executions`
   - execution 1:N `findings`
   - finding 1:N `finding_revisions`, `finding_evidence_anchors`, `finding_review_decisions`
   - `analysis_rag_retrieval_snapshots`, `analysis_ground_truth_fact_snapshots`, evidence chunk tables를 execution에 연결한다.

4. **Review/score/action**
   - `analyses` 1:1 `reviews`
   - reviewer decision → `risk_patterns` → `guardfit_actions`
   - reviewer-approved/current execution/exact anchor만 → `evidence_risk_score_runs` → append-only score ledger entries

`audit_events`는 모든 cluster를 관찰하는 append-only audit sink로 표시한다.

## `erd-v3.png`

V1–V24 migration 전체 ERD로 재생성한다. 최소한 다음 V17–V24 추가를 누락하지 않는다.

- V17 `analysis_executions`
- V18 finding revisions/evidence anchors
- V19 finding review decisions
- V20 situation-based personas
- V21 risk score runs/ledger
- V22 finding policy rule code
- V23 document batch queue/items/attempts
- V24 document extraction runs/pages

읽기 어려운 단일 초대형 그림 대신 색상 cluster, legend, cross-cluster FK만 선명하게 표시한다.

## `project-folder-structure.png`

root tree에 다음을 추가한다.

```text
ocr-worker/
  Dockerfile
  requirements.txt
  app/main.py
backend/src/main/resources/db/migration/
  V1 ... V24
tools/
  generate_synthetic_ocr_fixtures.py
  validate_synthetic_ocr_fixtures.py
data/synthetic-ocr-fixtures/
frontend/scripts/
  real-rag-full-flow-e2e.mjs
  real-batch-e2e.mjs
  real-batch-boundary-e2e.mjs
  real-batch-cancel-e2e.mjs
  real-korean-ocr-e2e.mjs
  real-cross-role-authz-e2e.mjs
  real-critical-visual-e2e.mjs
```

파일 흐름은 `PM upload → durable storage → page extraction/provenance → PM save/confirm → analysis execution → reviewer decision → score/action`으로 변경한다. 이전 V8–V11만 표시한 migration strip은 제거한다.

root tree는 repository에 실제 있는 항목만 표시한다. `참고 파일/`, `.github/`처럼 repository 밖 로컬 작업 폴더나 없는 디렉터리는 넣지 않는다.

## `service-usage-flow.png`

README `핵심 흐름` 아래에 넣는 전체 이용 흐름이다. 세 band로 그린다.

1. 문서 등록·추출·확정 (PM): 로그인/상품 등록 → 단일 PDF 또는 1–100 batch 업로드 → PDFBox-first page route(`PDFBOX_TEXT` / `OCR_KOR_ENG`) → PM text save → current run/text-hash 확정(stale 409)
2. 분석 (PM, backend/FastAPI/Ollama): VERIFIED fact snapshot → immutable 분석 입력(evidence 1–3·persona·Red Team rule) → pgvector retrieval snapshot → Ollama option ID 선택 → Spring exact anchor/scope 검증
3. 검토·점수·조치 (PM → Compliance reviewer): 검토 요청 → reviewer decision → APPROVED는 deterministic score ledger → Risk Pattern → GuardFit, REJECTED는 comment 필수·승격 없음·`NOT_SCORED`

batch terminal state(`SUCCEEDED`/`CANCELLED`/`QUARANTINED`), 역할 경계 위반 403, PM 반려 확인 화면을 보조 박스로 표시한다.

## `ai-logic-flow.png`

다음 순서로 그린다.

1. PM upload / batch submission
2. page route: PDFBox 또는 OCR
3. run/page provenance 저장, PM save then current run/text-hash confirmation
4. confirmed document + VERIFIED fact snapshot + active evidence 1–3 + persona + Red Team pack
5. pgvector retrieval top-k, immutable retrieval snapshot
6. AI returns structured Finding + `policyRuleCode` + `evidenceSpanOptionIds`
7. backend validates current execution, exact evidence option/anchor, persona/fact/document scope
8. reviewer approve or reject
9. approved current execution/exact anchor only → deterministic Policy v1 score ledger
10. reviewer Risk Pattern/GuardFit action; PM sees read-only terminal result

No fallback from OCR-routed page to PDFBox text on OCR failure. OCR blank/corrupt page must end in failed/quarantined state, not READY.

## `ai-input-output-json-schema.png`

- request: `productDocumentId`, `evidenceDocumentIds` (1–3), `personaIds`, `redTeamPackId`, confirmed document and verified facts server-side snapshot
- response Finding: statement, severity, affected persona IDs, `policyRuleCode`, `evidenceSpanOptionIds`, recommendation
- prohibit model-generated excerpts, arbitrary document/chunk IDs, non-selected persona/fact IDs, and raw risk score.
- backend maps option ID to exact retrieved chunk excerpt and writes immutable anchors/execution snapshot.
- score is a backend deterministic post-review output, not LLM output.

## `ai-extension-points.png`

현재 구현 영역으로 표시:

- page-level Korean OCR
- durable 1–100 batch queue
- reviewer-gated deterministic score ledger
- PM/reviewer role separation

향후 확장 영역으로만 표시:

- held-out TEVV calibration and score-band recalibration
- artifact-pinned model comparison
- hybrid/rerank retrieval evaluation
- support-matrix/browser/load validation when a production target declares it

## 화면 이미지/GIF

`argus-main-page.gif`가 아래를 보여주지 않으면 새 녹화본으로 교체한다.

- page provenance (`PDFBOX_TEXT`, `OCR_KOR_ENG`), confidence warning, PM save/confirm
- batch terminal summary/cancel or quarantine state
- reviewer read-only decision state
- score ledger/approved Risk Pattern/GuardFit

390px viewport에서는 product filter, mobile navigation, dialog action, review rejection action이 horizontal overflow 없이 보여야 한다.

## 검증 근거

- backend full test suite
- actual Docker/browser RAG score E2E
- actual 1-file/100-file batch boundary E2E
- actual batch cancellation E2E
- actual OCR normal/blank/corrupt/LOW-confidence/stale-confirmation E2E
- actual PM/reviewer adversarial authorization E2E
- desktop/390px critical visual screenshot E2E
