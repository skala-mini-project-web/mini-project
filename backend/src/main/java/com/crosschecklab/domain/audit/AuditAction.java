package com.crosschecklab.domain.audit;

public enum AuditAction {
    ANALYSIS_CREATED(AuditResourceType.ANALYSIS),
    ANALYSIS_RETRIED(AuditResourceType.ANALYSIS),
    ANALYSIS_COMPLETED(AuditResourceType.ANALYSIS),
    ANALYSIS_FAILED(AuditResourceType.ANALYSIS),
    REVIEW_CREATED(AuditResourceType.REVIEW),
    REVIEW_APPROVED(AuditResourceType.REVIEW),
    REVIEW_REJECTED(AuditResourceType.REVIEW),
    RISK_PATTERN_PROMOTED(AuditResourceType.RISK_PATTERN),
    RISK_PATTERN_UPDATED(AuditResourceType.RISK_PATTERN),
    RISK_PATTERN_ACTIVATED(AuditResourceType.RISK_PATTERN),
    GUARDFIT_ACTION_CREATED(AuditResourceType.GUARDFIT_ACTION),
    GUARDFIT_ACTION_UPDATED(AuditResourceType.GUARDFIT_ACTION),
    GUARDFIT_ACTION_APPROVED(AuditResourceType.GUARDFIT_ACTION);

    private final AuditResourceType resourceType;

    AuditAction(AuditResourceType resourceType) {
        this.resourceType = resourceType;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }
}
