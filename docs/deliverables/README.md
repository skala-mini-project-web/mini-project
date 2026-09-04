# ARGUS 제출 산출물

- 기준 저장소: https://github.com/skala-mini-project-web/mini-project
- 기준 브랜치: `develop`
- 데이터 고지: 모든 데모 corpus·PDF·정책·수치는 합성 데이터
- 범위 고지: 로컬 데모 시스템, 실제 금융·법률 자문 서비스 아님, 이미지형 스캔 PDF OCR 미지원

## 제출 항목

- `01-final-presentation/`: 최종 발표 슬라이드와 슬라이드 구성 안내
- `02-use-case/`: PM·reviewer 업무 흐름과 기능 명세서
- `03-wireframe/`: 실제 frontend 구현 화면 기반 PNG 와이어프레임
- `04-erd/`: dbdiagram 붙여넣기용 DBML, 핵심·전체 ERD PNG
- `05-database/`: 데이터 명세서와 실행 중 PostgreSQL schema export
- `06-api-postman/`: API 명세서, Postman Mock Collection, 사용 안내
- `07-ai-ready/`: AI prompt·JSON schema·RAG logic 문서와 다이어그램
- `08-fe-scaffolding/`: Vue 3 frontend scaffold와 구현 명세 안내
- `09-be-scaffolding/`: Spring Boot backend scaffold와 실행 구조 안내
- `10-ui-implementation/`: 메인·핵심 UI 구현 증빙
- `11-e2e-integration/`: 실제 Docker RAG E2E와 회귀 테스트 결과
- `12-github-repository/`: GitHub repository·README·PR 기반 협업 증빙

## 검토 순서

1. `01-final-presentation/`으로 전체 문제·해결 흐름 확인
2. `02-use-case/`부터 `07-ai-ready/`까지 설계 산출물 확인
3. `08-fe-scaffolding/`부터 `10-ui-implementation/`까지 구현 산출물 확인
4. `11-e2e-integration/`과 `12-github-repository/`로 실행·통합·형상관리 증빙 확인
