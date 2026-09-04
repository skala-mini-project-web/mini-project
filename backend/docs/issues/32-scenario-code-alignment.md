# [BE] X-Demo-Scenario 시나리오 코드를 데이터 명세 §6으로 통일

- **Issue**: #32
- **Branch**: `feature/32-scenario-code-alignment`
- **범위**: Backend (추출 픽스처 + 테스트). ai-service·FE 변경 없음
- **관련 명세**: 데이터 명세 v0.3 §6 「Mock 시나리오」 / API 명세 v0.3 §1.1

---

## 배경

`X-Demo-Scenario` 는 **문서 업로드(추출)와 분석 요청(ai-service)이 공유하는 헤더**다.
그런데 두 도메인이 서로 다른 코드 집합을 들고 있었다.

| 데이터 명세 §6 | 추출 (백엔드 픽스처) | 분석 (ai-service) |
|---|---|---|
| `GUARANTEE_MISUNDERSTANDING_HIGH` | ✅ | ✅ |
| `EARLY_TERMINATION_COST_MEDIUM` | ❌ `PRINCIPAL_LOSS_MEDIUM` | ✅ |
| `ACCESSIBILITY_LOW` | ❌ `CLEAN_LOW` | ✅ |
| `EXTRACT_TIMEOUT_THEN_SUCCESS` | ✅ | (추출 전용) |
| `PROVIDER_RATE_LIMITED_THEN_SUCCESS` | (분석 전용) | ✅ |
| `PROVIDER_RESPONSE_INVALID` | (분석 전용) | ✅ |

정상 시나리오 3종 중 2종의 이름이 어긋나, 겹치는 값은 `GUARANTEE_MISUNDERSTANDING_HIGH` 하나뿐이었다.

## 재현

1. 업로드 시 `X-Demo-Scenario: PRINCIPAL_LOSS_MEDIUM` → 추출 성공
2. 같은 값으로 `POST /api/analyses` 호출
3. ai-service 가 `404 SCENARIO_NOT_FOUND` 반환
4. 백엔드가 `PROVIDER_RESPONSE_INVALID` + **`retryable=false`** 로 확정 실패 → 재시도 CTA 없음

반대 방향도 마찬가지다. 업로드에 `ACCESSIBILITY_LOW` 를 보내면
`ExtractionScenarioResolver` 가 `400 VALIDATION_ERROR` 로 거절한다.

## 영향

- 데모 진행자가 헤더 하나로 시나리오를 바꾸는 흐름이 정상 시나리오 3종 중 1종에서만 동작
- `PRINCIPAL_LOSS_MEDIUM` · `CLEAN_LOW` 는 데이터 명세 §6에 없는 코드라 FE·Data 문서와도 어긋남

## 해결 방법

검토했던 세 안 중 **(a)** 를 택했다.

| 안 | 내용 | 판단 |
|---|---|---|
| **(a)** | 추출 픽스처 코드를 명세 §6에 맞춰 rename | **채택** — 백엔드 단독, API·DB 시맨틱 변화 없음 |
| (b) | 헤더를 추출용·분석용으로 분리 | FE 계약이 늘어남 |
| (c) | 데이터 명세 §6을 구현에 맞춰 개정 | ai-service·FE까지 연쇄 수정 |

## 변경 내용

### 1. `src/main/resources/fixtures/document-extraction-scenarios.json`

- `PRINCIPAL_LOSS_MEDIUM` → **`EARLY_TERMINATION_COST_MEDIUM`**
  `extractedText` 를 「중도해지 비용·수수료가 뒤로 밀리고 분산된 적금 설명서」로 교체.
  ai-service `termination_cost.json` 의 Finding(중도해지 비용 오인, `COST_OMISSION`)과 의미를 맞췄다.
- `CLEAN_LOW` → **`ACCESSIBILITY_LOW`**
  `extractedText` 를 「긴 문장과 전문용어가 집중된 DLB 설명서」로 교체.
  ai-service `accessibility.json` 의 Finding(인지 접근성, `COGNITIVE_ACCESSIBILITY`)과 의미를 맞췄다.
- 두 시나리오의 `fileNamePatterns` 도 새 의미에 맞게 교체하고 다른 시나리오와 겹치지 않게 정리

> 단순 rename이 아니라 본문까지 바꾼 이유: 코드 이름만 갈아끼우면
> `ACCESSIBILITY_LOW`(인지 접근성) 시나리오가 「위험 고지가 충실한 정기예금」 텍스트를 돌려주어
> 분석 결과와 원문이 서로 다른 얘기를 하게 된다.

### 2. `src/test/java/.../DocumentApiTest.java`

옛 코드 참조 3곳(`CLEAN_LOW`)과, 파일명 패턴 매칭 테스트의 기대 파일명·본문을 갱신.

### 3. `src/test/java/.../ExtractionScenarioVocabularyTest.java` (신규)

같은 드리프트가 다시 생기지 않도록 고정하는 회귀 테스트 4건.

- 추출 코드 집합이 데이터 명세 §6 표와 정확히 일치
- 분석과 공유하는 정상 시나리오 3종이 추출 쪽에도 존재
- 기본 시나리오(`GUARANTEE_MISUNDERSTANDING_HIGH`)가 픽스처에 존재
- 시나리오 간 `fileNamePatterns` 중복 없음

## 검증

- `./gradlew test` — **205건 전부 통과** (기존 201 + 신규 4)
- 추출·분석·오류 코드의 합집합이 명세 §6의 6종과 일치. **명세 밖 코드 0건 / 미구현 0건**

## 리뷰 포인트

- 새 `extractedText` 두 건이 각 Red Team 규칙(`COST_OMISSION`, `COGNITIVE_ACCESSIBILITY`)을 자연스럽게 유발하는지
- `fileNamePatterns` 가 데모 파일명과 잘 맞는지 (`중도해지`·`해지`·`termination`·`적금` / `접근성`·`accessibility`·`약관`·`dlb`)

## 남은 일 (이 이슈 범위 밖)

- `EXTRACT_TIMEOUT_THEN_SUCCESS` 는 추출 전용, `PROVIDER_*` 2종은 분석 전용이다.
  명세 §6의 구조가 그러하므로 의도된 분리지만, 데모 대본에는 어느 단계에서 어떤 코드를 쓰는지 명시하는 편이 좋다.
- 분석 재시도 시 `scenarioCode` 가 저장되지 않아 FE가 헤더를 다시 보내지 않으면 기본 시나리오로 바뀐다
  (검토 보고서 #8).
