package com.crosschecklab.global;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.config.ClockConfig;
import com.crosschecklab.global.config.SecurityConfig;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.trace.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Task 1 완료 조건 검증: 스택트레이스/내부 메시지가 응답에 노출되지 않는다, 모든 응답에 traceId 가 포함된다.
@WebMvcTest
@Import({ClockConfig.class, SecurityConfig.class, GlobalInfraTest.ProbeController.class})
class GlobalInfraTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException 은 계약 스키마 그대로 내려간다")
    void businessException() throws Exception {
        mockMvc.perform(get("/__probe/business"))
                .andExpect(status().isConflict())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.message").value("추출 텍스트 확인 후 분석을 요청하세요."))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                // 2026-09-03T09:03:12+09:00 형태 (초 단위, 오프셋 포함)
                .andExpect(jsonPath("$.timestamp")
                        .value(Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}([+-]\\d{2}:\\d{2}|Z)")));
    }

    @Test
    @DisplayName("예상치 못한 예외에도 스택트레이스와 내부 메시지가 응답에 노출되지 않는다")
    void unexpectedExceptionHidesInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/__probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Assertions.assertThat(body)
                .doesNotContain("jdbc:postgresql://secret-host")
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.crosschecklab.global.GlobalInfraTest")
                .doesNotContain("at java.");
    }

    @Test
    @DisplayName("검증 실패는 400 VALIDATION_ERROR + fieldErrors")
    void validationError() throws Exception {
        mockMvc.perform(post("/__probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("productDocumentId"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("권한 없음 / 리소스 없음 / 잘못된 메서드 / 지원하지 않는 Content-Type 도 각자 맞는 상태 코드로 내려간다")
    void frameworkExceptionsMapToDistinctErrorCodes() throws Exception {
        mockMvc.perform(get("/__probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        mockMvc.perform(get("/__probe/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        mockMvc.perform(post("/__probe/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));

        mockMvc.perform(post("/__probe/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("헤더/파라미터 누락, 타입 불일치도 각자 맞는 필드 오류를 담는다")
    void singleFieldErrorsIdentifyTheOffendingField() throws Exception {
        mockMvc.perform(get("/__probe/needs-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("X-Needed"));

        mockMvc.perform(get("/__probe/needs-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("count"));

        mockMvc.perform(get("/__probe/type-mismatch/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
    }

    @Test
    @DisplayName("성공 응답에도 X-Trace-Id 헤더가 붙는다")
    void successResponseCarriesTraceId() throws Exception {
        mockMvc.perform(get("/__probe/ok"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER,
                        Matchers.matchesPattern("trc-\\d{8}-\\d{4}")));
    }

    @Test
    @DisplayName("클라이언트가 보낸 X-Trace-Id 는 그대로 echo 된다")
    void echoesIncomingTraceId() throws Exception {
        mockMvc.perform(get("/__probe/business").header(TraceIdFilter.TRACE_ID_HEADER, "trc-20260903-0001"))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "trc-20260903-0001"))
                .andExpect(jsonPath("$.traceId").value("trc-20260903-0001"));
    }

    @Test
    @DisplayName("형식이 깨진 X-Trace-Id 는 무시하고 서버가 새로 발급한다")
    void rejectsMalformedTraceId() throws Exception {
        mockMvc.perform(get("/__probe/ok").header(TraceIdFilter.TRACE_ID_HEADER, "bad value\nInjected: 1"))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER,
                        Matchers.matchesPattern("trc-\\d{8}-\\d{4}")));
    }

    @Test
    @DisplayName("페이징 래퍼의 목록 키는 content 가 아니라 items 다")
    void pageResponseUsesItemsKey() throws Exception {
        mockMvc.perform(get("/__probe/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0]").value("first"))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/__probe/page")
        PageResponse<String> page() {
            return PageResponse.from(new PageImpl<>(List.of("first"), PageRequest.of(0, 20), 1));
        }

        @GetMapping("/__probe/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/__probe/business")
        String business() {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_CONFIRMED);
        }

        @GetMapping("/__probe/boom")
        String boom() {
            throw new IllegalStateException("jdbc:postgresql://secret-host/db 연결 실패");
        }

        @PostMapping("/__probe/validate")
        String validate(@Valid @RequestBody ProbeRequest request) {
            return "ok";
        }

        @GetMapping("/__probe/denied")
        String denied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/__probe/needs-header")
        String needsHeader(@RequestHeader("X-Needed") String header) {
            return header;
        }

        @GetMapping("/__probe/needs-param")
        String needsParam(@RequestParam("count") int count) {
            return String.valueOf(count);
        }

        @GetMapping("/__probe/type-mismatch/{id}")
        String typeMismatch(@PathVariable Long id) {
            return String.valueOf(id);
        }
    }

    record ProbeRequest(@NotNull Long productDocumentId) {
    }
}
