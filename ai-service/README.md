# AI Service

Spring Boot 백엔드가 내부 HTTP로 호출하는 독립형 FastAPI 분석
서비스입니다. 기본 모드는 `scenarioCode`에 대응하는 고정 JSON Fixture를
반환하며, 명시적으로 설정하면 로컬 Ollama 모델을 호출합니다.

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
| `POST` | `/internal/v1/risk-analyses` | 위험 분석 (기본 Fixture, 선택적 Ollama) |
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

기본값은 기존 JSON Fixture provider입니다. 로컬 Ollama의 실제 모델로
분석하려면 Ollama에 `qwen2.5:7b-instruct`가 설치되고
`http://127.0.0.1:11434`에서 실행 중인 상태에서 다음 환경으로
서비스를 시작합니다. Ollama 모드에서는 provider 오류 시 Fixture로
대체하지 않습니다.

```bash
export AI_PROVIDER=ollama
export OLLAMA_BASE_URL=http://127.0.0.1:11434
export OLLAMA_MODEL=qwen2.5:7b-instruct
uvicorn app.main:app --reload
```

선택한 provider와 모델의 접근 가능 여부는 다음 명령으로 검증합니다.
성공 응답은 `{"status":"UP","provider":"ollama"}`입니다.

```bash
curl --fail http://127.0.0.1:8000/internal/v1/health
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
