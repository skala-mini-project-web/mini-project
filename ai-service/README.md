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

Fixture provider의 성공 fixture는 다음 고정 source passage만 명시적으로
지원하며, 각 JSON의 `docClaim.excerpt`에 그대로 저장합니다.

- `GUARANTEE_MISUNDERSTANDING_HIGH` 및 재시도 성공:
  `최근 안정적인 수익률을 기록한 투자상품입니다.`
- `EARLY_TERMINATION_COST_MEDIUM`:
  `중도해지 시 수수료가 발생할 수 있습니다.`
- `ACCESSIBILITY_LOW`: `이 상품은 스텝다운 ELS 구조입니다.`

요청의 `confirmedText`에 해당 passage가 정확히 한 번 존재해야 합니다. Fixture는
요청 첫 줄, 임의의 다른 source passage 또는 첫 `knownFact`를 claim으로
대체하지 않으므로, 기존의 임의 원문과 호환되는 일반 분석 provider가 아닙니다.

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

- `OLLAMA_NUM_CTX`(기본 32768): 모델 context 크기. Ollama 기본 4096은 confirmedText 상한 20,000자와 retrieved context를 담지 못하고 초과분을 앞에서 조용히 잘라 system prompt를 잃는다. 짧은 데모 문서만 쓰는 로컬에서는 8192로 낮춰 KV cache 메모리를 줄일 수 있다. 생성 출력에는 `num_predict=4096`을 명시해 headroom을 둡니다.
- `OLLAMA_KEEP_ALIVE`(기본 미설정 = Ollama 서버 기본 5m): 응답 후 모델을 메모리에 유지하는 시간. 다른 작업과 메모리를 나눠 쓰려면 `1m` 또는 `0`으로 지정한다. 다음 분석 시 모델 재로드 시간이 늘어난다.

Ollama에는 검색 chunk의 문장, 글머리표, 표 행을 원문 그대로 최대 400자씩
잘라 opaque option ID와 함께 전달합니다. 합성 데이터 고지와 문서 메타데이터는
근거 option에서 제외하며 전체 option 원문은 UTF-8 12,000 bytes를 넘지 않습니다.
사용 가능한 원문 근거가 하나도 없으면 provider를 호출하지 않고
`AI_PROVIDER_REQUEST_REJECTED`(422)를 반환합니다.
최초 요청과 bounded repair 요청은 각각 format/schema를 포함해 직렬화한
payload가 UTF-8 100,000 bytes 이하인지 HTTP 호출 전에 검사하며, 초과하면 같은
422를 반환합니다. 이 byte 제한과 `num_predict` 설정은 정확한 tokenizer 기반
token 계산을 의미하지 않습니다.

분석 응답의 `findings`는 선택된 persona·rule·검색 chunk 범위에서 원문 근거로
지원되는 지적 0~20개입니다. 빈 배열은 이 선택 범위에서 지원되는 지적이 없다는
뜻일 뿐 상품의 안전성이나 법률·준법 적합성을 보장하지 않습니다. 이때
`riskScore`는 생략하거나 `null`이어야 하며 숫자 `0`을 사용하지 않습니다.
지적이 있더라도 진단용 `riskScore`는 `null`일 수 있습니다. 숫자가 있으면
0~100 범위의 분석용 위험 강도 요약이며 위반 확률, 법적 판정, 상품 등급을
뜻하지 않습니다. 실제 서비스의 내부 Ollama contract
`ollama-rag-grounded-v18`은 인용 역할을 구분합니다.
`evidenceSpanOptionIds`는 검색된 규범·원천 정책 원문을 가리키는
`POLICY_REQUIREMENT` 인용 전용이며, 상품 문구나 known fact를 넣지 않습니다.
서버가 신뢰된 option table을 사용해 공개 응답의 `retrievedContextChunkIds`와
정확한 원문 `evidenceSpans`를 결정적으로 생성합니다. 모델은 chunk ID나 span을
작성하지 않습니다. 한 지적 안에서 같은 option을 중복할 수 없지만 서로 다른
지적은 같은 option을 공유할 수 있습니다.
각 실제 지적에는 내부 응답의 공백이 아닌 단일 `docClaimOptionId`가 필수입니다.
이는 정책 option과 별도로 `confirmedText` 전체를 원문 그대로 400 Unicode
codepoint 이하, stride 320(80자 overlap)으로 나눈 최대 63개의
`allowedDocClaimOptions` 중 하나를 선택합니다. 메타데이터나 공백을 제거하거나
정규화하지 않고 20,000자 끝까지 모든 원문을 포함합니다. 서버는 선택한 ID를
정확한 excerpt로 매핑해 공개 응답의 필수 non-null
`docClaim: {"excerpt": "..."}`을 생성합니다. excerpt는 공백이 아니고 최대
400 codepoint이며, 변경하지 않은 `confirmedText` 안에서 overlapping occurrence
기준 정확히 한 번 나타나야 합니다. 동일 문구가 여러 번 나오는 경우 option ID로
특정 occurrence를 가장하지 않고 응답을 무효화해 bounded repair 대상으로
처리합니다. 알 수 없는 ID, 정책/문서 역할이 뒤바뀐 ID, 누락된 ID도 첫 option으로
대체하지 않고 무효입니다. 모델은 excerpt, hash, offset, revision ID를 직접
작성하지 않습니다. 누락 지적은 영향받는 기존 passage에 국소적으로 연결해야
하며, 그 anchor를 문서 전체에 내용이 없다는 증명으로 사용하지 않습니다.
서로 다른 선택 rule의 지적은 별도 위험이며, 같은 rule과 같은 원문 claim의
중복은 downstream 집계에서 한 번만 계산합니다.
`knownFactIds`는 선택적인 관련 provenance일 뿐 document claim anchor가
아닙니다. `knownFacts`가 전달되어도 실제 관련 fact가 없으면 빈 배열일 수 있으며,
첫 fact를 임의로 연결하거나 ID를 만들지 않습니다. Fixture provider도 각 fixture가
명시한 `docClaim`을 그대로 검증하며 요청이나 첫 fact에서 합성하지 않습니다.
모델·프롬프트 버전은 DB 컬럼 계약에 맞춰 공백이 아닌 최대 50자입니다.

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
