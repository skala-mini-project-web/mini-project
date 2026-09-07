package com.crosschecklab.domain.document.batch;

public enum DocumentBatchStatus {
    PENDING,
    SUCCEEDED,
    CANCELLED,
    QUARANTINED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED || this == QUARANTINED;
    }
}
