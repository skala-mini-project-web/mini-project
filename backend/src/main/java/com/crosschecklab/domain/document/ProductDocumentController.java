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
    public static final String EXPECTED_RUN_HEADER = "X-Expected-Extraction-Run-Id";
    public static final String EXPECTED_TEXT_HASH_HEADER = "X-Expected-Text-Hash";

    private final ProductDocumentService productDocumentService;

    @PostMapping(value = "/api/products/{productId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "DOC-001 설명서 업로드",
            description = "PDF/PPTX 만 허용하며 최대 10MB. 저장 즉시 202 를 반환하고 추출은 백그라운드에서 진행된다. "
                    + "real-extraction 프로필은 원본을 content-addressed durable storage에 보관하고 "
                    + "불변 source revision·SHA-256 체크섬을 함께 기록한다.")
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
                    + "false 면 확인을 해제한다. V24 추출 실행이 있는 문서를 확인할 때는 현재 실행 ID와 먼저 저장한 "
                    + "텍스트 해시를 조건부 헤더로 보내야 한다. 상품 담당자 본인만 호출할 수 있다.")
    public ResponseEntity<DocumentResponse> updateText(@PathVariable Long documentId,
                                                       @Valid @RequestBody DocumentTextUpdateRequest request,
                                                       @RequestHeader(
                                                               value = EXPECTED_RUN_HEADER,
                                                               required = false) Long expectedRunId,
                                                       @RequestHeader(
                                                               value = EXPECTED_TEXT_HASH_HEADER,
                                                               required = false) String expectedTextHash,
                                                       @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(productDocumentService.updateText(
                documentId, request, expectedRunId, expectedTextHash, currentUser));
    }

    @GetMapping(value = "/api/documents/{documentId}/pages/{pageNumber}/render",
            produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "현재 OCR 페이지 렌더 조회",
            description = "현재 추출 실행에서 OCR에 사용한 페이지 이미지를 반환한다. 소유자와 검토자 모두 읽을 수 있다.")
    public ResponseEntity<byte[]> findCurrentPageRender(
            @PathVariable Long documentId,
            @PathVariable int pageNumber,
            @CurrentUser DemoUser currentUser
    ) {
        ProductDocumentService.RenderArtifact artifact =
                productDocumentService.findCurrentPageRender(documentId, pageNumber, currentUser);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .eTag("\"" + artifact.sha256() + "\"")
                .body(artifact.content());
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
