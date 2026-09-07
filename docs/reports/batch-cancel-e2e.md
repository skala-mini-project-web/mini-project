# Real batch cancellation browser E2E

## Scope

`frontend/scripts/real-batch-cancel-e2e.mjs` is a real localhost browser test against the Docker-served frontend, Spring backend, and AI service. It does not mock or intercept an API. PM and compliance reviewer use separate Playwright browser contexts and authenticate through the visible demo-role screen.

The change that introduced this test did not execute Docker or the E2E. A PASS is claimed only by a generated JSON receipt whose `outcome` is `PASS`.

## Run

With the normal local Docker application already running:

```bash
cd frontend
node scripts/real-batch-cancel-e2e.mjs
```

Defaults:

- frontend: `http://127.0.0.1:5173`
- backend: `http://127.0.0.1:8080`
- AI service: `http://127.0.0.1:8000`
- browser: headless Chromium

Optional environment variables are `BATCH_FRONTEND_URL`, `BATCH_BACKEND_URL`, `BATCH_AI_URL`, `PLAYWRIGHT_EXECUTABLE_PATH`, `BATCH_HEADLESS=false`, `BATCH_CANCEL_E2E_RECEIPT`, and `BATCH_CANCEL_E2E_SCREENSHOT_DIR`.

## Proven browser flow

1. Preflight checks the three real services and the checked-in synthetic PDF.
2. A product manager creates a product through the product dialog.
3. The PM selects 100 uniquely named copies of the real synthetic PDF through the browser file chooser and submits the maximum-size batch through the upload dialog.
4. The script requires the create response and visible detail screen to remain `PENDING`, then captures the observable pre-cancel state.
5. The PM clicks the visible **배치 취소** control. The actual cancellation response must be HTTP 2xx with batch state `CANCELLED` and reason `사용자 취소`.
6. The PM-visible terminal screen and API payload must agree: all 100 items are `CANCELLED`; pending, leased, retry-wait, succeeded, and quarantined counts are zero; cancelled count is 100; all counters sum to the requested count; aggregate attempts equal item attempts; and batch/item reasons and terminal timestamps are present.
7. A full browser reload must return the identical durable cancellation projection and terminal UI.
8. Terminal PM controls cannot cancel or quarantine the batch/items. Retry is visible because the current product contract expressly permits retry for `CANCELLED` items (`canRetry` in the detail view and the retry endpoint contract); the E2E records this exception but deliberately does not mutate the terminal receipt.
9. A separately authenticated compliance reviewer opens the same batch and must see the same durable terminal data, cancellation reason, timestamps-backed state, and reconciled counters. The read-only notice is visible; the management column and every mutation control are absent.

The scenario intentionally uses 100 real synthetic uploads rather than an artificial API delay or route mock. The large queued batch leaves a real lifecycle window for the PM action. If a worker wins that window, the cancellation endpoint can return the contractually expected `409 ACTION_ALREADY_FINALIZED`; that response is recorded under `expectedCancellationResponses`, not misreported as a page/request failure, but the run still fails because it did not prove a successful PM cancellation lifecycle. Other non-2xx API responses remain failures.

## Evidence

Each run writes an immutable, run-keyed receipt by default:

- `docs/reports/v1/batch-cancel-e2e-<run-key>.json`

The receipt includes service URLs, actor IDs/roles, product and batch IDs, the synthetic fixture path/size/SHA-256, all unique submitted filenames, cancellation response status, first terminal batch/items, reload-persistence assertion, terminal mutation contract, reviewer authorization assertions, screenshots, and categorized browser/API signals.

Each run also writes full-page screenshots under:

- `docs/reports/v1/batch-cancel-e2e-<run-key>/pm-pending-before-cancel.png`
- `docs/reports/v1/batch-cancel-e2e-<run-key>/pm-terminal-cancelled.png`
- `docs/reports/v1/batch-cancel-e2e-<run-key>/pm-terminal-cancelled-after-reload.png`
- `docs/reports/v1/batch-cancel-e2e-<run-key>/reviewer-terminal-cancelled-read-only.png`

On failure, the receipt remains `FAIL`, includes the exception and categorized signals, and the script attempts PM/reviewer failure screenshots. A successful receipt requires no page errors, console errors, browser request failures, or unexpected non-2xx API responses, plus exactly one observed HTTP 2xx batch-cancellation response.
