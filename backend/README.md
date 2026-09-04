# ARGUS Backend

## 실행

- 필요 도구: Docker Desktop
- 전체 실행: `docker compose up --build`
- 백그라운드 실행: `docker compose up -d --build`
- 상태 확인: `docker compose ps`
- 로그 확인: `docker compose logs -f backend`
- 데이터 초기화: `docker compose down -v`

## 확인 주소

- Backend health: http://localhost:8080/actuator/health
- Swagger: http://localhost:8080/swagger-ui/index.html
- AI health: http://localhost:8000/internal/v1/health
- PostgreSQL: `localhost:5432`

## 환경 변수

- `DB_NAME`: PostgreSQL database name, default `crosschecklab`
- `DB_USERNAME`: PostgreSQL user, default `crosschecklab`
- `DB_PASSWORD`: PostgreSQL password, default `crosschecklab`
- `DB_PORT`: host PostgreSQL port, default `5432`
- `BACKEND_PORT`: host backend port, default `8080`
- `AI_SERVICE_PORT`: host AI service port, default `8000`
- `FRONTEND_PORT`: host frontend port, default `5173`

## 도메인

- 상품·문서: 상품 등록, PDF/PPTX 업로드, 텍스트 추출·확정
- 공식 사실: 확정 문서 기반 사실 snapshot·검증
- 분석: AI 분석, 결과, 재시도, 요청 중복 방지
- 검토: 검토 요청·승인·반려, Risk Pattern 승격
- GuardFit: 보호조치 초안·승인
- 감사: append-only 상태 변경 로그·고정 페이지 조회

## 데이터베이스

- Flyway migration: `backend/src/main/resources/db/migration`
- 애플리케이션 시작 시 migration 자동 적용
- 현재 schema migration: `V1`부터 `V7`
- 감사 로그는 append-only이며 행 단위 수정·삭제를 허용하지 않음

## API 기준

- 전체 엔드포인트·요청·응답 기준: Swagger
- 데모 인증: `X-Demo-User-Id`, `X-Demo-Role`
- 분석 생성: `Idempotency-Key` 필수
- 분석 재시도: 기존 분석 상태 전이·잠금 규칙 적용

## AI 제공자

- Compose 기본 AI 서비스: fixture provider
- 실제 Ollama 검증: `AI_PROVIDER=ollama`, `OLLAMA_BASE_URL`, `OLLAMA_MODEL`로 AI 서비스 단독 실행
- Ollama mode: fixture fallback 없이 provider 오류 반환
