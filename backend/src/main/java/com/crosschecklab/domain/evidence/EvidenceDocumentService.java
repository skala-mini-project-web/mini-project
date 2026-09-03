package com.crosschecklab.domain.evidence;

import com.crosschecklab.domain.evidence.dto.EvidenceDocumentResponse;
import com.crosschecklab.global.common.ListResponse;
import com.crosschecklab.global.common.enums.EvidenceSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvidenceDocumentService {

    private final EvidenceDocumentRepository evidenceDocumentRepository;

    // EVD-001. sourceType / active 는 둘 다 선택이며 null 이면 필터하지 않는다.
    public ListResponse<EvidenceDocumentResponse> findAll(EvidenceSourceType sourceType, Boolean active) {
        return ListResponse.of(
                evidenceDocumentRepository.search(sourceType, active),
                EvidenceDocumentResponse::from);
    }
}
