# ERD

- `ARGUS-ERD.dbml`: dbdiagram.io에 전체를 붙여넣는 편집 원본. `python3 tools/generate_erd_dbml.py`가 `05-database/ARGUS-current-schema.sql`에서 생성하며 `--check`로 동기화 여부를 확인
- `erd-core.png`: 문서/OCR provenance·durable batch·분석 execution/anchor·검토/score/action 핵심 관계
- `erd-v3.png`: RAG provenance·감사·idempotency·demo corpus·batch·score ledger를 포함한 전체 관계
- DBML 기준: 실행 중인 PostgreSQL + pgvector schema, Flyway V1~V24
- 제외: Flyway 내부 migration history table
