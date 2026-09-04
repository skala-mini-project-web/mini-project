package com.crosschecklab.domain.groundtruth;

import com.crosschecklab.domain.groundtruth.dto.GroundTruthFactListResponse;
import com.crosschecklab.domain.groundtruth.dto.GroundTruthFactResponse;
import com.crosschecklab.domain.groundtruth.dto.GroundTruthFactVerificationRequest;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GroundTruthFactController {

    private final GroundTruthFactService groundTruthFactService;

    @GetMapping("/api/product-documents/{documentId}/ground-truth-facts")
    public GroundTruthFactListResponse list(@PathVariable Long documentId,
                                            @CurrentUser DemoUser currentUser) {
        return groundTruthFactService.list(documentId, currentUser);
    }

    @PutMapping("/api/ground-truth-facts/{factId}/verification")
    public GroundTruthFactResponse verify(@PathVariable Long factId,
                                          @Valid @RequestBody GroundTruthFactVerificationRequest request,
                                          @CurrentUser DemoUser currentUser) {
        return groundTruthFactService.verify(factId, request, currentUser);
    }
}
