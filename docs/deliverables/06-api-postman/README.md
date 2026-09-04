# ARGUS Postman Mock Collection

`ARGUS-Postman-Mock-Collection.postman_collection.json`은 Postman Collection v2.1 형식의 로컬 데모용 API 컬렉션입니다. 경로와 데모 인증 헤더는 `ARGUS_MVP_API_명세서_v0.3.2.docx` 및 현재 Spring Controller 계약을 기준으로 구성했습니다. 모든 사용자, 상품, 문서, 분석 결과와 시각 값은 합성 예시입니다.

## 포함 범위

- Spring Boot health: `GET /actuator/health`
- 상품 생성 및 목록
- PDF/PPTX 문서 업로드와 추출 텍스트 확정
- 분석 생성, 상태 Polling, 결과 및 RAG retrieval trace 조회
- 검토 요청, 상세 조회, 승인 결정
- Risk Pattern 목록 및 수정
- GuardFit 생성, 목록 및 승인
- 예상 경계 응답: 미확정 문서 `409 DOCUMENT_NOT_CONFIRMED`, 소유권 위반 `403 FORBIDDEN_OWNERSHIP`

컬렉션의 기본 변수는 다음과 같습니다.

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8080` | 실제 로컬 Spring API 또는 Postman Mock URL |
| `pmUserId` / `pmRole` | `1` / `PRODUCT_MANAGER` | 상품 담당자 데모 헤더 |
| `reviewerUserId` / `reviewerRole` | `2` / `COMPLIANCE_REVIEWER` | 검토자 데모 헤더 |
| `demoScenario` | `GUARANTEE_MISUNDERSTANDING_HIGH` | 합성 데모 시나리오 선택 헤더 |

이 컬렉션에는 토큰, 비밀번호 또는 운영 자격 증명이 없습니다. `X-Demo-User-Id`와 `X-Demo-Role`은 로컬 MVP 전용이며 운영 인증 수단이 아닙니다.

## Postman 가져오기

1. Postman에서 **Import**를 선택합니다.
2. `ARGUS-Postman-Mock-Collection.postman_collection.json`을 가져옵니다.
3. 컬렉션의 **Variables**에서 PM 및 검토자 ID가 로컬 합성 seed 데이터와 맞는지 확인합니다.
4. 실제 로컬 API를 호출할 때는 `baseUrl`을 `http://localhost:8080`으로 유지합니다.

문서 업로드 요청의 `file` form-data 항목에는 사용자가 합성 PDF 또는 PPTX를 직접 선택해야 합니다. 최대 크기는 10 MB입니다. 스캔 PDF OCR은 지원하지 않습니다.

## Postman Mock Server 사용

1. Postman에서 **New > Mock Server**를 선택합니다.
2. **Select an existing collection**에서 `ARGUS Postman Mock Collection`을 선택합니다.
3. Mock Server를 생성하고 발급된 URL을 복사합니다. 공개 Mock이 아니라면 Postman이 안내하는 Mock Server 인증 설정을 사용하되, 자격 증명을 컬렉션 파일에 저장하지 않습니다.
4. 컬렉션 변수 `baseUrl`의 Current value를 Mock Server URL로 바꿉니다. URL 끝에는 `/`를 붙이지 않습니다.
5. 원하는 요청을 전송합니다. Mock Server는 method와 path에 맞는 저장된 example response를 반환합니다.

한 요청에 여러 예제가 있는 경우 `x-mock-response-name` 요청 헤더로 정확한 예제를 선택할 수 있습니다. 예:

| 요청 | `x-mock-response-name` 값 |
| --- | --- |
| 분석 생성 성공 | `202 PM analysis accepted` |
| 미확정 문서 경계 | `409 Document not confirmed` |
| 분석 진행 상태 | `200 Analysis running` |
| 분석 완료 상태 | `200 Analysis completed` |
| RAG trace 포함 결과 | `200 PM analysis result with RAG trace` |
| 접근 거부 | `403 Analysis access denied` |
| 검토 승인 | `200 Reviewer approval` |

Mock Server에서 multipart 요청을 보낼 때 파일 내용은 example 선택에 사용되지 않습니다. 저장된 업로드 응답은 실제 파일 검증, 추출 또는 저장이 수행됐다는 증거가 아닙니다.

## Mock 예제와 실제 Docker API 검증의 차이

Postman Mock 응답은 컬렉션에 저장된 정적 합성 JSON입니다. 다음을 실행하거나 입증하지 않습니다.

- Spring Boot 비즈니스 로직 및 RBAC
- PostgreSQL/pgvector 저장과 검색
- FastAPI 호출
- Ollama 임베딩 또는 생성 모델
- 비동기 상태 전이
- PDF/PPTX 파일 검증 및 텍스트 처리

실제 Docker API를 확인하려면 저장소 루트에서 서비스를 기동한 뒤 `baseUrl=http://localhost:8080`으로 되돌리고 요청을 순서대로 실행합니다. 최소 도달성 확인 경로는 다음과 같습니다.

```bash
docker compose up --build
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8000/internal/v1/health
```

Health 응답은 서비스 도달성만 확인합니다. 실제 검증에는 합성 PDF/PPTX 업로드, 문서 `READY` Polling, 텍스트 확정, 분석 생성, 분석 `COMPLETED` Polling, 결과의 `retrievalTrace` 및 인용 확인, 검토 승인까지의 전체 흐름이 필요합니다. Mock URL에서 받은 응답을 실제 Docker API 검증 결과로 기록하면 안 됩니다.

ARGUS는 로컬 Vue + Spring Boot + FastAPI + PostgreSQL/pgvector + Ollama 시스템이며 MSA 또는 운영 금융·법률 자문 서비스가 아닙니다.
