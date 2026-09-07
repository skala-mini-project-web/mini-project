# Real browser E2E coverage receipt

- Mode: Docker Compose real frontend·backend·ai-service + local Ollama
- Browser: isolated PM and compliance reviewer contexts
- Synthetic source: `SMART-INCOME-SALES-v1.pdf`, `MALFORMED-SYNTHETIC.pdf`
- Outcome: PASS

## Covered actual screen flows

- PM creates product and uploads a real PDF through the file chooser.
- PDFBox extraction reaches `READY`; source checksum and extracted Korean text are verified.
- PM edits confirmed text through the visible textarea, confirms it, and verifies the immutable raw extraction is unchanged.
- PM verifies an official fact, starts pgvector/Ollama RAG analysis, requests review, and sees approved Risk Pattern and GuardFit output.
- PM mutation after analysis receives actual `409 DOCUMENT_ALREADY_ANALYZED`; reload confirms the previously confirmed text remains unchanged.
- Reviewer opens the same document; confirmed-text textarea is disabled and no owner mutation control is visible.
- A malformed synthetic PDF reaches `FAILED`; the UI shows `DOCUMENT_EXTRACTION_FAILED`, sanitized public text, and no retry button.
- Browser asserts no page errors, request failures, or unexpected API responses.

## Expected non-success API responses

- `404 REVIEW_NOT_FOUND` before a review exists, observed by existing result polling.
- `409 DOCUMENT_ALREADY_ANALYZED` for the deliberate post-analysis PM mutation attempt.

## Explicit non-coverage

- Delete is not tested because the backend exposes no `DELETE` endpoint or deletion UI.
- A real retry-success path is not claimed: no deterministic real transient extractor fault exists yet.
- Deterministic score ledger, durable batch queue, and Korean scanned-PDF OCR are covered by their own receipts, not by this run: `batch-boundary-e2e.md`, `batch-cancel-e2e.md`, `korean-ocr-e2e.md`, `cross-role-authz-e2e.md`, `critical-visual-e2e.md`, and the `SCORED` assertion in `frontend/scripts/real-rag-full-flow-e2e.mjs`.
