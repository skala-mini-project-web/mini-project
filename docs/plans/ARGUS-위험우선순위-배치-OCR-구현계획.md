# ARGUS 위험 우선순위·대량 처리·한글 OCR 구현 계획

- 상태: 핵심 기능 구현·검증 및 `develop` 반영 완료, calibration·추가 설계 목표는 별도 추적
- 기준 branch: `origin/develop`
- 최근 반영: [PR #110](https://github.com/skala-mini-project-web/mini-project/pull/110), merge commit `c4cbe52` — 근거·점수 계약, 비동기 복구, 조회·OCR 보강
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
- 현재 공개 상태: `SCORED`, `PENDING_REVIEW`, `NOT_SCORED`
- reviewer 승인 전에는 `PENDING_REVIEW`, 승인된 Finding의 정책·근거 요건이 충족된 경우에만 `SCORED`의 0~100 지수 표시
- 근거 부족은 `NOT_SCORED`와 사유 코드로 표현하며 `위험 없음`이나 0점으로 대체하지 않음
- Finding은 0~20건. 빈 검토 승인 뒤에는 `NOT_SCORED / NO_APPROVED_FINDING`, 점수는 null
- 초기 설계의 `PROVISIONAL`·`NEEDS_EVIDENCE`·`INCOMPLETE_COVERAGE`는 현재 공개 상태 enum이 아니며, 필수 상황 lens 자동 coverage 판정은 추가 설계 목표

### 결정론 scorecard

각 중복 제거 Finding은 정책 version이 부여한 두 값을 가진다.

현재 정책 1.1.0은 rule별 고정 M/L을 0~10,000 basis points로 저장한다. 아래 1~5는 초기 척도의 의미 설명이며, LLM이 부여하는 점수가 아니다.

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
pᵢ = (Mᵢ_basisPoints × Lᵢ_basisPoints) / 10000²

I = ROUND_HALF_UP(100 × (1 - Π(1 - pᵢ)))
```

- 중간 반올림 금지, 최종에만 반올림
- `BigDecimal` 기반 정확 계산
- score 범위: 0~100 clamp
- 중복 키: `sourceDocumentId + sourceRevisionId + sourceHash + policyRuleCode + pageNumber + utf8StartOffset + utf8EndOffset + excerptHash`
- 동일 원문 범위·동일 규칙은 한 건으로 집계하되 원본 Finding·검토 이력은 보존. 같은 문장이라도 규칙이 다르면 별개 위험으로 집계
- 기존 가중치·noisy-OR는 유지. 일부 규칙의 100% 기여에 따른 점수 포화도 유지하며 실제 위험 확률을 뜻하지 않음
- policy-defined hard-stop·`requiresHumanEscalation`은 추가 설계 목표이며 현재 공개 출력이 아님
- score band 초기 설계안
  - 0~19: LOW
  - 20~49: ELEVATED
  - 50~79: HIGH
  - 80~100: URGENT
- band는 TEVV fixture calibration 뒤 versioned policy로 확정

### 분리해야 하는 네 출력 — 초기 설계 목표

아래 네 이름이 모두 현재 API 필드로 구현된 것은 아니다. 현재는 승인 전후 score state·불변 원장·Finding별 review decision을 제공하며, offline reliability·수정 후 residual risk는 별도 검증 대상이다.

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
- v18부터 모델은 정책 근거 option과 단일 문서 주장 option을 별도로 선택. AI service가 정확한 원문으로 매핑하고 backend가 고정된 revision의 고유 출현·UTF-8 범위·hash를 검증
- `knownFactIds`는 선택적 사실 인용 이력이며 문서 주장으로 추론하지 않음. 첫 fact 강제 대입·가짜 anchor·기존 이력 보정 금지

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

### 선택 정책 — 초기 설계 목표

상황 lens 7종과 기존 template 비활성화·역사 이력 보존은 V20에 반영했다. 아래 필수 lens 자동 선정·coverage 및 exploratory 구분은 현재 score engine의 완료 범위와 구분한다.

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

### 현재 처리 한계

- batch: 최대 100 파일
- 파일: 최대 10 MiB
- batch worker는 한 번 깨어날 때 최대 4개 item을 순차 claim·처리, item lease는 10분
- retry: 총 3회, 기본 15초 지수 backoff·최대 10분. bounded jitter는 현재 적용하지 않음
- 단건·배치 공통 문서 처리 예산: 기본 5분. OCR HTTP timeout보다 길고 10분 lease의 정리 여유 30초를 넘기지 않도록 검증
- 파일 hash는 스트리밍 SHA-256으로 계산
- 초기 설계의 aggregate 200 MiB·outstanding 500·PM active batch 2·상품별 extraction 1·worker 2는 현재 구현된 admission 한계로 간주하지 않음

### 상태

```text
Batch
PENDING → SUCCEEDED | CANCELLED | QUARANTINED

Item
PENDING → LEASED → SUCCEEDED
LEASED → RETRY_WAIT → LEASED
PENDING | LEASED | RETRY_WAIT → CANCELLED | QUARANTINED
```

### 보존 기본값 — 초기 설계안

아래 기간은 자동 보존·삭제 기능의 구현 완료를 뜻하지 않는다.

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
Backend PDFBox text-layer probe
→ text-inadequate page만 PDFBox 300 DPI render
→ isolated FastAPI ocr-worker
→ Tesseract kor+eng
→ plain text + TSV + page image artifact
→ PM source comparison·critical field review·confirmation
→ analysis input revision pin
```

### 품질·안전 정책

- text layer 우선, 부족한 page만 OCR로 처리하여 text/image 혼합 PDF 지원
- `--oem 1`, 기본 `--psm 6`
- run/page별 text·render hash, OCR confidence·engine version·언어·TSV 등 provenance 저장
- 초기 설계의 금액·이율·날짜 token confidence 95 기반 critical-field 검토는 현재 page confidence·PM confirmation과 구분되는 추가 목표
- OCR confidence는 정확도·법적 적합성 보장이 아니라 검토 우선순위
- OCR/MIXED 문서는 confidence와 무관하게 명시적 PM confirmation 전 분석 불가
- Tesseract 기본 timeout 30초, OCR HTTP 45초, 문서 전체 5분. PDF 렌더·OCR 이미지 각각 page당 40M pixels 선행 제한
- worker: concurrency 1, `cpus: 1`, `mem_limit: 768m`, `pids_limit: 128`, `/tmp` 256 MiB
- 인증·요청 크기 검사 후 본문 파싱 전에 한 건만 수락. 초과 요청은 `503 OCR_BUSY`; 실행 중 취소는 실제 OCR 종료까지 permit 유지
- 대량 스캔은 durable batch queue 사용. 동시 단건 스캔은 retryable FAILED와 수동 재시도가 발생할 수 있음
- 초기 설계의 40-page·문서 전체 240M pixels 한계는 현재 적용하지 않으며, 임의 길이 문서 완료를 보장하지 않음

## Feature 순서와 GitHub Issue

### Feature 0. 정책·TEVV specification

- branch: `feature/risk-policy-and-tevv-spec`
- Issue: [#83 결정론 점수 정책과 상황 기반 Persona 기준 확정](https://github.com/skala-mini-project-web/mini-project/issues/83)
- 상태: 정책·합성 fixture 반영 완료, held-out calibration·score band 재보정은 예정
- 산출물: policy ontology, M/L matrix, hard-stop catalog, taxonomy version, fixture manifest, calibration plan
- 완료 기준: 정책 owner와 reviewer가 versioned spec을 승인

### Feature 1a. 불변 source revision

- branch: `feature/immutable-document-evidence`
- Issue: [#84 불변 문서 revision과 score 근거 anchor 도입](https://github.com/skala-mini-project-web/mini-project/issues/84)
- 상태: 완료
- 완료 근거: durable source storage, source revision V15, actual Docker source hash·RAG E2E

### Feature 1b. 불변 evidence anchor·Finding revision

- branch: `feature/immutable-evidence-anchors`
- Issue: [#95 불변 score evidence anchor와 Finding revision 도입](https://github.com/skala-mini-project-web/mini-project/issues/95)
- 상태: 완료
- 완료 기준: source·evidence offset/hash, append-only execution·retry·review history, migration upgrade·clean DB 검증
- 데이터 원칙: 기존 excerpt-only 분석 이력에는 정확한 page·offset·hash를 추정해 넣지 않는다. 새 execution부터 anchored result만 score-eligible로 취급한다.

### Feature 2. Persona taxonomy와 결정론 score engine

- branch: `feature/evidence-risk-score`
- Issue: [#85 근거 기반 문서 위험 우선순위 지수 구현](https://github.com/skala-mini-project-web/mini-project/issues/85)
- 상태: 완료
- 완료 기준: provider raw score 비권위화, M/L policy engine, score ledger, reviewer override audit, UI score states, golden fixture·Docker E2E
- 완료 근거: Policy v1 score run·immutable ledger, Finding별 reviewer 승인/제외 결정 이력, `SCORED`/`PENDING_REVIEW`/`NOT_SCORED` UI, actual Docker/browser E2E
- 추가 반영: 정책 1.1.0의 동일 원문·규칙 중복 제거, Finding 0~20건·빈 검토 승인, nullable provider provenance. 필수 lens 자동 선정·추가 공개 출력은 완료 범위에서 제외

### Feature 3. durable batch ingestion

- branch: `feature/durable-document-batch`
- Issue: [#86 대량 문서 durable queue와 재시도 workflow 구현](https://github.com/skala-mini-project-web/mini-project/issues/86)
- 상태: 완료
- 완료 기준: 단일 item 배치, batch lifecycle, claim/lease/fence/recovery, cancellation, error CSV, non-mock browser E2E
- 완료 근거: V23 durable queue·attempt audit, 1–100 batch upload, `SKIP LOCKED` lease/fence, retry·cancel·quarantine, formula-safe CSV, actual Docker/browser E2E

### Feature 4. PDF Korean OCR

- branch: `feature/korean-pdf-ocr`
- Issue: [#87 PDF 한글 OCR worker와 검토 workflow 구현](https://github.com/skala-mini-project-web/mini-project/issues/87)
- 상태: 완료
- 완료 기준: actual Compose, text-layer/OCR/MIXED, provenance, PM confirmation fence, corpus E2E
- 완료 근거: PDFBox-first page routing, isolated `kor+eng` worker, V24 run/page provenance, PM run/text-hash confirmation, reviewer read-only, synthetic fixture actual browser E2E
- 검증 범위: 현재 기록은 arm64 환경 기준. amd64 실환경과 critical-field token별 검토는 별도 완료로 간주하지 않음

### Feature 5. 근거 계약·비동기 복구·조회·자원 보강

- branch: `fix/provider-persona-scope-num-ctx`
- Issue: [#111 비동기 작업 복구·상품 조회·점수 계약 보강과 실서비스 E2E 검증](https://github.com/skala-mini-project-web/mini-project/issues/111)
- 상태: 완료 — PR #110, `develop` merge commit `c4cbe52`
- 완료 근거: v18 명시적 문서 주장 선택·원문 검증, 수락 token·lease 복구, RAG 외부 HTTP/DB 잠금 분리, 상품 전체 검색·페이지/count·mock lifecycle·polling fence
- 완료 근거: PDF render 자원 제한·공통 추출 시간 예산, 본문 전 취소 안전 OCR admission, V26/V27 제약 추가·검증 분리
- 회귀: backend 461·AI 90·OCR 7·frontend smoke 113개, 실제 RAG·한국어 OCR browser E2E 통과
- 제한 부하: 조회 6,440건 오류 0·32동시 burst p95 98.57ms, 실제 RAG 2동시 성공, 스캔 배치 8/8 성공(14.16초), 컨테이너 OOM·비정상 재시작 0
- 확인된 한계: 단건 스캔 4동시 중 3건은 retryable FAILED 후 수동 재시도로 복구. 장시간 연속 부하·강제 OOM·최대 처리량·host Ollama 최대 메모리는 미검증
- 상세 근거: [부하 검증 결과](https://github.com/skala-mini-project-web/mini-project/pull/110#issuecomment-5581397161)

## 기능별 GitHub 반영 조건

- 해당 feature branch는 최신 `origin/develop`에서 생성
- Issue·commit은 개조식
- migration·API·backend·frontend·fixtures·docs·README roadmap 상태를 같은 PR에 포함
- unit·integration·migration·authorization·retry·failure-path test 통과
- Docker Compose actual service 검증
- non-mock browser E2E와 page error·request failure·unexpected 4xx/5xx 검증
- 실패·미검증 상태에서는 push·PR·merge 금지
- `develop` 병합 뒤 release 검증을 통과한 feature 집합만 `main` release PR 생성

## README 고도화 진행 상태

- 점수·Persona: 정책 1.1.0·상황 lens·불변 근거·review decision·score engine·UI, v18 문서 주장 검증·빈 결과·중복 집계 보강 완료. held-out calibration·artifact-pinned model comparison 예정
- batch: durable queue·browser E2E, 스캔 8건 배치 부하 검증 완료
- PDF 한글 OCR: worker·provenance·PM/reviewer workflow·browser E2E, 렌더 자원 제한·취소 안전 admission 보강 완료
- 상품 조회·비동기 작업: 전체 검색·페이지/count·mock lifecycle·polling fence, 수락 token·lease 실패 복구·RAG 트랜잭션 경계 보강 완료
- 실서비스 검증·합성 corpus: 회귀·실제 browser E2E·제한 부하 검증 완료. 대량 OCR은 배치 큐 사용 전제이며 장시간 연속 부하·강제 OOM·최대 처리량은 미검증

## 주요 근거

- NIST AI RMF MEASURE: https://airc.nist.gov/airmf-resources/airmf/5-sec-core/
- NIST AI RMF Playbook MEASURE: https://airc.nist.gov/airmf-resources/playbook/measure/
- 금융위원회 온라인 금융상품 설명방안: https://www.fsc.go.kr/po010105/78276
- OECD 금융소비자 취약성: https://www.oecd.org/content/dam/oecd/en/publications/reports/2025/04/understanding-and-responding-to-financial-consumer-vulnerability_838bba5d/111daec8-en.pdf
- Tesseract 품질 가이드: https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html
- Tesseract TSV: https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html#tsv-output
