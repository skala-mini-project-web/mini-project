# ARGUS 위험 우선순위·대량 처리·한글 OCR 구현 계획

- 상태: 설계 확정, 제품 코드 미구현
- 기준 branch: `origin/develop`
- 개발 규칙: `feature/*` branch → 로컬 검증 → PR → `develop` → release PR → `main`
- 범위: 합성 판매 문서의 설명·고지 위험 우선순위화
- 제외: 실제 금융·법률 자문, 실제 적합성 판단, 소비자 개인 위험·신용 점수화

## 근거와 설계 원칙

- NIST AI RMF는 risk를 영향 규모와 발생 가능성의 맥락적 조합으로 보고, 선택한 metric·TEVV·한계·설명 자료를 문서화할 것을 요구한다.
- 금융위원회 설명방안은 핵심 사항·불이익·위험·비용·중도해지·이해 확인을 명확히 전달할 것을 강조한다.
- OECD 금융소비자 취약성 지침은 취약성을 고정 인구 집단이 아닌 개인 상황·시장 설계·접근성의 동적 조합으로 본다.
- 따라서 ARGUS는 LLM의 self-reported score, Persona 수, 고객의 연령·직업·성별·건강·소득을 점수 입력으로 사용하지 않는다.

## 결정 1. 문서 위험 우선순위 지수

### 목적

- 이름: `ARGUS 문서 위험 우선순위 지수`
- 범위: 합성 판매 문서의 설명·고지 위험 remediation 우선순위
- 사용자: PM과 reviewer
- 금지된 용도: 개인별 적합성 판단, 투자 권유, 법률 적합성 판정, 자동 승인·차단

### 점수 대상과 상태

- 점수 대상: 고정된 문서 revision에서 reviewer가 수용한 정책 적용 Finding
- 후보 Finding: `PROVISIONAL`
- 근거·정책 적용성 부족: `NEEDS_EVIDENCE`
- 필수 상황 lens 미검증: `INCOMPLETE_COVERAGE`
- reviewer 판단 전: `PENDING_REVIEW`
- 모든 필수 범위가 닫힌 뒤에만 0~100 지수 표시
- `0`은 위 모든 조건이 충족된 상태에서만 사용하며, 그 외 상태를 `위험 없음`으로 표현하지 않음

### 결정론 scorecard

각 중복 제거 Finding은 정책 version이 부여한 두 값을 가진다.

- `Magnitude M` 1~5
  - 1: 경미한 명확성 문제
  - 2: 이해·접근을 방해하지만 단독으로 핵심 선택을 바꾸지 않는 문제
  - 3: 중요한 제한·권리·위험이 불명확하거나 덜 강조된 문제
  - 4: 수수료·해지·상환·금리변동·보장한도·투자위험 같은 핵심 정보의 오기·누락
  - 5: 확정 사실과 직접 모순되거나 원금손실 등 심각한 손해를 가릴 수 있는 정책 정의 critical 문제
- `Likelihood L` 1~5
  - 1: 간접적·약한 정책 위반 조건
  - 2: 간접적 암시 조건
  - 3: 특정 문서 span에서 모호성·강조도·접근성 조건 확인
  - 4: 핵심 주장이 무자격으로 직접 제시되거나 필수 정정문이 누락
  - 5: 확정 사실과 직접 모순 또는 정책 정의 필수 고지 누락

```text
pᵢ = (Mᵢ × Lᵢ) / 25

I = ROUND_HALF_UP(100 × (1 - Π(1 - pᵢ)))
```

- 중간 반올림 금지, 최종에만 반올림
- `BigDecimal` 기반 정확 계산
- score 범위: 0~100 clamp
- 중복 키: `sourceSnapshotId + policyRuleId + normalizedDocumentSpanAnchor`
- 같은 결함을 여러 rule·모델 문장이 반복해도 한 harm event로 묶음
- policy-defined hard-stop은 숫자와 분리된 `requiresHumanEscalation`으로 표시
- score band 기본안
  - 0~19: LOW
  - 20~49: ELEVATED
  - 50~79: HIGH
  - 80~100: URGENT
- band는 TEVV fixture calibration 뒤 versioned policy로 확정

### 분리해야 하는 네 출력

- `intrinsicDocumentRisk`: reviewer 수용 Finding 기반 pre-GuardFit 지수
- `modelReliability`: model·prompt·dataset version별 offline TEVV 지표, 점수 가중치 아님
- `reviewDecision`: Finding별 `ACCEPTED`, `REJECTED`, `NEEDS_EVIDENCE`, `ACCEPTED_WITH_OVERRIDE`
- `residualRisk`: GuardFit 승인만으로 계산 금지, 실제 수정 문서의 새 revision 재분석 뒤에만 표시

### 필수 근거 anchor

각 score-eligible Finding은 아래를 불변 저장한다.

- product document revision ID·content SHA-256·page·UTF-8 byte offset·excerpt SHA-256
- policy/evidence source ID·version·content SHA-256·chunk ID·chunk SHA-256·byte offset·excerpt SHA-256
- `DOCUMENT_CLAIM` 또는 `POLICY_REQUIREMENT` source role
- retrieval run/snapshot ID, policy rule ID, M/L rationale, score policy version
- reviewer decision·override reason·actor·trace ID·시간

## 결정 2. 상황 기반 Persona taxonomy

### 원칙

- 기존 Persona template는 역사 결과를 보존하고 새 taxonomy version에서 retired 처리
- 고객 segment·나이·직업·민감 특성으로 사용 금지
- `Persona`는 문서가 특정 상황에서 설명 실패를 만드는지 확인하는 synthetic scenario lens
- global score에는 직접 가중치 없음
- 상황 lens 추가는 같은 Finding score를 올리지 않고 필수 scenario coverage만 바꿈

### V1 상황 lens

- `LIMITED_PRODUCT_FAMILIARITY`
  - 상품 구조·용어 이해 지원이 필요한 상황
- `LOSS_RECOVERY_PRESSURE`
  - 손실 만회 기대가 수익·안정성 표현에 영향을 받을 수 있는 상황
- `NEAR_TERM_LIQUIDITY_NEED`
  - 가까운 시일 내 자금 사용·해지 가능성을 확인하는 상황
- `VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT`
  - 월별 현금흐름·상환 부담을 확인하는 상황
- `EXPLANATION_ACCESS_SUPPORT`
  - 복잡한 정보에 추가 설명·이해 확인이 필요한 상황
- `DIGITAL_CHANNEL_SUPPORT`
  - 디지털 채널·전자 고지에서 추가 확인이 필요한 상황
- `LIFE_EVENT_FINANCIAL_STRESS`
  - 일시적 소득·돌봄·건강·상실 등 변화 상황에서 핵심 조건을 확인하는 상황

### 선택 정책

- 4개 선택 상한 제거
- scoring analysis는 policy rule·상품 조건으로 필요한 모든 lens를 server가 결정
- exploratory analysis는 사용자가 필요한 수만큼 선택 가능하되 `EXPLORATORY_NOT_SCORE_ELIGIBLE`로 구분
- 각 lens는 trigger rule, 포함·제외 조건, 양성·음성 fixture, expected evidence, 만료·재검토일을 가진다

## 결정 3. 공통 불변 문서 기반

점수·batch·OCR의 구현 선행 조건이다.

- 원본 파일 content-addressed durable storage
- `logical_document_source`와 append-only `document_source_revision`
- extraction attempt·text revision·artifact manifest·audit event
- analysis는 confirmed text revision·source hash·attempt manifest를 고정
- 새 revision은 이전 analysis·score·review·RAG snapshot을 수정하지 않음
- 기존 `mock://` legacy document는 재처리 가능하다고 표시하지 않으며 재업로드가 필요함

## 결정 4. 대량 파일 처리

### 선택 기술

- PostgreSQL durable application queue
- Spring Batch·Redis·RabbitMQ 추가 안 함
- 사유: 현재 Compose에 PostgreSQL이 존재하고, PM 소유권·개별 status·cancel·retry·audit·source fencing을 직접 표현해야 함

### 기본 운영 한계

- batch: 최대 100 파일
- 파일: 최대 10 MiB
- batch aggregate: 최대 200 MiB
- system outstanding item: 최대 500
- PM당 active batch: 최대 2
- product당 동시 extraction: 최대 1
- document processing worker: 2개
- retry: 총 3회, 30초 → 2분 → 10분 backoff + bounded jitter
- 파일 hash는 스트리밍 SHA-256으로 계산

### 상태

```text
Batch
CREATED → UPLOADING → VALIDATING → QUEUED → PROCESSING
→ COMPLETED | COMPLETED_WITH_ERRORS | CANCELLED

Item
UPLOADING → VALIDATING → QUEUED → PROCESSING → SUCCEEDED
PROCESSING → RETRY_SCHEDULED → QUEUED
VALIDATING | PROCESSING → FAILED_TERMINAL | QUARANTINED | CANCELLED
```

### 보존 기본값

- confirmed source revision·score·review·audit: 마지막 활동 뒤 5년
- 미확정·terminal failure 원본: 30일
- quarantine artifact: 90일, 제한된 운영자 접근
- OCR page·TSV artifact: 연결된 source revision 보존 기간과 동일
- 실제 법령상 보존 정책이 아니라 B2B 운영 기본값이며 tenant·계약·법률 정책에 따라 override 가능

## 결정 5. PDF 한글 OCR

### 범위

- 1차: PDF만
- 제외: PPTX 내부 이미지 OCR, handwritten document, 외부 SaaS OCR
- PDFBox는 text layer probe로만 사용하고 OCR로 표현하지 않음

### 아키텍처

```text
Backend durable OCR job
→ isolated FastAPI ocr-worker
→ Poppler 300 DPI page render
→ pinned Tesseract tessdata_best kor+eng
→ plain text + TSV + page image artifact
→ PM source comparison·critical field review·confirmation
→ analysis input revision pin
```

### 품질·안전 정책

- page별 `TEXT_LAYER`, `OCR_PAGE`, `MIXED` routing
- `--oem 1`, 기본 `--psm 3`
- model·renderer·preprocess·flags·SHA-256 manifest 저장
- critical field: 금액·이율·날짜
- critical field token confidence 95 미만 또는 충돌·누락: 수동 검토 필수
- OCR confidence는 정확도·법적 적합성 보장이 아니라 검토 우선순위
- OCR/MIXED 문서는 confidence와 무관하게 명시적 PM confirmation 전 분석 불가
- page 45초, job 15분, 40 pages, 240M rendered pixels, 1GiB temp quota
- worker: concurrency 1, `cpus: 2`, `mem_limit: 2g`, `pids_limit: 256`

## Feature 순서와 GitHub Issue

### Feature 0. 정책·TEVV specification

- branch: `feature/risk-policy-and-tevv-spec`
- Issue: [#83 결정론 점수 정책과 상황 기반 Persona 기준 확정](https://github.com/skala-mini-project-web/mini-project/issues/83)
- 산출물: policy ontology, M/L matrix, hard-stop catalog, taxonomy version, fixture manifest, calibration plan
- 완료 기준: 정책 owner와 reviewer가 versioned spec을 승인

### Feature 1. 불변 source revision·근거 anchor

- branch: `feature/immutable-document-evidence`
- Issue: [#84 불변 문서 revision과 score 근거 anchor 도입](https://github.com/skala-mini-project-web/mini-project/issues/84)
- 완료 기준: source·evidence offset/hash, append-only retry/review history, migration upgrade·clean DB 검증

### Feature 2. Persona taxonomy와 결정론 score engine

- branch: `feature/evidence-risk-score`
- Issue: [#85 근거 기반 문서 위험 우선순위 지수 구현](https://github.com/skala-mini-project-web/mini-project/issues/85)
- 완료 기준: provider raw score 비권위화, M/L policy engine, score ledger, reviewer override audit, UI score states, golden fixture·Docker E2E

### Feature 3. durable batch ingestion

- branch: `feature/durable-document-batch`
- Issue: [#86 대량 문서 durable queue와 재시도 workflow 구현](https://github.com/skala-mini-project-web/mini-project/issues/86)
- 완료 기준: one-item compatibility, batch lifecycle, claim/lease/fence/recovery, cancellation, error CSV, non-mock browser E2E

### Feature 4. PDF Korean OCR

- branch: `feature/korean-pdf-ocr`
- Issue: [#87 PDF 한글 OCR worker와 검토 workflow 구현](https://github.com/skala-mini-project-web/mini-project/issues/87)
- 완료 기준: actual Compose arm64·amd64, text-layer/OCR/MIXED, provenance, critical review, confirmation fence, corpus E2E

## 기능별 GitHub 반영 조건

- 해당 feature branch는 최신 `origin/develop`에서 생성
- Issue·commit은 개조식
- migration·API·backend·frontend·fixtures·docs·README roadmap 상태를 같은 PR에 포함
- unit·integration·migration·authorization·retry·failure-path test 통과
- Docker Compose actual service 검증
- non-mock browser E2E와 page error·request failure·unexpected 4xx/5xx 검증
- 실패·미검증 상태에서는 push·PR·merge 금지
- `develop` 병합 뒤 release 검증을 통과한 feature 집합만 `main` release PR 생성

## README roadmap 상태

- 점수·Persona: 설계 확정, 구현 대기
- batch: 설계 확정, 구현 대기
- PDF 한글 OCR: 설계 확정, 구현 대기

## 주요 근거

- NIST AI RMF MEASURE: https://airc.nist.gov/airmf-resources/airmf/5-sec-core/
- NIST AI RMF Playbook MEASURE: https://airc.nist.gov/airmf-resources/playbook/measure/
- 금융위원회 온라인 금융상품 설명방안: https://www.fsc.go.kr/po010105/78276
- OECD 금융소비자 취약성: https://www.oecd.org/content/dam/oecd/en/publications/reports/2025/04/understanding-and-responding-to-financial-consumer-vulnerability_838bba5d/111daec8-en.pdf
- Tesseract 품질 가이드: https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html
- Tesseract TSV: https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html#tsv-output
