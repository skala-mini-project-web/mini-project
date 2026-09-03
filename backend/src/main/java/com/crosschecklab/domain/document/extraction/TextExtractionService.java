package com.crosschecklab.domain.document.extraction;

// 문서에서 본문 텍스트를 뽑는다.
// 기본 구현은 fixture 기반 Mock 이고, real-extraction 프로파일에서 PDFBox/POI 구현으로 교체된다.
public interface TextExtractionService {

    /**
     * @throws TextExtractionException 추출에 실패한 경우. 호출 측이 문서를 FAILED 로 전이시킨다.
     */
    String extract(ExtractionTarget target);
}
