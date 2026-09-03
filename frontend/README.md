# ARGUS Frontend (MVP)

- 금융상품 설명 문서의 표현 리스크를 분석하고 컴플라이언스 검토와 보호조치(GuardFit)까지 연결하는 워크스페이스
- 대상 사용자: 상품 담당자(`PRODUCT_MANAGER`), 컴플라이언스 검토자(`COMPLIANCE_REVIEWER`)
- Vue 3 + Vite 기반 프론트엔드이며 기본 Mock 모드에서는 백엔드 없이 단독 실행 가능

## 스택

- Vue 3, Vite, Vue Router, Pinia
- 순수 CSS 디자인 토큰(`src/styles/tokens.css`), Phosphor 아이콘, Pretendard + JetBrains Mono

## 실행

```bash
npm install
cp .env.example .env
npm run dev
npm run build
```

- 기본 개발 주소: `http://localhost:5173`
- 진입 화면의 역할 선택으로 데모 세션 시작
  - 상품 담당자: `USER-PM-001`
  - 컴플라이언스 검토자: `USER-CR-001`

## Mock 모드 / 실제 백엔드 전환 (Interface First)

- 화면은 `src/api/` 파사드만 호출하며 `.env`로 구현체 전환
- `VITE_USE_MOCK=true`(기본): 인메모리 Mock 서버로 단독 실행
- `VITE_USE_MOCK=false`: `/api`를 Spring 백엔드로 프록시(`VITE_API_BASE`)
- Mock은 API v0.3.1 계약과 AI 전체 흐름(Phase 1~6)을 반영
  - `statement`, `redTeamPackId`, `categoryCode`, `sourceReferences[]`, `evidenceDocumentId`, Review `status`
  - GuardFit `LABEL/WARNING/QUESTION/COMPARISON`, `DRAFT/APPROVED`
- 숫자 ID Golden Fixture가 기본 Mock store
- 기존 문자열 ID 대량 seed는 `VITE_DEMO_BULK_SEED=true`에서만 활성화

## 프론트엔드 구현 범위

- 화면: 역할 선택, 역할별 대시보드, 상품 목록/등록·상세, 문서 워크스페이스, 분석 요청·결과, 검토함·상세, Risk Library, GuardFit, 감사 로그
- 상품 목록: 초성 포함 검색, 유형·상태 필터, 숫자 페이지네이션
- 분석 요청 1회로 Analysis 1건 생성
  - Persona 이해도 실험: 2 Persona × 동일 조건 3회
  - Persona와 분리된 Red Team 독립 검증
  - Evaluator Finding과 선택 `aiDetail`
  - 반복 재현 취약 패턴 후보와 GuardFit 제안 후보
  - VERIFIED 공식 상품 사실, 근거 추적, Provenance
- 점수는 서버 정책으로 계산하며 AI 산출물은 승인 전 후보로만 취급
- 검토: 재현율·Run·공식 사실·규칙·판매 원문·근거·사례 추적, 선택 Finding만 Risk Pattern으로 승격
- GuardFit: AI Suggestion을 DRAFT 초깃값으로 사용하고 검토자가 별도로 APPROVED 처리; 상품 담당자는 승인본만 조회
- SHA-256 `input_hash`: 확정 텍스트, Red Team Pack, 정렬된 Persona/근거 ID로 계산해 동일 문서·동일 입력의 중복 Analysis를 409로 차단
- 알림: 추출·분석·검토 완료 폴링, 토스트, 상품별 읽음 상태
- 공통 상태: 로딩, 빈 상태, 인라인 오류, 오류 계약(400/401/403/404/409/413/503)
- 접근성: 포커스 링, 모달 포커스 트랩, `prefers-reduced-motion` 대응

## 임시 구현물과 격리 경계

- Mock API(`src/api/mock/`): Golden Fixture, 결정론적 AI 전체 결과, Clock 기반 상태 전이, RBAC, 오류 계약
- 기본 업무 경로는 사전 제작 Fixture의 추출 텍스트와 근거를 사용하며 Tesseract/Ollama 없이 동작
- 브라우저 문서 추출·OCR(`src/lib/extract.js`)은 `VITE_EXPERIMENTAL_CLIENT_EXTRACTION=true`일 때만 opt-in
  - PDF.js 텍스트, JSZip PPTX XML, Tesseract OCR
- 직접 Ollama/RAG(`src/lib/{analyze,rag,llm,guardrails}.js`)는 `VITE_DEBUG_AI_CONTROLS=true`일 때만 UI에서 opt-in
  - 로컬 실행 시 `qwen2.5:7b-instruct`, `bge-m3`, Vite `/ollama` 프록시 필요
- 운영 문서 추출·OCR·RAG·AI 오케스트레이션의 권위는 백엔드/AI Provider이며 프론트 실험 결과를 실제 API 응답에 섞지 않음
- 로컬 영속화: Mock store와 알림을 기존 `localStorage` 키로 복구
- 업로드 사전 검증(`src/lib/upload.js`): PDF/PPTX, 10MB, 기본 매직바이트 검사

## 구조

```text
public/           argus-logo.png, favicon.png
src/
  api/            파사드, 실서버 client, Mock 서버·시드·시나리오, fixtures/v1
  components/ui/  디자인 시스템 프리미티브
  components/layout/  ARGUS 사이드바와 앱 셸
  components/     PipelineGraph
  composables/    Polling·비동기 상태
  lib/            format, hangul, upload, extract, rag, llm, analyze, guardrails
  stores/         session, toast, jobs
  views/          업무 화면
  styles/         디자인 토큰과 공통 스타일
docs/             AI Provider 계약, QA 지시서, 구현 결과·Gap 보고서
scripts/          Mock Golden E2E, RAG·가드레일, Local AI 확인
```

## 검증

- `npm run build` 통과
- `VITE_USE_MOCK=false npm run build` 통과
- `node scripts/smoke.mjs`: Mock 계약 및 Golden E2E 33/33
  - 상품 → 문서 → 공식 사실 확인 → 분석 1건 → 전체 결과 → 검토 승인 → Risk Pattern → GuardFit DRAFT/APPROVED
  - score 82, 상태 전이, RBAC, 멱등성, SHA-256 중복 409, 실패 재시도
- `node scripts/ai-test.mjs`: RAG·Finding 가드레일 5/5
- `node scripts/analyze-live.mjs`: Ollama opt-in 환경의 Local AI 확인용

## 협업 메모

- API v0.3.1 정합화 Phase 1~6 완료, QA 지시서 완료 기준 충족
- 항목별 상태·검증 근거: `docs/reports/v1/implementation-result.md`
- 남은 Gap과 Backend/OpenAPI 합의 항목: `docs/reports/v1/gap-analysis.md`
- 실제 Backend 확장 필드가 없으면 선택 UI는 숨기며 Mock Fixture를 실제 데이터처럼 혼합하지 않음
- `main`/`develop` 직접 push 금지. `.env`, `node_modules`, `dist`, 업로드 원문은 커밋 금지
