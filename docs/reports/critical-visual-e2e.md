# Critical responsive visual E2E

## Scope

`frontend/scripts/real-critical-visual-e2e.mjs` runs Playwright against the real frontend and Spring API. It creates a fresh product and workflow records through the browser, uses only PDFs declared `SYNTHETIC_ONLY` by `data/synthetic-ocr-fixtures/manifest.json`, and does not install request routes or use the frontend mock server.

The run covers the following actual persisted states at both `1440 × 1000` and `390 × 844`:

| State | Browser/API source | Required visual assertions |
| --- | --- | --- |
| Product creation dialog | PM browser creates the product through `POST /api/products` | dialog, focused field, and primary action are inside the viewport |
| Terminal mixed batch error | low-confidence, corrupt, and born-digital synthetic PDFs are uploaded through the batch dialog; the worker persists the corrupt item as `QUARANTINED` | no page overflow, retry action visible, Korean public error present, and error text wraps at 390px |
| Low-confidence OCR confirmation | successful batch document loaded from `GET /api/documents/{id}` | actual `OCR_KOR_ENG` page has `LOW` confidence, unconfirmed gate is visible, analysis remains disabled, and confirmation action is visible |
| Review request dialog | confirmed born-digital document is analyzed and submitted through the PM UI | dialog, focused submission field, and submit action are not clipped |
| Review rejection decision | reviewer opens the real pending review | focused rejection comment and reject action are visible at both widths |
| Persisted rejected review | reviewer submits `REJECTED`, then reloads the review detail from the API | `반려됨` and the long Korean reason render without horizontal clipping |

Every state also asserts that `documentElement` and `body` do not exceed the viewport width. Screenshots disable animation, transition, and caret rendering to keep pixels stable; filenames are stable and are overwritten by the next successful run.

## Run

Prerequisites are the already-running real frontend, backend, database, batch worker, OCR worker, and real analysis provider configuration. Playwright or `playwright-core` must already be installed.

```bash
cd frontend
node scripts/real-critical-visual-e2e.mjs
```

Optional environment variables:

- `CRITICAL_VISUAL_FRONTEND_URL` (default `http://127.0.0.1:5173`)
- `CRITICAL_VISUAL_BACKEND_URL` (default `http://127.0.0.1:8080`)
- `CRITICAL_VISUAL_HEADLESS=false` to show Chromium
- `PLAYWRIGHT_EXECUTABLE_PATH` for an existing Chromium binary
- `CRITICAL_VISUAL_RECEIPT` to override the JSON receipt
- `CRITICAL_VISUAL_SCREENSHOT_DIR` to override the screenshot directory

## Evidence and receipts

Default screenshot receipts:

```text
docs/reports/runtime/critical-visual-e2e/product-create-dialog-desktop.png
docs/reports/runtime/critical-visual-e2e/product-create-dialog-mobile-390.png
docs/reports/runtime/critical-visual-e2e/batch-terminal-error-desktop.png
docs/reports/runtime/critical-visual-e2e/batch-terminal-error-mobile-390.png
docs/reports/runtime/critical-visual-e2e/ocr-low-confidence-confirmation-desktop.png
docs/reports/runtime/critical-visual-e2e/ocr-low-confidence-confirmation-mobile-390.png
docs/reports/runtime/critical-visual-e2e/review-request-dialog-desktop.png
docs/reports/runtime/critical-visual-e2e/review-request-dialog-mobile-390.png
docs/reports/runtime/critical-visual-e2e/review-rejection-decision-desktop.png
docs/reports/runtime/critical-visual-e2e/review-rejection-decision-mobile-390.png
docs/reports/runtime/critical-visual-e2e/review-rejected-desktop.png
docs/reports/runtime/critical-visual-e2e/review-rejected-mobile-390.png
```

The machine-readable receipt is written even on failure:

```text
docs/reports/runtime/critical-visual-e2e-<UTC-run-key>-<pid>.json
```

A passing receipt records synthetic provenance, isolated PM/reviewer identities, product/batch/document/analysis/review IDs, all screenshot paths with exact viewport dimensions, responsive assertion flags, page errors, and request failures. A failing receipt records the error and stack so missing evidence cannot be mistaken for a passing visual run.

This change intentionally does not include generated screenshots: evidence must be regenerated from the running real services immediately before documentation images are refreshed. No test, build, Git, or Docker command was run while adding this QA lane.
