# AI Service

Spring Boot 백엔드가 내부 HTTP로 호출하는 독립형 FastAPI Mock 분석
서비스입니다. 현재 MVP에서는 실제 LLM이나 RAG를 호출하지 않고,
`scenarioCode`에 대응하는 고정 JSON Fixture를 반환합니다.

## 책임 범위

- 금융상품 설명에 대한 Mock 위험 분석 결과 반환
- Persona, Red Team, Blue Team, Evaluator 결과를 하나의 응답으로 제공
- 요청·응답 JSON 계약 검증
- 재시도 가능 여부가 포함된 오류 Fixture 제공
- 상태 확인 API 제공

파일 업로드·텍스트 추출·인증·업무 데이터 저장·분석 Job 상태 관리는
Spring Boot 백엔드의 책임이며 이 서비스는 DB에 직접 접근하지 않습니다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/internal/v1/health` | 서비스 상태 확인 |
| `POST` | `/internal/v1/risk-analyses` | Fixture 기반 위험 분석 |
| `GET` | `/docs` | Swagger UI |

지원하는 `scenarioCode`는 다음과 같습니다.

- `GUARANTEE_MISUNDERSTANDING_HIGH`
- `EARLY_TERMINATION_COST_MEDIUM`
- `ACCESSIBILITY_LOW`
- `PROVIDER_RATE_LIMITED_THEN_SUCCESS` (같은 `analysisId`의 첫 호출은 503, 다음 호출부터 성공)
- `PROVIDER_RESPONSE_INVALID` (재시도 불가능한 500 오류)

`redTeamPackCode`는 DB 기준 데이터와 동일한 `CORE_FINANCIAL_RISK_V1`만
허용합니다. 정상 분석 시나리오는 관련 `ruleCodes`가 선택되어 있어야 하며,
조합이 맞지 않으면 Fixture를 읽기 전에 422를 반환합니다.

## 로컬 실행

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

## 테스트

```bash
pip install -r requirements-dev.txt
pytest
```

## Docker 실행

```bash
docker build -t crosschecklab-ai-service .
docker run --rm -p 8000:8000 crosschecklab-ai-service
```
