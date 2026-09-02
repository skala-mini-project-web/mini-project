# GuardLab Frontend (MVP)

- 금융상품 설명 문서의 표현 리스크를 분석하고, 컴플라이언스 검토와 보호조치(GuardFit)까지 연결하는 워크스페이스
- 대상 사용자: 상품 담당자(PRODUCT_MANAGER), 컴플라이언스 검토자(COMPLIANCE_REVIEWER)
- 본 저장소는 프론트엔드(Vue 3 + Vite). 백엔드 없이 localhost 단독 실행 가능

## 스택

- Vue 3 + Vite, Vue Router, Pinia
- 순수 CSS 디자인 토큰(`src/styles/tokens.css`), 아이콘 Phosphor, 폰트 Pretendard + JetBrains Mono

## 실행

```bash
npm install
cp .env.example .env      # 기본값 Mock 모드
npm run dev               # http://localhost:5173
npm run build             # 프로덕션 번들
```

- 진입 화면에서 역할 선택 시 데모 세션 시작
  - 상품 담당자: `USER-PM-001`
  - 컴플라이언스 검토자: `USER-CR-001`

## Mock 모드 / 실제 백엔드 전환 (Interface First)

- FE는 `src/api/` 파사드 하나만 호출. 백엔드 유무는 `.env`로 전환
- `VITE_USE_MOCK=true`(기본): 인메모리 Mock 서버로 단독 실행
- `VITE_USE_MOCK=false`: `/api`를 Spring 백엔드로 프록시(`VITE_API_BASE`)
- Mock은 명세서(API/기능/Mock 데이터 v0.2) 계약을 그대로 구현. 백엔드 준비 시 화면 수정 없이 전환

## 프론트엔드 구현 범위

- 화면(뷰) 12종: 역할 선택, 대시보드(역할별), 상품 목록/등록, 상품 상세, 문서 워크스페이스, 분석 요청, 분석 결과, 검토함, 검토 상세, Risk Library, GuardFit, 감사 로그
- 상품 목록: 검색(초성 검색 포함), 유형 필터, 상태 필터, 숫자 페이지네이션
- 분석 결과: riskScore, 근거 추적 Finding(원문 발췌·영향 Persona·근거·권고), 검토 요청, 반려 사유 표시
- 검토 흐름: 검토 요청, 승인 시 선택 Finding만 RiskPattern 승격, 반려
- GuardFit: 검토자 관리(생성·승인·폐기), 담당자 Before/After 적용 가이드
- 알림: 추출·분석·검토 완료 시 토스트, 상품 뱃지 카운트, 상품 상세 진입 시 읽음 처리
- 상태 전주기: 로딩 스켈레톤, 빈 상태, 인라인 오류, 오류 계약(400/401/403/404/409/413/503) 처리
- 접근성: 대비 WCAG AA, 포커스 링, 모달 포커스 트랩, prefers-reduced-motion 대응

## 임시 구현물 (프론트엔드 범위 밖, 데모용)

- Mock API 서버(`src/api/mock/`): 시드 데이터, 결정론적 분석 시나리오, Clock 기반 비동기 상태 전이, RBAC, 오류 계약. 명세의 백엔드 계약을 FE에서 대체
- 클라이언트 문서 추출(`src/lib/extract.js`): 백엔드 없이 브라우저에서 실제 추출
  - PDF 텍스트: pdf.js
  - PPTX 텍스트: JSZip(슬라이드 XML 파싱)
  - 이미지/스캔 PDF: tesseract.js OCR(한국어+영어). 최초 1회 언어 데이터 다운로드 필요
  - 참고: 명세상 추출은 백엔드(PDFBox/POI), OCR은 P2. 데모 편의를 위해 클라이언트에서 선구현
- 알림 폴러(`src/stores/jobs.js`): 진행 중 작업 백그라운드 폴링, 완료 시 알림 적재
- 로컬 영속화: Mock 스토어와 알림을 localStorage에 저장하여 새로고침 후 복구
- AI 분석 설계 문서(`docs/ai-provider.md`): 역할 프롬프트, 입출력 JSON 스키마, 가드레일, RAG 계획. 실제 LLM 호출은 백엔드 담당

## 구조

```
src/
  api/            파사드(index.js), 실서버 client, mock 서버/시드/시나리오
  components/ui/  디자인 시스템 프리미티브
  components/layout/  사이드바 콘솔 셸, 페이지 헤더
  components/     PipelineGraph(진입 애니메이션)
  composables/    usePolling, useAsyncData
  lib/            format(라벨·상태·시간), hangul(초성 검색), extract(문서 추출/OCR)
  stores/         session(데모 인증), toast(오류 표시), jobs(알림/폴러)
  views/          화면 12종
  styles/         tokens.css(디자인 토큰), base.css
docs/             ai-provider.md(AI 분석 설계)
scripts/          smoke.mjs(Mock 계약 E2E 스모크)
```

## 검증

- `npm run build`: 통과
- `node scripts/smoke.mjs`: Mock 계약 E2E 31건 통과(score 82, 상태 전이, RBAC 403, 멱등성, 승격, 재시도)
- 실제 PDF/PPTX 업로드 및 이미지 PDF OCR: 브라우저 E2E 확인

## 협업 메모

- 본 브랜치는 프론트 진행분 공유용. `main`/`develop` 직접 push 금지(명세 Git 운영 기준)
- v0.2 명세서(기능/API/Mock 데이터)는 저장소 외부에서 별도 관리
- 커밋 전 변경 파일 선택 stage. `.env`, `node_modules`, `dist`, 업로드 원문은 커밋 금지
```
