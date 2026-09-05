# ARGUS 문서 위험 우선순위 정책 v1

- 상태: 구현 전 정책·TEVV specification
- 적용 대상: 합성 금융상품 판매 문서의 설명·고지 위험
- 적용 제외: 실제 금융·법률 자문, 실제 고객 적합성 판단, 개인·집단 위험 점수화
- 변경 규칙: 새 정책 version을 만들며 기존 분석·fixture·score run을 재계산하지 않음

## 목적

- PM과 reviewer가 remediation 순서를 설명 가능한 기준으로 정하도록 지원
- LLM이 반환한 raw score·confidence를 최종 점수로 사용하지 않음
- 점수는 정책 규칙, 불변 문서·근거 anchor, reviewer 수용 Finding으로만 계산

## 상태

- `PENDING_REVIEW`: reviewer 판단 전
- `NEEDS_EVIDENCE`: source·policy anchor 또는 적용성 부족
- `INCOMPLETE_COVERAGE`: 필요한 상황 lens 검증이 완료되지 않음
- `PROVISIONAL`: 구조·근거 검증은 통과했으나 reviewer 수용 전
- `APPROVED`: reviewer 수용 Finding과 필수 coverage가 확정됨
- `NOT_SCORED`: scope 밖 또는 필요한 정책 정보가 없어 점수를 만들 수 없음

- `0`은 `APPROVED`와 complete coverage를 동시에 만족할 때만 사용
- 어느 상태도 법적 적합성·투자 적합성·위험 없음 결론을 의미하지 않음

## 점수 단위

- 단위: 고정 문서 revision의 중복 제거된 harm event
- 중복 키: `sourceSnapshotId + policyRuleId + normalizedDocumentSpanAnchor`
- 동일 문장을 여러 Red Team rule·모델 문장이 반복해도 하나의 harm event로 처리
- Persona 수·선택 순서·model confidence·retrieval similarity·Finding 개수는 점수 입력이 아님

## 평가 차원

- `CLAIM_SUBSTANTIATION`: 보장·수익·비교·사실 주장의 근거·정정 여부
- `KEY_INFORMATION_COMPLETENESS`: 손실·비용·유동성·기간·자격·변동성 정보의 완전성
- `BALANCE_AND_PROMINENCE`: 이익 표현과 위험·제한 정보의 시점·근접성·강조도 균형
- `COMPREHENSIBILITY_AND_ACCESS`: 용어·구조·이해 확인·접근성
- `DECISION_AND_CONSENT_INTEGRITY`: checkbox·default·압박·동의 흐름이 informed decision을 대체하는지

## M/L anchor

### Magnitude M

- 1: 경미한 명확성 문제
- 2: 이해·접근을 방해하지만 단독으로 핵심 선택을 바꾸지 않는 문제
- 3: 중요한 제한·권리·위험이 불명확하거나 덜 강조된 문제
- 4: 수수료·해지·상환·금리변동·보장한도·투자위험 정보의 오기·누락
- 5: 확정 사실 직접 모순 또는 원금손실 등 심각한 손해를 가릴 수 있는 정책 정의 critical 문제

### Likelihood L

- 1: 간접적·약한 정책 위반 조건
- 2: 간접적 암시 조건
- 3: 특정 document span의 모호성·강조도·접근성 조건 확인
- 4: 핵심 주장이 무자격으로 직접 제시되거나 필수 정정문 누락
- 5: 확정 사실 직접 모순 또는 정책 정의 필수 고지 누락

## 집계

```text
pᵢ = (Mᵢ × Lᵢ) / 25
I = ROUND_HALF_UP(100 × (1 - Π(1 - pᵢ)))
```

- `BigDecimal`으로 중간 반올림 없이 계산
- 마지막에만 `ROUND_HALF_UP`
- 결과 범위는 0~100
- hard-stop은 숫자와 분리된 `requiresHumanEscalation` flag
- GuardFit 제안은 지수 감소 근거가 아님
- 실제 변경 문서의 새 revision을 같은 정책·coverage 기준으로 재분석한 경우에만 before/after 비교

## 근거 적격성

각 scored Finding에는 아래가 필요하다.

- `DOCUMENT_CLAIM` source role의 document revision·page·UTF-8 offset·excerpt hash
- `POLICY_REQUIREMENT` source role의 evidence version·chunk hash·UTF-8 offset·excerpt hash
- policy rule ID·policy version·M/L rationale
- reviewer 결정·override reason·actor·trace ID

- retrieval rank·similarity는 provenance이며 M/L 계수가 아님
- missing·contradictory·out-of-scope evidence는 낮은 점수가 아니라 `NEEDS_EVIDENCE` 또는 `NOT_SCORED`

## 상황 기반 Persona lens

- `LIMITED_PRODUCT_FAMILIARITY`
- `LOSS_RECOVERY_PRESSURE`
- `NEAR_TERM_LIQUIDITY_NEED`
- `VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT`
- `EXPLANATION_ACCESS_SUPPORT`
- `DIGITAL_CHANNEL_SUPPORT`
- `LIFE_EVENT_FINANCIAL_STRESS`

- 각 lens는 synthetic scenario test이며 실제 고객 속성·연령·직업·장애·소득을 추론하지 않음
- scoring analysis는 policy rule과 상품 조건으로 필요한 lens를 모두 실행
- exploratory UI는 개수 제한 없이 선택 가능하나 score eligibility와 분리
- lens는 점수를 더하지 않으며 coverage status와 scenario observation만 바꿈

## TEVV release gate

- identical input·scoreRunKey 재실행: 100% 동일 결과
- duplicate/paraphrase Finding: 지수 불변
- M 또는 L 상승: 지수 하락 금지
- 잘못된 source/chunk/hash/offset/evidence: 100% score·review 진입 차단
- Persona lens 순서·subset 변경: 동일 accepted Finding의 global score 불변
- `NOT_SCORED`·`NEEDS_EVIDENCE`·`PENDING_REVIEW`을 `위험 없음`으로 표시 금지
- model/prompt/dataset version별 reliability는 TEVV 보고서로 별도 표시하며 global score에 곱하지 않음

## 근거

- NIST AI RMF MEASURE: https://airc.nist.gov/airmf-resources/airmf/5-sec-core/
- NIST AI RMF Playbook MEASURE: https://airc.nist.gov/airmf-resources/playbook/measure/
- 금융위원회 온라인 금융상품 설명방안: https://www.fsc.go.kr/po010105/78276
- OECD 금융소비자 취약성: https://www.oecd.org/content/dam/oecd/en/publications/reports/2025/04/understanding-and-responding-to-financial-consumer-vulnerability_838bba5d/111daec8-en.pdf
