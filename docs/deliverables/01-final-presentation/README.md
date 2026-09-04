# ARGUS 최종발표 자료

## 파일

- `ARGUS-최종발표-슬라이드.pptx`: 16:9 비율, 14장 구성의 한국어 최종발표 자료
- `ARGUS-최종발표-슬라이드.pdf`: 제출·열람용 PDF export

## 발표 순서와 요구 산출물 매핑

| 슬라이드 | 섹션 | 핵심 내용 | 연결된 제출 근거 |
|---:|---|---|---|
| 1 | 표지 | ARGUS, 금융상품 판매 리스크 사전검증 AI 플랫폼 | 프로젝트 정체성과 기술 구성 |
| 2 | 문제와 범위 | 표현·근거·조치의 단절, 로컬 MVP 경계 | 루트 `README.md`의 해결 문제·범위 고지 |
| 3 | 핵심 업무 흐름 | PM 분석 → reviewer 승인/반려 → Risk Pattern → GuardFit | `../02-use-case/ARGUS_MVP_기능_명세서_v0.3.1.docx` |
| 4 | 시스템 아키텍처 | Vue 3, Spring Boot, PostgreSQL/pgvector, FastAPI, Ollama, Docker Compose | `../../assets/system-architecture.png` |
| 5 | PM 구현 화면 | 역할 선택, 대시보드, 상품·문서, 분석 조건 | `../03-wireframe/01-역할선택.png` ~ `04-분석조건.png` |
| 6 | RAG 결과 화면 | 검색 정보, 근거 문서, Finding, exact evidence span, 공식 사실 | `../03-wireframe/05-분석결과-RAG.png` |
| 7 | RAG provenance | 확정 입력, cosine top-6 검색, 구조화 생성, backend 검증·저장 | `../07-ai-ready/ai-logic-flow.png`, AI 참고 문서 |
| 8 | 사람의 승인 | reviewer 검토와 승인된 GuardFit의 PM 적용 가이드 | `../03-wireframe/06-reviewer-검토상세.png`, `07-GuardFit-가이드.png` |
| 9 | 데이터 모델 | 상품·문서·분석·검색 근거·검토·조치·감사 연결 | `../04-erd/erd-core.png`, `ARGUS-ERD.dbml`, `../05-database/ARGUS-current-schema.sql` |
| 10 | 신뢰성 통제 | 멱등성, 실행 토큰, terminal 원자성, stale 복구, 모델 identity, 권한·모드 분리 | backend·AI service 구현 및 루트 `README.md` |
| 11 | 검증 근거 | UI·ERD·SQL·명세 증거와 Backend/AI/Frontend/실제 RAG E2E 재현 경로 | 루트 `README.md`의 검증 명령, `frontend/scripts/real-rag-full-flow-e2e.mjs` |
| 12 | 데이터·서비스 한계 | 합성 데이터, OCR 비지원, 금융·법률 자문 및 운영 서비스 아님 | 루트 `README.md`의 범위와 데이터 고지 |
| 13 | 팀과 저장소 | 팀 역할, 개발 타임라인, GitHub URL | `../../assets/project-timeline.png`, 프로젝트 팀 구성 |
| 14 | 결론·Q&A | 근거 검색 → 사람의 결정 → 승인된 조치 연결 | 전체 제출 산출물 요약 |

## 발표 시 반드시 유지할 사실

- 모든 데모 corpus와 PDF의 상품명·정책·규정·수치는 합성 데이터입니다.
- 이미지형 스캔 PDF OCR은 지원하지 않으며, PDF 추출은 PDFBox text layer 기준입니다.
- ARGUS는 로컬 Vue + Spring Boot + FastAPI + PostgreSQL/pgvector + Ollama 시스템입니다.
- 단일 업무 backend와 독립 AI service로 구성되며 MSA가 아닙니다.
- 실제 금융·법률 자문이나 production 서비스가 아닙니다.
- AI는 후보와 근거를 제시하고 reviewer가 승인 또는 반려합니다.
- 검증 슬라이드는 저장소의 증거와 재현 명령을 안내하며, 측정하지 않은 성능 수치를 주장하지 않습니다.

## 저장소

<https://github.com/skala-mini-project-web/mini-project>
