package com.crosschecklab.global.error;

import com.crosschecklab.global.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 모든 예외를 ErrorResponse 한 형태로 변환
// 스택트레이스는 로그에만, 응답엔 절대 안 넣는다.
// Exception.class 하나만 잡으면 Spring 이 원래 처리하던 404/405/415 까지 500 이 되므로 표준 예외는 여기서 직접 되살린다.
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Clock clock;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("[{}] {} {} -> {} ({})", traceId(), request.getMethod(), request.getRequestURI(),
                e.getErrorCode(), e.getMessage());
        return build(e.getErrorCode(), e.getMessage(), e.isRetryable(), e.getFieldErrors());
    }

    // @Valid 로 검증한 요청 본문이 깨진 경우 — 필드별 상세 오류를 그대로 살린다
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.add(new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage())));
        e.getBindingResult().getGlobalErrors()
                .forEach(ge -> fieldErrors.add(new ErrorResponse.FieldError(ge.getObjectName(), ge.getDefaultMessage())));
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), false, fieldErrors);
    }

    // @RequestParam 등 컨트롤러 파라미터에 붙은 제약 조건 위반
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                fieldErrors.add(new ErrorResponse.FieldError(
                        result.getMethodParameter().getParameterName(), error.getDefaultMessage()))));
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), false, fieldErrors);
    }

    // 서비스 레이어에서 발생한 Bean Validation 위반
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), false, fieldErrors);
    }

    // 헤더/파라미터 누락, 타입 불일치 — 문제된 필드 하나만 짚어주면 되는 경우들을 한데 묶음
    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleSingleFieldError(Exception e) {
        ErrorResponse.FieldError fieldError = switch (e) {
            case MissingRequestHeaderException ex -> new ErrorResponse.FieldError(ex.getHeaderName(), "필수 헤더입니다.");
            case MissingServletRequestParameterException ex -> new ErrorResponse.FieldError(ex.getParameterName(), "필수 파라미터입니다.");
            case MethodArgumentTypeMismatchException ex -> new ErrorResponse.FieldError(ex.getName(), "형식이 올바르지 않습니다.");
            default -> throw new IllegalStateException("unreachable: " + e.getClass());
        };
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), false, List.of(fieldError));
    }

    // JSON 파싱 실패 / 본문 누락
    // Jackson 예외 메시지엔 파싱하다 만 요청 본문 조각이 들어있어서 응답은 물론 로그에도 남기지 않는다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[{}] 요청 본문 파싱 실패 ({})", traceId(), e.getClass().getSimpleName());
        return build(ErrorCode.VALIDATION_ERROR, "요청 본문을 해석할 수 없습니다.", false, List.of());
    }

    // 권한 없음 / 리소스 없음 / 잘못된 메서드 / 지원하지 않는 Content-Type — 상태 코드만 되살리면 되는 경우들을 한데 묶음
    @ExceptionHandler({
            AccessDeniedException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<ErrorResponse> handleFrameworkDefaults(Exception e) {
        ErrorCode errorCode = switch (e) {
            case AccessDeniedException ignored -> ErrorCode.FORBIDDEN;
            case NoResourceFoundException ignored -> ErrorCode.NOT_FOUND;
            case HttpRequestMethodNotSupportedException ignored -> ErrorCode.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException ignored -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> throw new IllegalStateException("unreachable: " + e.getClass());
        };
        return build(errorCode, errorCode.getDefaultMessage(), false, List.of());
    }

    // 최종 방어선. 여기까지 온 예외는 스택트레이스를 서버 로그에만 남기고 응답엔 일반 메시지만 내려간다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("[{}] 처리되지 않은 예외 {} {}", traceId(), request.getMethod(), request.getRequestURI(), e);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(), false, List.of());
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode errorCode, String message, boolean retryable,
                                                List<ErrorResponse.FieldError> fieldErrors) {
        ErrorResponse body = ErrorResponse.of(errorCode, message, retryable, fieldErrors, traceId(), now());
        return ResponseEntity.status(errorCode.getStatus()).body(body);
    }

    private String traceId() {
        return TraceIdFilter.currentTraceId();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
