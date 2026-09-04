# BE Scaffolding Project 구성

- 실제 source: [`backend/`](../../../backend/)
- stack: Java 21, Spring Boot, Spring Data JPA, Flyway, PDFBox
- project configuration
  - [`build.gradle`](../../../backend/build.gradle)
  - [`application.yml`](../../../backend/src/main/resources/application.yml)
  - [migration](../../../backend/src/main/resources/db/migration/)
  - [API·workflow test](../../../backend/src/test/)
- domain: 상품·문서·공식 사실·분석·검토·Risk Pattern·GuardFit·감사·RAG provenance
- AI service source: [`ai-service/`](../../../ai-service/)
