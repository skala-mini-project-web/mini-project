# ARGUS 합성 금융 문서 corpus v1

- 문서 수: 30개 PDF
- 분량: 102페이지
- 구성: 6개 합성 상품군 × 5개 문서 유형
  - 상품설명서
  - 핵심정보 확인서
  - 위험·제한 고지서
  - 비용·중도해지 안내서
  - 가입 전 확인서

## 사용 목적

- PDFBox text-layer extraction regression
- pgvector RAG retrieval·근거 추적 regression
- 향후 durable batch queue의 실제 workload
- 향후 PDF 한글 OCR feature의 born-digital baseline·mixed/scanned fixture 비교 기준

## 합성·안전 원칙

- 모든 회사·상품·문서번호·수치·절차·연락처·조건은 합성입니다.
- 실존 금융사, 상품, 약관, 법령, 소비자, 규제기관의 문구·양식을 복제하지 않습니다.
- 금융·법률 자문, 실제 가입·판매·심사·동의 기록에 사용하면 안 됩니다.
- 각 페이지의 header·footer와 본문에 합성 data 고지를 포함합니다.

## 재생성·검증

```bash
python tools/generate_synthetic_financial_corpus.py
python tools/validate_synthetic_financial_corpus.py
```

- `manifest.v1.json`: document logical ID, SHA-256, page 수, 예상 핵심 용어
- `expected-extraction.v1.json`: PDF text extraction과 RAG regression의 expected case
- `verification-receipt.v1.json`: 실제 backend PDFBox 30-document extraction 검증 결과
- generator는 macOS Korean font를 사용합니다. Linux CI·Docker에서 regeneration이 필요해질 때에는 동일 license의 pinned Korean font artifact를 별도 관리해야 하며, 생성된 PDF 자체는 font를 embed합니다.
