package com.crosschecklab.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("V1/V2 마이그레이션")
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
    @DisplayName("Persona 5종이 API 명세와 동일한 id로 시딩된다")
    void seedsPersonaTemplatesWithFixedIds() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM persona_templates ORDER BY id", String.class);

        assertThat(codes).containsExactly(
                "FINANCIAL_BEGINNER", "SENIOR", "LOSS_EXPERIENCED",
                "SHORT_TERM_LIQUIDITY", "SELF_EMPLOYED");
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
