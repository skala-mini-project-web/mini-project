# Durable batch 1–100 boundary browser E2E

## Scope

`frontend/scripts/real-batch-boundary-e2e.mjs` proves the advertised inclusive 1–100 upload boundary through the actual PM and reviewer browser workflow. It uses the already-running localhost frontend, Spring backend, database, batch worker, OCR service, and object storage. It does not install Playwright routes, call mock APIs, or replace processing with fixtures.

A fresh PM-owned product is created through the UI. The PM then submits two independent batches through the **문서 일괄 업로드** dialog:

1. exactly one valid synthetic PDF;
2. exactly 100 valid synthetic PDFs with unique filenames.

Every selected browser file contains the same known-valid synthetic PDF bytes, but each upload has a run-scoped unique filename. Reusing deterministic valid content isolates the boundary under test from document-content variation; this is a correctness check, not a throughput or stress test.

## Assertions

For each boundary, the script requires all of the following:

- the browser selection list contains exactly 1 or 100 entries;
- `POST /api/products/{productId}/document-batches` accepts exactly 1 or 100 items and is not an idempotent replay;
- the persisted batch reaches `SUCCEEDED` and has a non-empty terminal timestamp;
- item IDs, document IDs, filenames, and ordinals are unique;
- ordinals are exactly the complete range `1..N`;
- the items endpoint returns exactly `N` rows and reports exactly `N` total elements;
- pending, leased, retry-waiting, cancelled, and quarantined counts are zero;
- succeeded count and the sum of all aggregate state counts are exactly `N`;
- every item is `SUCCEEDED`, has a terminal timestamp, and has exactly one attempt;
- batch `totalAttemptCount` is exactly `N`, guarding against missing or duplicate processing;
- a PM navigation reload preserves the same terminal batch/item invariants;
- the PM page displays the exact terminal summary and exactly `N` item rows;
- an isolated reviewer browser session reloads the same durable state, displays the exact summary and rows, shows the read-only notice, and exposes no mutation controls.

The receipt records every observed API response. The run also fails on any non-2xx API response, browser page error, console error, or request failure. It explicitly verifies the expected PM product/batch APIs and PM/reviewer detail and 100-item listing APIs were observed.

## Run

Prerequisites are already-running real Docker services at their localhost ports and an existing Playwright/Chromium installation. This script does not start, stop, build, or reconfigure Docker services.

```bash
cd frontend
node scripts/real-batch-boundary-e2e.mjs
```

Optional environment variables:

- `BATCH_BOUNDARY_FRONTEND_URL` (default `http://127.0.0.1:5173`)
- `BATCH_BOUNDARY_BACKEND_URL` (default `http://127.0.0.1:8080`)
- `BATCH_BOUNDARY_AI_URL` (default `http://127.0.0.1:8000`)
- `BATCH_BOUNDARY_TIMEOUT_MS` (default `600000`)
- `BATCH_BOUNDARY_HEADLESS=false` to show Chromium
- `PLAYWRIGHT_EXECUTABLE_PATH` for an existing Chromium binary
- `BATCH_BOUNDARY_RECEIPT` to override the JSON receipt path
- `BATCH_BOUNDARY_SCREENSHOT_DIR` to override the screenshot directory

## Evidence

A successful default run writes a run-specific JSON receipt under:

```text
docs/reports/runtime/batch-boundary-e2e/receipt-<run-key>.json
```

It also writes stable full-page screenshots (overwritten by the next run):

```text
docs/reports/runtime/batch-boundary-e2e/pm-1-file-terminal.png
docs/reports/runtime/batch-boundary-e2e/pm-100-file-terminal.png
docs/reports/runtime/batch-boundary-e2e/reviewer-1-file-terminal.png
docs/reports/runtime/batch-boundary-e2e/reviewer-100-file-terminal.png
```

The receipt contains fixture hash and byte size, actor/session separation, product and batch IDs, all unique submitted filenames, accepted counts, persisted terminal aggregates and items, expected API observations, screenshots, and the complete page-error/console-error/request-failure/API-response signal sets. On failure, it still writes the receipt and best-effort PM/reviewer failure screenshots so the failing browser state remains inspectable.

## Interpretation

A passing receipt demonstrates stable terminal aggregate correctness at both advertised endpoints through the real browser upload and role workflows. It does not claim load capacity, processing throughput, or behavior above 100 files.
