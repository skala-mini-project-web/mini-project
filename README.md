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
