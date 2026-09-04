package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 추출 상태 전이만 담당한다.
// 전이마다 독립 트랜잭션(REQUIRES_NEW)으로 커밋해야 폴링 중인 클라이언트가 중간 상태를 볼 수 있고,
// 추출이 실패해도 앞선 전이가 함께 롤백되지 않는다.
// 별도 빈으로 분리한 이유는 같은 클래스 안에서 호출하면 프록시를 타지 않아 전파 설정이 무시되기 때문이다.
@Component
@RequiredArgsConstructor
public class DocumentExtractionTransitions {

    private final ProductDocumentRepository productDocumentRepository;

    // 문서가 이미 지워졌을 수 있으므로 Optional 로 돌려준다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExtractionTarget> beginExtraction(Long documentId) {
        return productDocumentRepository.findById(documentId)
                .map(document -> {
                    document.markExtracting();
                    return ExtractionTarget.from(document);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeExtraction(Long documentId, String extractedText) {
        productDocumentRepository.findById(documentId)
                .ifPresent(document -> document.markReady(extractedText));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failExtraction(Long documentId) {
        productDocumentRepository.findById(documentId)
                .ifPresent(ProductDocument::markFailed);
    }
}
