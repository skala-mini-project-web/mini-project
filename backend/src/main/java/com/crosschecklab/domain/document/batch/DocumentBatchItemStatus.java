package com.crosschecklab.domain.document.batch;

public enum DocumentBatchItemStatus {
    PENDING,
    LEASED,
    RETRY_WAIT,
    SUCCEEDED,
    CANCELLED,
    QUARANTINED;

    public boolean isClaimable() {
        return this == PENDING || this == RETRY_WAIT;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED || this == QUARANTINED;
    }
}
