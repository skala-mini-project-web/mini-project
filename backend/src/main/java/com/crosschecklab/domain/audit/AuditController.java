package com.crosschecklab.domain.audit;

import com.crosschecklab.domain.audit.dto.AuditLogPageResponse;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "offset", "limit", "snapshotCreatedAt", "snapshotAuditId");
    private static final long DEFAULT_OFFSET = 0;
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 100;

    private final AuditService auditService;

    @GetMapping
    public AuditLogPageResponse list(@RequestParam MultiValueMap<String, String> queryParameters,
                                     @CurrentUser DemoUser currentUser) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        queryParameters.keySet().stream()
                .filter(parameter -> !ALLOWED_PARAMETERS.contains(parameter))
                .forEach(parameter -> fieldErrors.add(
                        new ErrorResponse.FieldError(parameter, "허용되지 않은 파라미터입니다.")));

        long offset = parseOffset(queryParameters.get("offset"), fieldErrors);
        int limit = parseLimit(queryParameters.get("limit"), fieldErrors);
        List<String> snapshotCreatedAtValues = queryParameters.get("snapshotCreatedAt");
        List<String> snapshotAuditIdValues = queryParameters.get("snapshotAuditId");
        validateSnapshotPair(snapshotCreatedAtValues, snapshotAuditIdValues, fieldErrors);
        OffsetDateTime snapshotCreatedAt = parseSnapshotCreatedAt(
                snapshotCreatedAtValues, fieldErrors);
        Long snapshotAuditId = parseSnapshotAuditId(snapshotAuditIdValues, fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, fieldErrors);
        }
        return auditService.list(
                offset, limit, snapshotCreatedAt, snapshotAuditId, currentUser);
    }

    private long parseOffset(List<String> values, List<ErrorResponse.FieldError> fieldErrors) {
        if (values == null) {
            return DEFAULT_OFFSET;
        }
        if (values.size() != 1) {
            fieldErrors.add(new ErrorResponse.FieldError("offset", "한 번만 지정할 수 있습니다."));
            return DEFAULT_OFFSET;
        }
        String value = values.getFirst();
        if (!isAsciiDecimal(value)) {
            fieldErrors.add(new ErrorResponse.FieldError("offset", "형식이 올바르지 않습니다."));
            return DEFAULT_OFFSET;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            fieldErrors.add(new ErrorResponse.FieldError("offset", "형식이 올바르지 않습니다."));
            return DEFAULT_OFFSET;
        }
    }

    private int parseLimit(List<String> values, List<ErrorResponse.FieldError> fieldErrors) {
        if (values == null) {
            return DEFAULT_LIMIT;
        }
        if (values.size() != 1) {
            fieldErrors.add(new ErrorResponse.FieldError("limit", "한 번만 지정할 수 있습니다."));
            return DEFAULT_LIMIT;
        }
        String value = values.getFirst();
        if (!isAsciiDecimal(value)) {
            fieldErrors.add(new ErrorResponse.FieldError("limit", "형식이 올바르지 않습니다."));
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) {
                fieldErrors.add(new ErrorResponse.FieldError("limit", "1 이상 100 이하여야 합니다."));
            }
            return limit;
        } catch (NumberFormatException e) {
            fieldErrors.add(new ErrorResponse.FieldError("limit", "형식이 올바르지 않습니다."));
            return DEFAULT_LIMIT;
        }
    }

    private void validateSnapshotPair(List<String> createdAtValues, List<String> auditIdValues,
                                      List<ErrorResponse.FieldError> fieldErrors) {
        if (createdAtValues == null && auditIdValues != null) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotCreatedAt", "스냅샷 필드는 함께 지정해야 합니다."));
        } else if (createdAtValues != null && auditIdValues == null) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotAuditId", "스냅샷 필드는 함께 지정해야 합니다."));
        }
    }

    private OffsetDateTime parseSnapshotCreatedAt(
            List<String> values, List<ErrorResponse.FieldError> fieldErrors) {
        if (values == null) {
            return null;
        }
        if (values.size() != 1) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotCreatedAt", "한 번만 지정할 수 있습니다."));
            return null;
        }
        try {
            return OffsetDateTime.parse(values.getFirst());
        } catch (DateTimeParseException | NullPointerException e) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotCreatedAt", "형식이 올바르지 않습니다."));
            return null;
        }
    }

    private Long parseSnapshotAuditId(
            List<String> values, List<ErrorResponse.FieldError> fieldErrors) {
        if (values == null) {
            return null;
        }
        if (values.size() != 1) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotAuditId", "한 번만 지정할 수 있습니다."));
            return null;
        }
        String value = values.getFirst();
        if (!isAsciiDecimal(value)) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotAuditId", "형식이 올바르지 않습니다."));
            return null;
        }
        try {
            long auditId = Long.parseLong(value);
            if (auditId < 1) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        "snapshotAuditId", "1 이상이어야 합니다."));
            }
            return auditId;
        } catch (NumberFormatException e) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    "snapshotAuditId", "형식이 올바르지 않습니다."));
            return null;
        }
    }

    private boolean isAsciiDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
