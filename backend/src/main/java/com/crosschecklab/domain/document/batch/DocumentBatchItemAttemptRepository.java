package com.crosschecklab.domain.document.batch;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface DocumentBatchItemAttemptRepository
        extends Repository<DocumentBatchItemAttempt, Long> {

    Optional<DocumentBatchItemAttempt> findByIdAndItem_Owner_Id(Long id, Long ownerId);

    Optional<DocumentBatchItemAttempt> findByItem_IdAndAttemptNo(Long itemId, int attemptNo);

    Page<DocumentBatchItemAttempt> findAllByItem_IdAndItem_Owner_IdOrderByAttemptNoDesc(
            Long itemId,
            Long ownerId,
            Pageable pageable
    );

    long countByItem_Batch_IdAndOutcome(Long batchId, String outcome);
}
