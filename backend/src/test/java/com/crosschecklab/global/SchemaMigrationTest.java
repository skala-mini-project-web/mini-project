package com.crosschecklab.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("스키마 마이그레이션")
class SchemaMigrationTest extends IntegrationTestSupport {

    private static final List<String> EXPECTED_TABLES = List.of(
            "users",
            "products", "product_documents",
            "evidence_documents", "persona_templates", "red_team_packs", "red_team_rules",
            "analyses", "analysis_personas", "analysis_evidence_documents",
            "findings", "finding_affected_personas", "evidence_references",
            "reviews", "review_selected_findings",
            "risk_patterns", "guardfit_actions");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record PersonaSeed(long id, String code, boolean active) {
    }

    @Test
    @DisplayName("확정 ERD 17테이블이 모두 생성된다")
    void createsAllTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).containsAll(EXPECTED_TABLES);
        assertThat(EXPECTED_TABLES).hasSize(17);
    }

    @Test
    @DisplayName("analyses에 status <> FAILED 조건의 partial unique index가 존재한다")
    void createsPartialUniqueIndexOnAnalyses() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_analyses_document_input_hash'",
                String.class);

        // Postgres가 WHERE 절을 정규화하므로 (status)::text <> 'FAILED'::text 형태로 저장된다.
        assertThat(definition)
                .contains("CREATE UNIQUE INDEX")
                .contains("(product_document_id, input_hash)")
                .containsPattern("WHERE .*status.*<>.*'FAILED'");
    }

    @Test
    @DisplayName("legacy Persona 5종은 비활성으로 보존되고 상황 Persona 7종은 활성으로 시딩된다")
    void seedsCompleteLegacyAndSituationPersonaTaxonomy() {
        List<PersonaSeed> personas = jdbcTemplate.query(
                "SELECT id, code, active FROM persona_templates ORDER BY id",
                (resultSet, rowNumber) -> new PersonaSeed(
                        resultSet.getLong("id"),
                        resultSet.getString("code"),
                        resultSet.getBoolean("active")));

        assertThat(personas).containsExactly(
                new PersonaSeed(1L, "FINANCIAL_BEGINNER", false),
                new PersonaSeed(2L, "SENIOR", false),
                new PersonaSeed(3L, "LOSS_EXPERIENCED", false),
                new PersonaSeed(4L, "SHORT_TERM_LIQUIDITY", false),
                new PersonaSeed(5L, "SELF_EMPLOYED", false),
                new PersonaSeed(6L, "LIMITED_PRODUCT_FAMILIARITY", true),
                new PersonaSeed(7L, "LOSS_RECOVERY_PRESSURE", true),
                new PersonaSeed(8L, "NEAR_TERM_LIQUIDITY_NEED", true),
                new PersonaSeed(9L, "VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT", true),
                new PersonaSeed(10L, "EXPLANATION_ACCESS_SUPPORT", true),
                new PersonaSeed(11L, "DIGITAL_CHANNEL_SUPPORT", true),
                new PersonaSeed(12L, "LIFE_EVENT_FINANCIAL_STRESS", true));
    }

    @Test
    @DisplayName("CORE_FINANCIAL_RISK_V1 Pack에 Rule 6종이 시딩된다")
    void seedsRedTeamPackWithSixRules() {
        Integer ruleCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM red_team_rules r
                JOIN red_team_packs p ON p.id = r.pack_id
                WHERE p.code = 'CORE_FINANCIAL_RISK_V1'
                """, Integer.class);

        assertThat(ruleCount).isEqualTo(6);
    }

    // 삽입한 행이 다른 테스트 클래스의 시드 데이터 개수 단언을 깨뜨리므로 롤백시킨다.
    // (컨테이너는 JVM 당 하나라 커밋하면 이후 모든 테스트가 이 행을 보게 된다)
    @Test
    @Transactional
    @DisplayName("시드 이후 identity 시퀀스가 재정렬되어 새 행을 삽입할 수 있다")
    void resetsIdentitySequencesAfterSeeding() {
        Long generatedId = jdbcTemplate.queryForObject("""
                INSERT INTO evidence_documents (source_type, title, active, created_at, updated_at)
                VALUES ('REGULATION', '시퀀스 확인용', TRUE, NOW(), NOW())
                RETURNING id
                """, Long.class);

        assertThat(generatedId).isGreaterThan(3L);
    }
}
