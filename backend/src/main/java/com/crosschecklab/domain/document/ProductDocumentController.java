package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.dto.DocumentResponse;
import com.crosschecklab.domain.document.dto.DocumentUploadResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ResponseEntity<DocumentUploadResponse> upload(
            @PathVariable Long productId,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = SCENARIO_HEADER, required = false) String scenario,
            @CurrentUser DemoUser currentUser) {
        DocumentUploadResponse response = productDocumentService.upload(productId, file, scenario, currentUser);
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
}
