package com.crosschecklab.domain.document.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.global.fixture.JsonFixtureLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추출 시나리오 코드가 데이터 명세 v0.3 §6 표를 벗어나지 않는지 고정한다.
 *
 * <p>{@code X-Demo-Scenario} 는 문서 업로드(추출)와 분석 요청(ai-service)이 공유하는 헤더다.
 * 두 도메인이 각자 코드를 늘리면 같은 헤더 값이 한쪽에서만 통해서, 데모 중
 * 업로드는 400, 분석은 404 → PROVIDER_RESPONSE_INVALID(재시도 불가)로 갈린다.
 * 정상 시나리오 3종은 반드시 양쪽에 같은 이름으로 존재해야 한다.
 */
class ExtractionScenarioVocabularyTest {

    // 데이터 명세 §6 중 정상(추출·분석 공통) 시나리오.
    // ai-service 의 fixture_loader._scenario_files() 키와 반드시 일치해야 한다.
    private static final Set<String> SHARED_NORMAL_CODES = Set.of(
            "GUARANTEE_MISUNDERSTANDING_HIGH",
            "EARLY_TERMINATION_COST_MEDIUM",
            "ACCESSIBILITY_LOW");

    // 데이터 명세 §6 중 추출 도메인 전용 오류 시나리오.
    private static final Set<String> EXTRACTION_ONLY_CODES = Set.of("EXTRACT_TIMEOUT_THEN_SUCCESS");

    private final List<ExtractionScenario> scenarios = new JsonFixtureLoader(new ObjectMapper())
            .load("document-extraction-scenarios.json", ExtractionScenarios.class)
            .scenarios();

    @Test
    @DisplayName("추출 시나리오 코드는 데이터 명세 §6 표와 정확히 일치한다")
    void usesOnlySpecifiedScenarioCodes() {
        Set<String> expected = new HashSet<>(SHARED_NORMAL_CODES);
        expected.addAll(EXTRACTION_ONLY_CODES);

        assertThat(codes()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("분석과 공유하는 정상 시나리오 3종이 모두 추출 쪽에도 있다")
    void coversSharedNormalScenarios() {
        assertThat(codes()).containsAll(SHARED_NORMAL_CODES);
    }

    @Test
    @DisplayName("기본 시나리오는 fixture 에 존재한다")
    void containsDefaultScenario() {
        assertThat(codes()).contains(ExtractionScenarioResolver.DEFAULT_SCENARIO_CODE);
    }

    @Test
    @DisplayName("파일명 패턴이 시나리오끼리 겹치지 않는다")
    void keepsFileNamePatternsDisjoint() {
        List<String> allPatterns = scenarios.stream()
                .flatMap(scenario -> scenario.fileNamePatterns().stream())
                .toList();

        assertThat(allPatterns).doesNotHaveDuplicates();
    }

    private Set<String> codes() {
        return scenarios.stream().map(ExtractionScenario::code).collect(Collectors.toSet());
    }
}
