# ARGUS 구현 Gap Analysis

기준: API v0.3.1, `GuardLab_AI_Flow_QA_Instructions_v1.md` §5/§10/§11/§12. 상태는 2026-09-03 Phase 2~6 반영 후 기준이다.

| 파일 | 기존 차이 | 반영 결과 | 상태 |
|---|---|---|---|
| `src/api/mock/fixtures/v1/index.js` | Golden JSON이 런타임 store와 분리되고 참조 검증이 없었음 | 숫자 ID Golden 데이터, Persona 2명×3회, Red Team 2건, Evaluator Finding 2건, 5/6 패턴, Suggestion과 FK validator를 실행 가능한 모듈로 제공 | IMPLEMENTED |
| `src/api/mock/fixtures/v1/*.json` | 계약 예시 뼈대만 존재 | manifest에서 런타임 모듈과 완전한 결과 예시를 함께 선언 | IMPLEMENTED |
| `src/api/mock/seed.js` | 문자열 ID 대량 seed가 기본 store를 점유 | Golden fixture를 기본 store에 연결. 기존 대량/문자열 seed는 삭제하지 않고 `VITE_DEMO_BULK_SEED=true`에서만 포함 | IMPLEMENTED |
| `src/api/mock/server.js` | 최종 Finding만 생성, 단계/공식 사실/중복 해시 없음 | 전체 Mock 오케스트레이션 결과 저장, 선택 stage, Ground Truth 확인, 숫자 신규 ID, SHA-256 inputHash와 409, Review/Risk/GuardFit 추적 read model 구현 | IMPLEMENTED |
| `src/lib/analyze.js` | 직접 LLM Finding 분석만 존재 | 기존 실험 API를 유지하면서 `orchestrateMockAnalysis()`로 하나의 Analysis 산출물 전체를 생성 | IMPLEMENTED_DIFFERENTLY |
| `src/api/index.js` | Mock 기본 업로드가 브라우저 추출을 실행하고 localStorage 값만으로 Ollama가 활성화 | 기본 Mock은 사전 제작 추출 텍스트 사용. 클라이언트 추출은 `VITE_EXPERIMENTAL_CLIENT_EXTRACTION=true`, Ollama는 `VITE_DEBUG_AI_CONTROLS=true`에서만 활성화 | IMPLEMENTED |
| `src/views/AnalysisNewView.vue` | 디버그 컨트롤 상시 노출, Ground Truth 확인/반복 안내 없음 | 디버그 게이트, Fact 확인, Persona 기준/질문 요약, 3회 안내, 단일 CTA, 중복 분석 기존 route 이동 구현 | IMPLEMENTED |
| `src/views/AnalysisResultView.vue` | Finding 중심 결과와 단일 진행 문구 | 점수 근거, Persona 요약/Run, Red Team, Finding, 패턴, GuardFit 후보, 공식 사실, provenance 및 선택 필드 안전 렌더링 | IMPLEMENTED |
| `src/views/ReviewDetailView.vue` | Finding 선택 외 AI 추적 정보 부족 | category/type, 재현율, Run/Fact/Evidence/Case, Suggestion 미리보기 추가. Review 승인과 GuardFit 승인은 분리 유지 | IMPLEMENTED |
| `src/views/RiskLibraryView.vue` | Action 폼이 빈 값으로 시작하고 Analysis 역추적 없음 | 원본 Analysis/Review/Finding 추적 및 AI Suggestion 폼 초깃값 적용 | IMPLEMENTED |
| `src/views/GuardFitView.vue` | 승인 Action에서 근거 추적 불가 | 선택 `supportingContext`가 있을 때 Source/Evidence/Case와 ID 관계를 표시하고 없으면 UI 숨김 | IMPLEMENTED |
| `src/views/DashboardView.vue` | 알림 빈 상태가 큰 중앙 블록 | 벨을 헤딩 옆으로 이동하고 동일 문구를 상단 정렬 compact empty로 표시 | IMPLEMENTED |
| `scripts/smoke.mjs` | 문자열 ID/구 계약과 Golden 전체 흐름 불일치 | 숫자 reference, Fact 승인, 전체 승인 흐름, 정렬 순서가 다른 중복 입력 409 검증으로 갱신 | IMPLEMENTED |
| 실제 Backend/OpenAPI | Ground Truth, stage, AI detail endpoint/read model 합의 확인 불가 | Mock DTO와 실제 API facade 경로만 준비 | DEFERRED |
| 실제 AI Provider 다중 Agent | 프론트에서 운영 AI를 실행하지 않음 | 지시서 경계대로 Mock deterministic orchestration만 제공 | MOCK_ONLY |
| OCR_REQUIRED/대용량 운영 추출 | 브라우저 실험 추출과 Mock fixture만 존재 | 실제 운영 추출/OCR 상태는 Backend 구현 필요 | DEFERRED |

## 계약 및 Phase 상태

- Phase 1 계약 정리: **IMPLEMENTED**
- Phase 2 최소 Fixture와 숫자 FK 검증: **IMPLEMENTED**
- Phase 3 단일 요청 전체 오케스트레이션 및 화면: **IMPLEMENTED** / 실제 AI는 **MOCK_ONLY**
- Phase 4 OCR/RAG/Ollama 기본 경로 격리: **IMPLEMENTED**
- Phase 5 SHA-256 정규화·중복 409·Idempotency-Key: **IMPLEMENTED**
- Phase 6 Golden E2E 코드와 회귀 스크립트: **IMPLEMENTED** / 명령 실행은 감독자 QA로 **DEFERRED**
- 실제 Backend DTO/OpenAPI/권한 통합: **DEFERRED**

## 구현 선택 근거

1. 기존 대량 시드를 삭제하지 않고 `VITE_DEMO_BULK_SEED` 뒤로 격리했다. 기본 store와 Golden E2E는 숫자 ID만 사용하고 목록/페이지네이션 데모가 필요할 때만 기존 데이터를 복원한다.
2. Vue Router param은 문자열이므로 Mock API 경계에서 `String(id)` 비교를 사용하되 응답 Entity ID는 number를 유지한다. 숫자 ID를 문자열 ID로 변환하는 Adapter는 두지 않았다.
3. Ground Truth와 AI 산출물 API는 v0.3.1 확장이므로 Mock에서만 완전 제공하며 실제 API 응답에 임의 Fixture를 섞지 않는다.
4. 점수는 `computeRiskScore` 정책으로 계산하며 Suggestion은 `PROPOSED`, Review 이후 Action은 `DRAFT`로 생성되어 자동 승인되지 않는다.
