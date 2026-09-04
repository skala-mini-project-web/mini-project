package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.dto.DocumentAcceptedResponse;
import com.crosschecklab.domain.document.dto.DocumentResponse;
import com.crosschecklab.domain.document.dto.DocumentTextUpdateRequest;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Document", description = "상품 설명서 업로드와 텍스트 추출")
@RestController
@RequiredArgsConstructor
public class ProductDocumentController {

    // 데모 진행자가 추출 시나리오를 직접 고를 때 쓰는 헤더. 없으면 파일명/기본값으로 정해진다.
    public static final String SCENARIO_HEADER = "X-Demo-Scenario";

    private final ProductDocumentService productDocumentService;

    @PostMapping(value = "/api/products/{productId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "DOC-001 설명서 업로드",
            description = "PDF/PPTX 만 허용하며 최대 10MB. 저장 즉시 202 를 반환하고 추출은 백그라운드에서 진행된다. "
                    + "파일 바이너리는 보관하지 않고 SHA-256 체크섬만 남긴다.")
    public ResponseEntity<DocumentAcceptedResponse> upload(
            @PathVariable Long productId,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = SCENARIO_HEADER, required = false) String scenario,
            @CurrentUser DemoUser currentUser) {
        DocumentAcceptedResponse response = productDocumentService.upload(productId, file, scenario, currentUser);
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .body(response);
    }

    @GetMapping("/api/documents/{documentId}")
    @Operation(summary = "DOC-002 문서 조회 (추출 상태 폴링)",
            description = "읽기 전용이다. 몇 번을 호출해도 extractStatus 가 바뀌지 않는다. "
                    + "소유자 본인 또는 COMPLIANCE_REVIEWER 만 조회할 수 있다.")
    public ResponseEntity<DocumentResponse> findById(@PathVariable Long documentId,
                                                     @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(productDocumentService.findById(documentId, currentUser));
    }

    @PatchMapping("/api/documents/{documentId}/text")
    @Operation(summary = "DOC-003 추출 텍스트 수정·확인",
            description = "READY 상태에서만 수정할 수 있다(아니면 409). confirmed=true 면 확인자와 확인 시각을 기록하고, "
                    + "false 면 확인을 해제한다. 상품 담당자 본인만 호출할 수 있다.")
    public ResponseEntity<DocumentResponse> updateText(@PathVariable Long documentId,
                                                       @Valid @RequestBody DocumentTextUpdateRequest request,
                                                       @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(productDocumentService.updateText(documentId, request, currentUser));
    }

    @PostMapping("/api/documents/{documentId}/retry")
    @Operation(summary = "DOC-004 추출 재시도",
            description = "FAILED 상태에서만 재시도할 수 있다(아니면 409). 즉시 202 를 반환하고 "
                    + "추출은 업로드와 같은 경로로 백그라운드에서 다시 진행된다.")
    public ResponseEntity<DocumentAcceptedResponse> retryExtraction(@PathVariable Long documentId,
                                                                    @CurrentUser DemoUser currentUser) {
        DocumentAcceptedResponse response = productDocumentService.retryExtraction(documentId, currentUser);
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .body(response);
    }
}
