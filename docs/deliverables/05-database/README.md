# Database 구성

- DBMS: PostgreSQL 16 + pgvector
- migration: Flyway V1~V14
- vector index: `vector(1024)`, HNSW cosine index
- `ARGUS_MVP_데이터_명세서_v0.3.docx`: 데이터 명세서
- `ARGUS-current-schema.sql`: 실행 중인 DB에서 추출한 schema-only DDL
- 핵심 보장
  - 분석 terminal state와 terminal audit event를 같은 transaction으로 저장
  - RAG retrieval snapshot은 완료 analysis 기준 변경 불가
  - embedding model digest가 달라지면 기존 vector generation과 섞어 검색하지 않음
  - zero-norm vector 저장·검색 거부
