package com.crosschecklab.domain.document.extraction;

import java.util.List;

/**
 * Mock 추출 시나리오 한 건. {@code fixtures/document-extraction-scenarios.json} 의 항목과 1:1 대응한다.
 *
 * @param fileNamePatterns 파일명에 이 조각이 들어 있으면 해당 시나리오로 판정한다
 * @param failFirstAttempt 첫 시도를 실패시킬지 여부. 재추출 데모(EXTRACT_TIMEOUT_THEN_SUCCESS)용이다
 */
public record ExtractionScenario(
        String code,
        String description,
        List<String> fileNamePatterns,
        boolean failFirstAttempt,
        String extractedText
) {

    public List<String> fileNamePatterns() {
        return fileNamePatterns == null ? List.of() : fileNamePatterns;
    }
}
