# Use-Case

- 원본 명세: `ARGUS_MVP_기능_명세서_v0.3.1.docx`
- PM 흐름: 상품 등록 → PDF 업로드 → 추출 텍스트 확정 → VERIFIED 공식 사실 확인 → Persona + Red Team 분석 → reviewer 검토 요청
- reviewer 흐름: Finding·RAG trace 확인 → 승인 또는 반려
- 승인 흐름: 선택 Finding → Risk Pattern → GuardFit 승인 → PM 적용 가이드
- 반려 흐름: comment 필수 → Risk Pattern·GuardFit 미생성
- 분석 원문: 분석이 생성되면 수정·확정 해제 불가
