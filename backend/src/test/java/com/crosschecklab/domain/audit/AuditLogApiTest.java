package com.crosschecklab.domain.audit;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
@DisplayName("감사 로그 저장소 및 조회 API")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(AuditLogApiTest.AuditClockConfiguration.class)
class AuditLogApiTest extends IntegrationTestSupport {

    private static final String API = "/api/audit-logs";
    private static final long PM = 1L;
    private static final long REVIEWER = 2L;
    private static final Set<String> PAGE_FIELDS = Set.of(
            "items", "offset", "limit", "totalElements",
            "snapshotCreatedAt", "snapshotAuditId");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "auditId", "createdAt", "action", "resourceType", "resourceId",
            "resourceLabel", "analysisId", "actorId", "traceId");
    private static final Set<String> ERROR_FIELDS = Set.of(
            "status", "errorCode", "message", "fieldErrors", "retryable", "traceId", "timestamp");

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdjustableClock auditClock;

    @Test
    @Order(1)
    @DisplayName("전체 범위를 벗어난 빈 페이지를 조회해도 행을 만들지 않는다")
    void returnsEmptyPageWithoutWritingAuditRows() throws Exception {
        long countBeforeRead = auditEventRepository.count();
        AuditEvent newestBeforeRead = auditEventRepository
                .findFirstByOrderByCreatedAtDescAuditIdDesc()
                .orElse(null);
        long emptyOffset = countBeforeRead + 1;

        JsonNode response = performReviewerGet(get(API).param("offset", Long.toString(emptyOffset)));

        assertThat(fieldNames(response)).isEqualTo(PAGE_FIELDS);
        assertThat(response.path("items").isArray()).isTrue();
        assertThat(response.path("items").isEmpty()).isTrue();
        assertThat(response.path("offset").asLong()).isEqualTo(emptyOffset);
        assertThat(response.path("limit").asInt()).isEqualTo(15);
        assertThat(response.path("totalElements").asLong()).isEqualTo(countBeforeRead);
        if (newestBeforeRead == null) {
            assertThat(response.path("snapshotCreatedAt").isNull()).isTrue();
            assertThat(response.path("snapshotAuditId").isNull()).isTrue();
        } else {
            assertThat(OffsetDateTime.parse(response.path("snapshotCreatedAt").asText()))
                    .isEqualTo(newestBeforeRead.getCreatedAt());
            assertThat(response.path("snapshotAuditId").asLong())
                    .isEqualTo(newestBeforeRead.getAuditId());
        }
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeRead);
    }

    @Test
    @Order(2)
    @DisplayName("검토자 조회만 허용하고 인증 실패는 공통 오류 봉투를 유지한다")
    void enforcesReviewerAuthorization() throws Exception {
        long countBeforeForbiddenRead = auditEventRepository.count();
        mockMvc.perform(asPm(get(API)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeForbiddenRead);

        long countBeforeUnauthorizedRead = auditEventRepository.count();
        MvcResult result = mockMvc.perform(get(API))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();

        assertThat(fieldNames(readBody(result))).isEqualTo(ERROR_FIELDS);
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeUnauthorizedRead);
    }

    @Test
    @Order(3)
    @DisplayName("기본 페이지와 비정렬 offset 페이지가 생성시각 및 id 역순으로 안정적으로 조회된다")
    void listsStableOffsetPagesWithOnlyDocumentedFields() throws Exception {
        long baselineCount = auditEventRepository.count();
        OffsetDateTime latestBaseline = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM audit_events", OffsetDateTime.class);
        Instant testRowsStart = latestBaseline == null
                ? Instant.parse("2026-09-04T00:00:00Z")
                : latestBaseline.toInstant().plusSeconds(1);
        String traceScope = "audit-page-" + baselineCount + "-";
        List<AuditEvent> inserted = new ArrayList<>();
        AuditAction[] actions = AuditAction.values();
        for (int index = 0; index < 18; index++) {
            AuditAction action = actions[index % actions.length];
            auditClock.set(testRowsStart.plusSeconds(index / 2));
            AuditEvent event = AuditEvent.create(
                    traceScope + index,
                    index % 3 == 0 ? null : REVIEWER,
                    action,
                    action.getResourceType(),
                    10_000L + index,
                    index % 4 == 0 ? null : "감사 대상 " + index,
                    action.getResourceType() == AuditResourceType.ANALYSIS ? 20_000L + index : null);
            // 같은 createdAt인 두 행을 만들어 auditId DESC 보조 정렬까지 검증한다.
            inserted.add(auditEventRepository.save(event));
        }

        List<AuditEvent> expected = inserted.stream()
                .sorted(Comparator.comparing(AuditEvent::getCreatedAt).reversed()
                        .thenComparing(Comparator.comparing(AuditEvent::getAuditId).reversed()))
                .toList();
        long totalElements = baselineCount + inserted.size();

        long countBeforeDefaultRead = auditEventRepository.count();
        JsonNode defaultPage = performReviewerGet(get(API));
        assertPage(defaultPage, expected.subList(0, 15), 0, 15, totalElements);
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeDefaultRead);

        long countBeforeOffsetRead = auditEventRepository.count();
        JsonNode offsetPage = performReviewerGet(get(API).param("offset", "7").param("limit", "4"));
        assertPage(offsetPage, expected.subList(7, 11), 7, 4, totalElements);
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeOffsetRead);
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidQueries")
    @Order(4)
    @DisplayName("쿼리 문법과 범위를 엄격하게 검증한다")
    void rejectsInvalidQueryGrammar(String field, String[] values, String message) throws Exception {
        long countBeforeRead = auditEventRepository.count();

        MvcResult result = mockMvc.perform(asReviewer(get(API).queryParam(field, values)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(message))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();

        JsonNode response = readBody(result);
        assertThat(fieldNames(response)).isEqualTo(ERROR_FIELDS);
        assertThat(fieldNames(response.path("fieldErrors").get(0)))
                .isEqualTo(Set.of("field", "message"));
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeRead);
    }

    static Stream<Arguments> invalidQueries() {
        return Stream.of(
                Arguments.of("unknown", new String[]{"1"}, "허용되지 않은 파라미터입니다."),
                Arguments.of("offset", new String[]{"1", "2"}, "한 번만 지정할 수 있습니다."),
                Arguments.of("limit", new String[]{"1", "2"}, "한 번만 지정할 수 있습니다."),
                Arguments.of("offset", new String[]{"1.5"}, "형식이 올바르지 않습니다."),
                Arguments.of("limit", new String[]{"1.5"}, "형식이 올바르지 않습니다."),
                Arguments.of("offset", new String[]{"+1"}, "형식이 올바르지 않습니다."),
                Arguments.of("offset", new String[]{"-1"}, "형식이 올바르지 않습니다."),
                Arguments.of("limit", new String[]{"+1"}, "형식이 올바르지 않습니다."),
                Arguments.of("limit", new String[]{"-1"}, "형식이 올바르지 않습니다."),
                Arguments.of("offset", new String[]{"１"}, "형식이 올바르지 않습니다."),
                Arguments.of("limit", new String[]{"١"}, "형식이 올바르지 않습니다."),
                Arguments.of("offset", new String[]{"9223372036854775808"}, "형식이 올바르지 않습니다."),
                Arguments.of("limit", new String[]{"2147483648"}, "형식이 올바르지 않습니다."),
                Arguments.of("limit", new String[]{"0"}, "1 이상 100 이하여야 합니다."),
                Arguments.of("limit", new String[]{"101"}, "1 이상 100 이하여야 합니다."));
    }

    @Test
    @Order(5)
    @DisplayName("스냅샷 필드의 잘못된 문법을 공통 필드 오류 봉투로 거부한다")
    void rejectsMalformedSnapshotBoundary() throws Exception {
        long countBeforeRead = auditEventRepository.count();

        mockMvc.perform(asReviewer(get(API)
                        .param("snapshotCreatedAt", "2026-09-04T00:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("snapshotAuditId"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("스냅샷 필드는 함께 지정해야 합니다."));

        mockMvc.perform(asReviewer(get(API).param("snapshotAuditId", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("snapshotCreatedAt"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("스냅샷 필드는 함께 지정해야 합니다."));

        MvcResult malformedCreatedAt = mockMvc.perform(asReviewer(get(API)
                        .param("snapshotCreatedAt", "2026-09-04")
                        .param("snapshotAuditId", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("snapshotCreatedAt"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("형식이 올바르지 않습니다."))
                .andReturn();
        assertThat(fieldNames(readBody(malformedCreatedAt))).isEqualTo(ERROR_FIELDS);

        MvcResult malformedAuditId = mockMvc.perform(asReviewer(get(API)
                        .param("snapshotCreatedAt", "2026-09-04T00:00:00Z")
                        .param("snapshotAuditId", "1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("snapshotAuditId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("형식이 올바르지 않습니다."))
                .andReturn();
        assertThat(fieldNames(readBody(malformedAuditId))).isEqualTo(ERROR_FIELDS);
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeRead);
    }

    @Test
    @Order(6)
    @DisplayName("동시 추가 중에도 스냅샷 offset 순회에는 중복과 누락이 없다")
    void freezesOffsetTraversalAcrossConcurrentAppend() throws Exception {
        OffsetDateTime latest = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM audit_events", OffsetDateTime.class);
        auditClock.set(latest.toInstant().plusSeconds(1));
        AuditEvent newestBeforeTraversal = auditEventRepository.save(AuditEvent.create(
                "snapshot-before-append", REVIEWER, AuditAction.REVIEW_APPROVED,
                AuditResourceType.REVIEW, 50_000L, "스냅샷 기준 행", null));

        List<Long> expectedIds = jdbc.queryForList("""
                SELECT audit_id
                FROM audit_events
                ORDER BY created_at DESC, audit_id DESC
                """, Long.class);
        int pageLimit = 4;
        JsonNode firstPage = performReviewerGet(
                get(API).param("offset", "0").param("limit", Integer.toString(pageLimit)));
        assertThat(firstPage.path("snapshotAuditId").asLong())
                .isEqualTo(newestBeforeTraversal.getAuditId());
        String snapshotCreatedAt = firstPage.path("snapshotCreatedAt").asText();
        String snapshotAuditId = firstPage.path("snapshotAuditId").asText();
        long frozenTotal = firstPage.path("totalElements").asLong();
        assertThat(frozenTotal).isEqualTo(expectedIds.size());

        auditClock.set(latest.toInstant().plusSeconds(2));
        AuditEvent concurrentlyAppended = auditEventRepository.save(AuditEvent.create(
                "snapshot-concurrent-append", REVIEWER, AuditAction.REVIEW_APPROVED,
                AuditResourceType.REVIEW, 50_001L, "동시 추가 행", null));

        List<Long> traversedIds = new ArrayList<>();
        firstPage.path("items").forEach(item -> traversedIds.add(item.path("auditId").asLong()));
        for (long offset = pageLimit; offset < frozenTotal; offset += pageLimit) {
            JsonNode page = performReviewerGet(get(API)
                    .param("offset", Long.toString(offset))
                    .param("limit", Integer.toString(pageLimit))
                    .param("snapshotCreatedAt", snapshotCreatedAt)
                    .param("snapshotAuditId", snapshotAuditId));
            assertThat(page.path("totalElements").asLong()).isEqualTo(frozenTotal);
            assertThat(page.path("snapshotCreatedAt").asText()).isEqualTo(snapshotCreatedAt);
            assertThat(page.path("snapshotAuditId").asText()).isEqualTo(snapshotAuditId);
            page.path("items").forEach(item -> traversedIds.add(item.path("auditId").asLong()));
        }

        assertThat(traversedIds).containsExactlyElementsOf(expectedIds);
        assertThat(traversedIds).doesNotHaveDuplicates();

        JsonNode newTraversal = performReviewerGet(
                get(API).param("offset", "0").param("limit", "1"));
        assertThat(newTraversal.path("items").get(0).path("auditId").asLong())
                .isEqualTo(concurrentlyAppended.getAuditId());
        assertThat(newTraversal.path("totalElements").asLong()).isEqualTo(frozenTotal + 1);
    }

    @Test
    @Order(7)
    @DisplayName("모든 유효 action/resource 조합은 저장되고 불일치는 모델과 DB에서 거부된다")
    void enforcesActionResourceCompatibility() {
        for (AuditAction action : AuditAction.values()) {
            AuditResourceType mismatchedType = Stream.of(AuditResourceType.values())
                    .filter(candidate -> candidate != action.getResourceType())
                    .findFirst()
                    .orElseThrow();
            assertThatIllegalArgumentException().isThrownBy(() -> AuditEvent.create(
                    "factory-mismatch", REVIEWER, action, mismatchedType, 1L, null, null));

            AuditEvent stored = auditEventRepository.save(AuditEvent.create(
                    "valid-" + action.name().toLowerCase(), REVIEWER, action,
                    action.getResourceType(), 30_000L + action.ordinal(), action.name(), null));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT action, resource_type FROM audit_events WHERE audit_id = ?",
                    stored.getAuditId());
            assertThat(row)
                    .containsEntry("action", action.name())
                    .containsEntry("resource_type", action.getResourceType().name());
        }

        long countBeforeInvalidInsert = auditEventRepository.count();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_events (trace_id, actor_id, action, resource_type, resource_id)
                VALUES ('db-mismatch', ?, 'REVIEW_CREATED', 'ANALYSIS', 99999)
                """, REVIEWER))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(auditEventRepository.count()).isEqualTo(countBeforeInvalidInsert);
    }

    @Test
    @Order(8)
    @DisplayName("UPDATE, DELETE, TRUNCATE는 거부되고 실패 뒤에도 감사 행은 그대로 읽힌다")
    void rejectsEveryMutationAndPreservesRows() {
        AuditEvent immutableEvent = auditEventRepository.save(AuditEvent.create(
                "immutable-row", REVIEWER, AuditAction.REVIEW_APPROVED,
                AuditResourceType.REVIEW, 40_000L, "변경 불가 감사 행", null));
        Long auditId = immutableEvent.getAuditId();
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT * FROM audit_events WHERE audit_id = ?", auditId);
        long countBefore = auditEventRepository.count();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET trace_id = 'changed' WHERE audit_id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_events WHERE audit_id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is append-only");

        assertThat(auditEventRepository.count()).isEqualTo(countBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM audit_events WHERE audit_id = ?", auditId))
                .isEqualTo(before);
    }

    private JsonNode performReviewerGet(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(asReviewer(request))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result);
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertPage(JsonNode response, List<AuditEvent> expectedItems,
                            long offset, int limit, long totalElements) {
        assertThat(fieldNames(response)).isEqualTo(PAGE_FIELDS);
        assertThat(response.path("offset").asLong()).isEqualTo(offset);
        assertThat(response.path("limit").asInt()).isEqualTo(limit);
        assertThat(response.path("totalElements").asLong()).isEqualTo(totalElements);
        assertThat(response.path("items").size()).isEqualTo(expectedItems.size());

        for (int index = 0; index < expectedItems.size(); index++) {
            AuditEvent expected = expectedItems.get(index);
            JsonNode actual = response.path("items").get(index);
            assertThat(fieldNames(actual)).isEqualTo(ITEM_FIELDS);
            assertThat(actual.path("auditId").asLong()).isEqualTo(expected.getAuditId());
            assertThat(OffsetDateTime.parse(actual.path("createdAt").asText()))
                    .isEqualTo(expected.getCreatedAt());
            assertThat(actual.path("action").asText()).isEqualTo(expected.getAction().name());
            assertThat(actual.path("resourceType").asText()).isEqualTo(expected.getResourceType().name());
            assertThat(actual.path("resourceId").asLong()).isEqualTo(expected.getResourceId());
            assertNullableText(actual.path("resourceLabel"), expected.getResourceLabel());
            assertNullableLong(actual.path("analysisId"), expected.getAnalysisId());
            assertNullableLong(actual.path("actorId"), expected.getActorId());
            assertThat(actual.path("traceId").asText()).isEqualTo(expected.getTraceId());
        }
    }

    private void assertNullableText(JsonNode actual, String expected) {
        if (expected == null) {
            assertThat(actual.isNull()).isTrue();
        } else {
            assertThat(actual.asText()).isEqualTo(expected);
        }
    }

    private void assertNullableLong(JsonNode actual, Long expected) {
        if (expected == null) {
            assertThat(actual.isNull()).isTrue();
        } else {
            assertThat(actual.asLong()).isEqualTo(expected);
        }
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, PM).header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, REVIEWER).header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuditClockConfiguration {

        @Bean
        @Primary
        AdjustableClock auditTestClock() {
            return new AdjustableClock(
                    Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    static final class AdjustableClock extends Clock {

        private volatile Instant currentInstant;
        private final ZoneId zone;

        private AdjustableClock(Instant currentInstant, ZoneId zone) {
            this.currentInstant = currentInstant;
            this.zone = zone;
        }

        void set(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new AdjustableClock(currentInstant, requestedZone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
