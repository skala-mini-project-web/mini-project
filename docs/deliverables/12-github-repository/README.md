# GitHub Repository 세팅

- repository: https://github.com/skala-mini-project-web/mini-project
- integration branch: `develop`
- main project guide: [`README.md`](../../../README.md)
- local runtime: [`docker-compose.yml`](../../../docker-compose.yml)
- configuration safety
  - 실제 `.env`와 인증서 파일은 `.gitignore` 처리
  - `.env.example`만 합성 기본값·설정 안내용으로 추적
- collaboration evidence
  - feature branch → test → PR → merge 흐름
  - RAG·workflow QA remediation은 PR #70으로 병합
  - README·submission docs도 별도 PR로 병합
