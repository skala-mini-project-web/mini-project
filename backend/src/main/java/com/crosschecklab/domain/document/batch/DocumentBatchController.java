package com.crosschecklab.domain.document.batch;

import com.crosschecklab.domain.document.batch.dto.CreateDocumentBatchResponse;
import com.crosschecklab.domain.document.batch.dto.DocumentBatchItemPageResponse;
import com.crosschecklab.domain.document.batch.dto.DocumentBatchItemResponse;
import com.crosschecklab.domain.document.batch.dto.DocumentBatchResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Document Batch", description = "상품 설명서 일괄 제출")
@Validated
@RestController
@RequiredArgsConstructor
public class DocumentBatchController {

    private static final String SCENARIO_HEADER = "X-Demo-Scenario";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final DocumentBatchService documentBatchService;

    @PostMapping(
            value = "/api/products/{productId}/document-batches",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "상품 설명서 일괄 제출",
            description = "PDF/PPTX 파일 1~100개를 durable batch queue에 원자적으로 등록하고 202를 반환한다.")
    public ResponseEntity<CreateDocumentBatchResponse> submit(
            @PathVariable Long productId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestHeader(value = SCENARIO_HEADER, required = false) String scenario,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @CurrentUser DemoUser currentUser
    ) {
        CreateDocumentBatchResponse response = documentBatchService.submit(
                productId, files, scenario, idempotencyKey, currentUser);
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .body(response);
    }

    @GetMapping("/api/document-batches/{batchId}")
    @Operation(summary = "문서 배치 상태 조회",
            description = "소유자 또는 COMPLIANCE_REVIEWER가 durable item row에서 집계한 상태를 조회한다.")
    public ResponseEntity<DocumentBatchResponse> findBatch(
            @PathVariable Long batchId,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(documentBatchService.findBatch(batchId, currentUser));
    }

    @GetMapping("/api/document-batches/{batchId}/items")
    @Operation(summary = "문서 배치 item 조회")
    public ResponseEntity<DocumentBatchItemPageResponse> findItems(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page 는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size 는 1 이상이어야 합니다.")
            @Max(value = 100, message = "size 는 100 을 넘을 수 없습니다.") int size,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(
                documentBatchService.findItems(batchId, page, size, currentUser));
    }

    @GetMapping("/api/document-batches/{batchId}/items/{itemId}")
    @Operation(summary = "문서 배치 item 상세 조회")
    public ResponseEntity<DocumentBatchItemResponse> findItem(
            @PathVariable Long batchId,
            @PathVariable Long itemId,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(
                documentBatchService.findItem(batchId, itemId, currentUser));
    }

    @PostMapping("/api/document-batches/{batchId}/cancel")
    @Operation(summary = "문서 배치 취소", description = "소유 PRODUCT_MANAGER 전용")
    public ResponseEntity<DocumentBatchResponse> cancelBatch(
            @PathVariable Long batchId,
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) ManagementActionRequest request,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(documentBatchService.cancelBatch(
                batchId,
                resolveReason(reason, request, "Cancelled by product manager."),
                currentUser));
    }

    @PostMapping("/api/document-batches/{batchId}/quarantine")
    @Operation(summary = "문서 배치 격리", description = "소유 PRODUCT_MANAGER 전용")
    public ResponseEntity<DocumentBatchResponse> quarantineBatch(
            @PathVariable Long batchId,
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) ManagementActionRequest request,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(documentBatchService.quarantineBatch(
                batchId,
                resolveReason(reason, request, "Quarantined by product manager."),
                currentUser));
    }

    @PostMapping("/api/document-batches/{batchId}/items/{itemId}/cancel")
    @Operation(summary = "문서 배치 item 취소", description = "소유 PRODUCT_MANAGER 전용")
    public ResponseEntity<DocumentBatchItemResponse> cancelItem(
            @PathVariable Long batchId,
            @PathVariable Long itemId,
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) ManagementActionRequest request,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(documentBatchService.cancelItem(
                batchId,
                itemId,
                resolveReason(reason, request, "Cancelled by product manager."),
                currentUser));
    }

    @PostMapping("/api/document-batches/{batchId}/items/{itemId}/quarantine")
    @Operation(summary = "문서 배치 item 격리", description = "소유 PRODUCT_MANAGER 전용")
    public ResponseEntity<DocumentBatchItemResponse> quarantineItem(
            @PathVariable Long batchId,
            @PathVariable Long itemId,
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) ManagementActionRequest request,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(documentBatchService.quarantineItem(
                batchId,
                itemId,
                resolveReason(reason, request, "Quarantined by product manager."),
                currentUser));
    }

    @PostMapping("/api/document-batches/{batchId}/items/{itemId}/retry")
    @Operation(summary = "격리/취소된 문서 배치 item 수동 재시도",
            description = "소유 PRODUCT_MANAGER 전용")
    public ResponseEntity<DocumentBatchItemResponse> retryItem(
            @PathVariable Long batchId,
            @PathVariable Long itemId,
            @CurrentUser DemoUser currentUser
    ) {
        return ResponseEntity.ok(
                documentBatchService.retryItem(batchId, itemId, currentUser));
    }

    @GetMapping(
            value = "/api/document-batches/{batchId}/error-report.csv",
            produces = "text/csv")
    @Operation(summary = "문서 배치 오류 CSV 보고서")
    public ResponseEntity<byte[]> errorReport(
            @PathVariable Long batchId,
            @CurrentUser DemoUser currentUser
    ) {
        byte[] report = documentBatchService.errorReport(batchId, currentUser);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"document-batch-" + batchId + "-errors.csv\"")
                .body(report);
    }

    private String resolveReason(
            String queryReason,
            ManagementActionRequest request,
            String defaultReason
    ) {
        if (queryReason != null) {
            return queryReason;
        }
        return request == null || request.reason() == null ? defaultReason : request.reason();
    }

    public record ManagementActionRequest(String reason) {
    }
}
