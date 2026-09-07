package com.crosschecklab.domain.document.batch;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentBatchRecoveryScheduler {

    private static final int RECOVERY_LIMIT = 100;
    private static final int BASE_BACKOFF_SECONDS = 15;
    private static final int MAX_BACKOFF_SECONDS = 600;

    private final DocumentBatchClaimRepository claimRepository;
    private final DocumentBatchWorker worker;
    private final Clock clock;

    /**
     * This tick is only a recovery scan and worker wake-up. It never claims pending rows itself;
     * ownership is established solely by the worker's fenced PostgreSQL claim.
     */
    @Scheduled(initialDelayString = "PT2S", fixedDelayString = "PT5S")
    public void recoverAndWakeUp() {
        try {
            int recovered = claimRepository.recoverExpiredLeases(
                    OffsetDateTime.now(clock),
                    RECOVERY_LIMIT,
                    BASE_BACKOFF_SECONDS,
                    MAX_BACKOFF_SECONDS);
            if (recovered > 0) {
                log.warn("Recovered {} expired document batch leases", recovered);
            }
        } catch (RuntimeException e) {
            log.error("Failed to recover expired document batch leases", e);
        } finally {
            worker.wakeUp();
        }
    }

    @PreDestroy
    void shutdownWorker() {
        worker.shutdown();
    }
}
