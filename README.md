# mini-project
SKALA 4기 미니 프로젝트

## 전체 서비스 Docker 실행

Docker Desktop을 실행한 뒤 저장소 루트에서 다음 명령을 실행합니다.

```bash
docker compose up --build
```

기본 접속 주소:

- Frontend: http://localhost:5173
- Backend Swagger: http://localhost:8080/swagger-ui/index.html
- AI Mock Swagger: http://localhost:8000/docs
- PostgreSQL: `localhost:5432`

종료할 때는 다음 명령을 사용합니다.

```bash
docker compose down
```

DB 데이터까지 초기화하려면 `docker compose down -v`를 사용합니다.
포트나 로컬 DB 계정을 변경할 때만 `.env.example`을 `.env`로 복사해 수정합니다.

## 로컬 확인

- Frontend: http://localhost:5173
- Backend health: http://localhost:8080/actuator/health
- AI health: http://localhost:8000/internal/v1/health
- Swagger: http://localhost:8080/swagger-ui/index.html

## 작업 상태

- 완료: Compose 기반 PostgreSQL·Spring·Frontend 실행 환경
- 완료: 감사 로그 조회·상태 변경 기록
- 완료: 실제 Ollama 분석 제공자 연결
- 완료: 확정 문서 기반 공식 사실 검증
- 진행: Frontend 실서버 API 어댑터·브라우저 E2E
