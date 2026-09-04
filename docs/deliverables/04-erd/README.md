# ERD

- `ARGUS-ERD.dbml`: dbdiagram.io에 전체를 붙여넣는 편집 원본
- `erd-core.png`: 상품·문서·분석·검토·GuardFit 핵심 관계
- `erd-v3.png`: RAG provenance·감사·idempotency·demo corpus를 포함한 전체 관계
- DBML 기준: 실행 중인 PostgreSQL + pgvector schema, Flyway V1~V14
- 제외: Flyway 내부 migration history table
