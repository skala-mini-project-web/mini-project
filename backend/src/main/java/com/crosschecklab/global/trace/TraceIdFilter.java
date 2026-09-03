package com.crosschecklab.global.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 모든 요청에 traceId 를 부여
// X-Trace-Id 헤더가 있으면 그대로, 없거나 형식이 안 맞으면 trc-yyyyMMdd-0001 형태로 생성해서 MDC + 응답 헤더에 싣는다.
// Security 필터보다 먼저 돌아야 인증 단계 오류에도 traceId 가 붙으므로 HIGHEST_PRECEDENCE.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    // 로그/헤더 인젝션 방지 — 외부에서 받은 값은 이 형식만 허용
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AtomicLong sequence = new AtomicLong();
    private volatile String sequenceDate = "";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    // 현재 요청 스레드의 traceId. 필터 밖(비동기 스레드 등)에서는 null 일 수 있다.
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    private String resolveTraceId(String headerValue) {
        if (headerValue != null && ALLOWED.matcher(headerValue).matches()) {
            return headerValue;
        }
        return generate();
    }

    private synchronized String generate() {
        String today = LocalDate.now().format(DATE_PART);
        if (!today.equals(sequenceDate)) {
            sequenceDate = today;
            sequence.set(0);
        }
        return "trc-%s-%04d".formatted(today, sequence.incrementAndGet());
    }
}
