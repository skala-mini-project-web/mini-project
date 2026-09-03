# GuardLab V1 구현 결과 보고서 폴더

`frontend/docs/instructions/v1/GuardLab_AI_Flow_QA_Instructions_v1.md`에 따른 코드 수정이 끝난 후 다음 파일을 작성한다.

```text
frontend/docs/reports/v1/implementation-result.md
```

권장 목차:

1. 구현 요약
2. 변경 파일 및 변경 이유
3. 요구사항별 구현 상태
4. 계약/API/ERD 변경점
5. Mock 전용 처리와 실제 연동 시 교체점
6. 테스트 명령과 결과
7. 미구현·보류 항목 및 이유
8. 알려진 문제와 후속 작업
9. 화면별 확인 Route와 데모 순서

상태 값은 다음 중 하나로 통일한다.

- `IMPLEMENTED`
- `IMPLEMENTED_DIFFERENTLY`
- `MOCK_ONLY`
- `DEFERRED`
- `NOT_IMPLEMENTED`

결과 보고서에는 성공한 내용뿐 아니라 다르게 구현한 부분, 구현하지 않은 부분, 백엔드 합의가 필요한 부분을 모두 기록한다.
