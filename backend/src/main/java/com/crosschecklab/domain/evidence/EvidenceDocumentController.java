package com.crosschecklab.domain.evidence;

import com.crosschecklab.domain.evidence.dto.EvidenceDocumentResponse;
import com.crosschecklab.global.common.ListResponse;
import com.crosschecklab.global.common.enums.EvidenceSourceType;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "EvidenceDocument", description = "근거 문서")
@RestController
@RequestMapping("/api/evidence-documents")
@RequiredArgsConstructor
public class EvidenceDocumentController {

    private final EvidenceDocumentService evidenceDocumentService;

    // 기준 데이터라 역할 제한은 없지만, 로그인한 데모 사용자만 조회할 수 있어야 하므로
    // @CurrentUser 를 받아 인증을 강제한다 (헤더가 없으면 401).
    @GetMapping
    @Operation(summary = "EVD-001 근거 문서 목록",
            description = "sourceType / active 는 선택 필터이며, 생략하면 전체를 반환한다.")
    public ResponseEntity<ListResponse<EvidenceDocumentResponse>> findAll(
            @RequestParam(required = false) EvidenceSourceType sourceType,
            @RequestParam(required = false) Boolean active,
            @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(evidenceDocumentService.findAll(sourceType, active));
    }
}
