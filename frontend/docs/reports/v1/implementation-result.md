# ARGUS V1 구현 결과

## 1. 구현 요약

API v0.3.1 계약 정리 후 숫자 ID Golden Fixture를 기본 Mock store에 연결했다. 분석 요청 한 번으로 Persona 2명×3회, 독립 Red Team, Evaluator Finding, 서버 점수, 5/6 취약 패턴, GuardFit Suggestion을 동일 Analysis에 저장한다. Review가 채택한 Finding만 Risk Pattern으로 승격되고 GuardFit Action은 별도 DRAFT/APPROVED 단계로 유지된다.

## 2. 변경 파일과 역할

| 파일 | 변경 내용 |
|---|---|
| `src/api/mock/fixtures/v1/index.js` | 실행 가능한 Golden Fixture, 전체 오케스트레이션 결과와 FK/number ID validator |
| `src/api/mock/fixtures/v1/manifest.json` | 런타임/JSON fixture 진입점 선언 |
| `src/api/mock/seed.js` | Golden 기본 store 연결, 문자열/대량 seed를 `VITE_DEMO_BULK_SEED`로 격리 |
| `src/api/mock/server.js` | Ground Truth, 전체 결과, stage, SHA-256 중복, Review/Risk/GuardFit 추적 구현 |
| `src/lib/analyze.js` | Mock 전체 오케스트레이션 함수와 실험 Local AI 경로 분리 |
| `src/lib/guardrails.js`, `src/api/mock/scenarios.js` | v0.3.1 Finding 계약 및 서버 점수 정책 |
| `src/api/index.js` | Mock Fact API, 기본 fixture 추출, OCR/Ollama opt-in 게이트 |
| `src/api/client.js`, `src/api/errors.js` | 409의 `existingAnalysisId`를 실제/Mock 공통 오류로 전달 |
| `src/views/AnalysisNewView.vue` | Fact 검증, Persona 상세, 반복 3회, 단일 전체 분석 CTA, debug 제어 숨김 |
| `src/views/AnalysisResultView.vue` | §5.6 결과 전 구역과 선택 필드 안전 렌더링 |
| `src/views/ReviewDetailView.vue` | 재현율, Run/Fact/Rule/Source/Evidence/Case/Suggestion 검토 |
| `src/views/RiskLibraryView.vue` | 역추적 및 Suggestion 기반 Action 초깃값 |
| `src/views/GuardFitView.vue` | 선택 supportingContext 근거 표시와 승인 단계 |
| `src/views/DashboardView.vue` | 알림 5건 제한 및 compact 빈 상태 |
| `src/components/layout/AppSidebar.vue`, `src/views/RoleSelectView.vue`, `index.html`, `README.md`, 사용자 문서 제목 | 사용자 노출 서비스명을 ARGUS로 변경하고 코드 식별자/localStorage key는 유지 |
| `scripts/smoke.mjs`, `scripts/ai-test.mjs`, `scripts/analyze-live.mjs` | 최신 숫자 ID/필드/Golden 흐름 계약으로 갱신 |
| `docs/ai-provider.md`, `docs/reports/v1/gap-analysis.md` | Provider 계약과 구현 차이 최신화 |

## 3. Phase별 결과

| Phase | 상태 | 근거 |
|---|---|---|
| Phase 1 계약 | IMPLEMENTED | `statement`, `categoryCode`, `sourceReferences[]`, `evidenceDocumentId`, Review `status`, 최신 GuardFit enum |
| Phase 2 Fixture | IMPLEMENTED | `fixtures/v1/index.js#validateGoldenFixture`, 기본 seed의 숫자 ID Golden 데이터 |
| Phase 3 전체 흐름 | IMPLEMENTED | `orchestrateMockAnalysis`, 결과/검토/Risk/GuardFit 화면 |
| Phase 3 실제 AI | MOCK_ONLY | 운영 오케스트레이션은 Backend/AI Provider 책임 |
| Phase 4 OCR/RAG 격리 | IMPLEMENTED | 기본 Mock은 `MOCK_FIXTURE`; 브라우저 추출과 Ollama는 명시 환경변수 opt-in |
| Phase 5 SHA-256 | IMPLEMENTED | `analysisInputHash`, 정렬/공백 정규화, non-FAILED 중복 409와 `existingAnalysisId` |
| Phase 6 E2E 코드 | IMPLEMENTED | `scripts/smoke.mjs` Golden 승인 흐름 |
| Phase 6 명령 실행 | DEFERRED | 감독자 일괄 QA 지시로 이번 작업에서 실행 금지 |

## 4. §15 계약/동작 결과

| 요구사항 | 상태 | 근거 |
|---|---|---|
| Golden 저장 Entity ID number | IMPLEMENTED | `fixtures/v1/index.js`, 신규 `nextId()` |
| 상품 검색/route 숫자 ID 안전 | IMPLEMENTED | `String(productId)` 검색, Mock API `sameId` 경계 비교 |
| Finding 최신 필드/선택 aiDetail | IMPLEMENTED | fixture, guardrails, 두 상세 화면 |
| 잘못된 Fixture 참조 실패 | IMPLEMENTED | `validateGoldenFixture()`가 module load 시 실행 |
| Analysis 1건/Persona Run 6건 | IMPLEMENTED | `buildGoldenOutcome()` |
| Persona/Red Team 분리 | IMPLEMENTED | `personaRuns`, `redTeamResults` 별도 배열 |
| Evaluator 역참조 | IMPLEMENTED | `sourceRunIds`, `redTeamResultIds`, `groundTruthFactIds`, Evidence |
| 5/6, stable=true | IMPLEMENTED | fixture 집계와 결과 화면 |
| riskScore 82/정책 합 | IMPLEMENTED | `computeRiskScore`, `scoreBreakdown` |
| 정렬 무관 SHA-256/409 | IMPLEMENTED | `analysisInputHash`, smoke의 역순 배열 case |
| FAILED retry 동일 Analysis | IMPLEMENTED | 기존 `retryAnalysis` 유지 |
| 선택 Finding만 Risk Pattern | IMPLEMENTED | `decideReview` 검증/승격 |
| Suggestion→DRAFT Action→APPROVED | IMPLEMENTED | Risk Library 초깃값과 별도 GuardFit 승인 |
| PM APPROVED만 조회 | IMPLEMENTED | `listGuardFitActions` RBAC 필터 |
| supportingContext 유무 조건부 UI | IMPLEMENTED | server read model, GuardFit `v-if` |
| Tesseract/Ollama 없는 기본 E2E | IMPLEMENTED | API facade 기본 fixture 추출/Mock orchestration |
| 실제 Backend/OpenAPI 확장 | DEFERRED | 아래 합의 필요 |

## 5. 다르게 구현한 항목

- **IMPLEMENTED_DIFFERENTLY**: 권장 JSON 파일을 직접 import하면 Node smoke의 JSON import attribute와 Vite 간 차이가 생긴다. 따라서 같은 폴더의 `index.js`를 런타임 단일 소스로 사용하고 JSON 파일은 계약 예시/검수 자료로 유지했다.
- **IMPLEMENTED_DIFFERENTLY**: Ground Truth 전용 route를 추가하는 대신 분석 설정 화면 안에 확인 단계를 배치했다. 분석 CTA 직전 VERIFIED 조건이 드러나고 기존 P0 route 수를 늘리지 않는다.
- **IMPLEMENTED_DIFFERENTLY**: 기존 문자열 seed는 삭제하지 않고 환경변수 뒤로 격리했다. 기본 Golden store에는 숫자 ID 데이터만 들어간다.

## 6. DEFERRED / NOT_IMPLEMENTED

1. **DEFERRED** 실제 Backend의 Ground Truth API, 선택 `stage`, `aiDetail`, Persona/Red Team 상세, `supportingContext` DTO와 OpenAPI 합의.
2. **DEFERRED** 운영 OCR/문서 추출의 `OCR_REQUIRED`, 재시도와 대용량 성능. 프론트 실험 코드는 opt-in이며 권위 결과는 Backend가 제공해야 한다.
3. **MOCK_ONLY** Persona/Red Team/Evaluator 실제 모델 호출. V1 프론트는 정규화 결과 DTO만 소비한다.
4. **NOT_IMPLEMENTED** 명시적 문서 버전/서로 다른 ProductDocument 간 파일 중복 차단. §11 범위 밖이다.

## 7. Mock과 실제 API 차이

Mock은 사전 제작 Ground Truth와 deterministic AI 전체 결과를 제공한다. 실제 API 모드에서는 Fixture를 섞지 않고 facade가 Backend endpoint를 호출한다. 실제 Backend가 선택 확장 필드를 주지 않으면 화면의 해당 구역은 숨겨진다. 직접 Ollama와 브라우저 추출은 각각 `VITE_DEBUG_AI_CONTROLS=true`, `VITE_EXPERIMENTAL_CLIENT_EXTRACTION=true`일 때만 사용한다.

## 8. 실행 명령과 결과

이번 변경에서는 사용자 지시에 따라 npm/build/test/lint/git 명령을 실행하지 않았다. 따라서 아래는 감독자가 실행할 검증 명령이며 성공을 주장하지 않는다.

```bash
node scripts/ai-test.mjs
node scripts/smoke.mjs
npm run build
VITE_USE_MOCK=false npm run build
```

선택 확인:

```bash
VITE_DEMO_BULK_SEED=true npm run dev
VITE_DEBUG_AI_CONTROLS=true npm run dev
VITE_EXPERIMENTAL_CLIENT_EXTRACTION=true npm run dev
```

## 9. 화면 Route 및 재현 순서

1. `/`에서 상품 담당자 선택
2. `/products` → 상품 등록 → `/products/{productId}`에서 PDF/PPTX 업로드
3. `/documents/{documentId}`에서 추출 텍스트 확정
4. `/products/{productId}/analyze`에서 공식 사실 VERIFIED → Persona/근거/Pack 선택 → 단일 CTA
5. `/analyses/{analysisId}`에서 단계와 전체 결과 확인 → 검토 요청
6. `/`에서 컴플라이언스 검토자 전환 → `/reviews/{reviewId}`에서 Finding 선택 승인
7. `/risk-library`에서 Suggestion 초깃값으로 GuardFit DRAFT 생성
8. `/guardfit`에서 DRAFT 승인 및 근거 확인
9. 상품 담당자로 전환 후 `/guardfit`에서 APPROVED Action만 확인

## 10. 다음 담당자 우선 확인

- 기존 `guardlab.store.v1`은 key를 유지하면서 `__schemaVersion: 2`가 아닌 payload를 자동 무시하는지 확인
- 동일 입력의 evidence/persona 순서만 바꾼 두 번째 POST가 409와 `existingAnalysisId`를 반환하는지 확인
- riskScore 82와 breakdown 합, Persona 2×3 및 취약 패턴 5/6 확인
- `VITE_USE_MOCK=false` 빌드에서 Mock 선택 필드 참조가 컴파일을 깨뜨리지 않는지 확인
- 모바일 폭에서 분석 결과/설정 및 Dashboard compact 알림 빈 상태 확인
