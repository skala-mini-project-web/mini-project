package com.crosschecklab.domain.document.extraction;

import java.util.List;

// fixture 파일의 최상위 래퍼. 배열 대신 객체로 감싸 두면 나중에 메타 필드를 붙일 수 있다.
public record ExtractionScenarios(List<ExtractionScenario> scenarios) {
}
