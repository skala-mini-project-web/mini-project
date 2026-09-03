package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import com.crosschecklab.global.fixture.JsonFixtureLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// 업로드된 파일을 어떤 Mock 시나리오로 추출할지 정한다.
// 우선순위: X-Demo-Scenario 헤더 > 파일명 패턴 > 기본값.
// 데모 진행자가 헤더 하나로 위험도가 다른 결과를 재현할 수 있게 하는 것이 목적이다.
@Component
public class ExtractionScenarioResolver {

    public static final String DEFAULT_SCENARIO_CODE = "GUARANTEE_MISUNDERSTANDING_HIGH";

    private static final String FIXTURE_FILE = "document-extraction-scenarios.json";

    // 파일명 패턴 매칭이 선언 순서를 따르도록 순서를 보존한다.
    private final Map<String, ExtractionScenario> scenariosByCode = new LinkedHashMap<>();

    public ExtractionScenarioResolver(JsonFixtureLoader fixtureLoader) {
        List<ExtractionScenario> loaded = fixtureLoader.load(FIXTURE_FILE, ExtractionScenarios.class).scenarios();
        loaded.forEach(scenario -> scenariosByCode.put(scenario.code(), scenario));

        if (!scenariosByCode.containsKey(DEFAULT_SCENARIO_CODE)) {
            throw new IllegalStateException("기본 시나리오가 fixture 에 없습니다: " + DEFAULT_SCENARIO_CODE);
        }
    }

    /**
     * @param requestedCode {@code X-Demo-Scenario} 헤더 값. 없으면 null
     * @param fileName      업로드 파일명
     */
    public String resolveCode(String requestedCode, String fileName) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            String code = requestedCode.trim().toUpperCase(Locale.ROOT);
            if (!scenariosByCode.containsKey(code)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        List.of(new ErrorResponse.FieldError("X-Demo-Scenario",
                                "지원하지 않는 시나리오입니다. 사용 가능한 값: "
                                        + String.join(", ", scenariosByCode.keySet()))));
            }
            return code;
        }

        return matchByFileName(fileName).orElse(DEFAULT_SCENARIO_CODE);
    }

    // 코드에 해당하는 시나리오. 저장된 storage_key 에서 복원할 때 사용한다.
    public ExtractionScenario require(String code) {
        ExtractionScenario scenario = scenariosByCode.get(code);
        if (scenario == null) {
            throw new IllegalStateException("시나리오를 찾을 수 없습니다: " + code);
        }
        return scenario;
    }

    private Optional<String> matchByFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return scenariosByCode.values().stream()
                .filter(scenario -> scenario.fileNamePatterns().stream()
                        .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase(Locale.ROOT))))
                .map(ExtractionScenario::code)
                .findFirst();
    }
}
