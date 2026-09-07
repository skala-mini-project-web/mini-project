package com.crosschecklab.domain.document.extraction;

// 문서에서 본문 텍스트를 뽑는다.
// 기본 구현은 fixture 기반 Mock 이고, real-extraction 프로파일에서 PDFBox/POI 구현으로 교체된다.
public interface TextExtractionService {

    /**
     * @throws TextExtractionException 손상되었거나 지원하지 않는 문서처럼 재시도해도 복구되지
     *         않는 입력은 non-retryable 예외로, 일시적인 실패는 retryable 예외로 보고한다.
     *         호출 측은 이 분류를 보존해 문서나 배치 항목을 실패 상태로 전이시킨다.
     */
    String extract(ExtractionTarget target);
}
