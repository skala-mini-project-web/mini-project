package com.crosschecklab.analysis.application;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisExecution;
import com.crosschecklab.domain.analysis.AnalysisExecutionRepository;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingEvidenceAnchor;
import com.crosschecklab.domain.analysis.FindingEvidenceAnchorRepository;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.analysis.RiskScoreRun;
import com.crosschecklab.domain.analysis.RiskScoreRunRepository;
import com.crosschecklab.domain.review.FindingReviewDecision;
import com.crosschecklab.domain.review.FindingReviewDecisionRepository;
import com.crosschecklab.domain.review.Review;
import com.crosschecklab.domain.review.ReviewRepository;
import com.crosschecklab.global.common.enums.ReviewStatus;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates immutable, reviewer-gated risk priority score runs. */
@Service
public class EvidenceRiskScoreService {

    private static final String REASON_REVIEW_REJECTED = "REVIEW_REJECTED";
    private static final String REASON_NO_APPROVED_FINDING = "NO_APPROVED_FINDING";
    private static final String REASON_APPROVED_FINDING_MISSING = "APPROVED_FINDING_MISSING";
    private static final String REASON_FINDING_NOT_CURRENT = "APPROVED_FINDING_NOT_CURRENT";
    private static final String REASON_POLICY_RULE_MISSING = "POLICY_RULE_MISSING";
    private static final String REASON_ANCHOR_CARDINALITY = "REQUIRED_ANCHOR_CARDINALITY";

    private final RiskScoreRunRepository riskScoreRunRepository;
    private final AnalysisRepository analysisRepository;
    private final AnalysisExecutionRepository analysisExecutionRepository;
    private final ReviewRepository reviewRepository;
    private final FindingRepository findingRepository;
    private final FindingReviewDecisionRepository findingReviewDecisionRepository;
    private final FindingEvidenceAnchorRepository findingEvidenceAnchorRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public EvidenceRiskScoreService(
            RiskScoreRunRepository riskScoreRunRepository,
            AnalysisRepository analysisRepository,
            AnalysisExecutionRepository analysisExecutionRepository,
            ReviewRepository reviewRepository,
            FindingRepository findingRepository,
            FindingReviewDecisionRepository findingReviewDecisionRepository,
            FindingEvidenceAnchorRepository findingEvidenceAnchorRepository,
            EntityManager entityManager,
            Clock clock
    ) {
        this.riskScoreRunRepository = riskScoreRunRepository;
        this.analysisRepository = analysisRepository;
        this.analysisExecutionRepository = analysisExecutionRepository;
        this.reviewRepository = reviewRepository;
        this.findingRepository = findingRepository;
        this.findingReviewDecisionRepository = findingReviewDecisionRepository;
        this.findingEvidenceAnchorRepository = findingEvidenceAnchorRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public RiskScoreRun createPendingReview(AnalysisExecution execution) {
        requireSuccessfulExecution(execution);
        List<String> inputs = new ArrayList<>(List.of(
                "PENDING_REVIEW",
                EvidenceRiskScorePolicyV1.VERSION,
                execution.getId().toString()));
        findingRepository.findAllByAnalysisExecutionIdOrderByIdAsc(execution.getId())
                .forEach(finding -> {
                    inputs.add(finding.getId().toString());
                    inputs.add(finding.getPolicyRuleCode() == null
                            ? "<missing>"
                            : finding.getPolicyRuleCode().name());
                });
        findingEvidenceAnchorRepository
                .findAllByFindingAnalysisExecutionIdOrderByFindingIdAscIdAsc(execution.getId())
                .forEach(anchor -> addAnchorFingerprint(inputs, anchor));
        String fingerprint = fingerprint(inputs);
        return riskScoreRunRepository
                .findByAnalysisExecutionIdAndPolicyVersionAndInputFingerprint(
                        execution.getId(), EvidenceRiskScorePolicyV1.VERSION, fingerprint)
                .orElseGet(() -> riskScoreRunRepository.save(RiskScoreRun.pendingReview(
                        execution,
                        EvidenceRiskScorePolicyV1.VERSION,
                        fingerprint,
                        OffsetDateTime.now(clock))));
    }

    /** Locks the owning analysis so concurrent replays cannot create competing score runs. */
    @Transactional
    public RiskScoreRun scoreAfterReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("review does not exist: " + reviewId));
        Analysis analysis = analysisRepository.findWithLockById(review.getAnalysisId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "analysis does not exist: " + review.getAnalysisId()));
        AnalysisExecution execution = analysisExecutionRepository.findById(review.getAnalysisExecutionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "analysis execution does not exist: " + review.getAnalysisExecutionId()));
        requireSuccessfulExecution(execution);
        if (!Objects.equals(analysis.getCurrentSuccessfulExecutionId(), execution.getId())) {
            return createNotScored(execution, review, List.of(), REASON_FINDING_NOT_CURRENT);
        }

        List<FindingReviewDecision> allDecisions = new ArrayList<>();
        allDecisions.addAll(findingReviewDecisionRepository
                .findAllByReviewIdAndDecisionOrderByFindingRevisionIdAsc(
                        review.getId(), ReviewStatus.APPROVED));
        allDecisions.addAll(findingReviewDecisionRepository
                .findAllByReviewIdAndDecisionOrderByFindingRevisionIdAsc(
                        review.getId(), ReviewStatus.REJECTED));
        allDecisions.sort(Comparator.comparing(FindingReviewDecision::getFindingRevisionId));

        if (review.getStatus() == ReviewStatus.REJECTED) {
            return createNotScored(execution, review, allDecisions, REASON_REVIEW_REJECTED);
        }
        if (review.getStatus() != ReviewStatus.APPROVED) {
            throw new IllegalStateException("review must have a terminal decision before scoring");
        }

        List<FindingReviewDecision> approvals = allDecisions.stream()
                .filter(decision -> decision.getDecision() == ReviewStatus.APPROVED)
                .toList();
        if (approvals.isEmpty()) {
            return createNotScored(execution, review, allDecisions, REASON_NO_APPROVED_FINDING);
        }

        List<Long> approvedFindingIds = approvals.stream()
                .map(FindingReviewDecision::getFindingRevisionId)
                .toList();
        List<Finding> allFindings =
                findingRepository.findAllByAnalysisExecutionIdOrderByIdAsc(execution.getId());
        List<Finding> findings = findingRepository
                .findAllByAnalysisExecutionIdAndIdInOrderByIdAsc(execution.getId(), approvedFindingIds);
        if (findings.size() != approvals.size()) {
            return createNotScored(
                    execution, review, allDecisions, REASON_APPROVED_FINDING_MISSING);
        }

        Map<Long, FindingReviewDecision> approvalsByFindingId = new LinkedHashMap<>();
        for (FindingReviewDecision approval : approvals) {
            approvalsByFindingId.put(approval.getFindingRevisionId(), approval);
        }
        Map<Long, List<FindingEvidenceAnchor>> anchorsByFindingId = new LinkedHashMap<>();
        List<FindingEvidenceAnchor> allAnchors = findingEvidenceAnchorRepository
                .findAllByFindingAnalysisExecutionIdOrderByFindingIdAscIdAsc(execution.getId());
        for (FindingEvidenceAnchor anchor : allAnchors) {
            anchorsByFindingId.computeIfAbsent(anchor.getFinding().getId(), ignored -> new ArrayList<>())
                    .add(anchor);
        }

        List<EligibleFinding> eligibleFindings = new ArrayList<>();
        String failureReason = null;
        for (Finding finding : findings) {
            boolean eligible = true;
            if (hasSuccessor(finding)) {
                if (failureReason == null) {
                    failureReason = REASON_FINDING_NOT_CURRENT;
                }
                eligible = false;
            }
            EvidenceRiskScorePolicyV1.Components components =
                    EvidenceRiskScorePolicyV1.components(finding.getPolicyRuleCode());
            if (components == null) {
                if (failureReason == null) {
                    failureReason = REASON_POLICY_RULE_MISSING;
                }
                eligible = false;
            }
            List<FindingEvidenceAnchor> anchors = anchorsByFindingId.getOrDefault(finding.getId(), List.of());
            List<FindingEvidenceAnchor> documentClaims = anchors.stream()
                    .filter(anchor -> anchor.getSourceRole()
                            == FindingEvidenceAnchor.SourceRole.DOCUMENT_CLAIM)
                    .toList();
            List<FindingEvidenceAnchor> policyRequirements = anchors.stream()
                    .filter(anchor -> anchor.getSourceRole()
                            == FindingEvidenceAnchor.SourceRole.POLICY_REQUIREMENT)
                    .toList();
            Map<FindingEvidenceAnchor.DocumentClaimIdentity, List<FindingEvidenceAnchor>>
                    documentClaimsByIdentity = new LinkedHashMap<>();
            for (FindingEvidenceAnchor documentClaim : documentClaims) {
                documentClaimsByIdentity
                        .computeIfAbsent(documentClaim.documentClaimIdentity(), ignored -> new ArrayList<>())
                        .add(documentClaim);
            }
            if (documentClaimsByIdentity.size() != 1 || policyRequirements.isEmpty()) {
                if (failureReason == null) {
                    failureReason = REASON_ANCHOR_CARDINALITY;
                }
                eligible = false;
            }
            if (!eligible) {
                continue;
            }
            List<FindingEvidenceAnchor> equivalentDocumentClaims =
                    documentClaimsByIdentity.values().iterator().next();
            FindingEvidenceAnchor canonicalDocumentClaim = equivalentDocumentClaims.stream()
                    .min(Comparator.comparing(FindingEvidenceAnchor::getId))
                    .orElseThrow();
            FindingEvidenceAnchor canonicalPolicyRequirement = policyRequirements.stream()
                    .min(Comparator.comparing(FindingEvidenceAnchor::getId))
                    .orElseThrow();
            eligibleFindings.add(new EligibleFinding(
                    finding,
                    approvalsByFindingId.get(finding.getId()),
                    canonicalDocumentClaim,
                    canonicalPolicyRequirement,
                    components));
        }

        if (failureReason != null) {
            return createNotScored(execution, review, allDecisions, failureReason);
        }

        String inputFingerprint = scoreFingerprint(
                execution, review, allDecisions, allFindings, allAnchors);
        RiskScoreRun existing = findExisting(execution, inputFingerprint);
        if (existing != null) {
            return existing;
        }

        Map<HarmEventKey, EligibleFinding> representatives = new LinkedHashMap<>();
        for (EligibleFinding eligible : eligibleFindings) {
            HarmEventKey key = new HarmEventKey(
                    eligible.documentClaim().documentClaimIdentity(),
                    eligible.finding().getPolicyRuleCode().name());
            representatives.merge(
                    key,
                    eligible,
                    (current, candidate) -> current.finding().getId() <= candidate.finding().getId()
                            ? current
                            : candidate);
        }
        List<EligibleFinding> distinctHarmEvents = representatives.values().stream()
                .sorted(Comparator.comparing(eligible -> eligible.finding().getId()))
                .toList();
        List<Integer> contributions = distinctHarmEvents.stream()
                .map(eligible -> eligible.components().contributionBasisPoints())
                .toList();
        int score = EvidenceRiskScorePolicyV1.aggregate(contributions);
        OffsetDateTime now = OffsetDateTime.now(clock);
        RiskScoreRun run = RiskScoreRun.scored(
                execution,
                EvidenceRiskScorePolicyV1.VERSION,
                inputFingerprint,
                score,
                now,
                now);
        for (EligibleFinding eligible : distinctHarmEvents) {
            EvidenceRiskScorePolicyV1.Components components = eligible.components();
            run.addLedgerEntry(
                    eligible.finding(),
                    eligible.approval(),
                    eligible.documentClaim(),
                    eligible.canonicalPolicyRequirement(),
                    eligible.finding().getPolicyRuleCode().name(),
                    components.magnitudeBasisPoints(),
                    components.likelihoodBasisPoints(),
                    components.contributionBasisPoints(),
                    now);
        }
        RiskScoreRun saved = riskScoreRunRepository.save(run);
        mirrorAuthoritativeScore(analysis.getId(), score);
        return saved;
    }

    private RiskScoreRun createNotScored(
            AnalysisExecution execution,
            Review review,
            List<FindingReviewDecision> decisions,
            String reason
    ) {
        List<String> inputs = new ArrayList<>();
        inputs.add("NOT_SCORED");
        inputs.add(EvidenceRiskScorePolicyV1.VERSION);
        inputs.add(reason);
        addFullInputProvenance(
                inputs,
                execution,
                review,
                decisions,
                findingRepository.findAllByAnalysisExecutionIdOrderByIdAsc(execution.getId()),
                findingEvidenceAnchorRepository
                        .findAllByFindingAnalysisExecutionIdOrderByFindingIdAscIdAsc(execution.getId()));
        String inputFingerprint = fingerprint(inputs);
        RiskScoreRun existing = findExisting(execution, inputFingerprint);
        if (existing != null) {
            return existing;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        RiskScoreRun saved = riskScoreRunRepository.save(RiskScoreRun.notScored(
                execution,
                EvidenceRiskScorePolicyV1.VERSION,
                inputFingerprint,
                reason,
                now,
                now));
        mirrorAuthoritativeScore(review.getAnalysisId(), null);
        return saved;
    }

    private String scoreFingerprint(
            AnalysisExecution execution,
            Review review,
            List<FindingReviewDecision> decisions,
            List<Finding> findings,
            List<FindingEvidenceAnchor> anchors
    ) {
        List<String> inputs = new ArrayList<>();
        inputs.add("SCORED");
        inputs.add(EvidenceRiskScorePolicyV1.VERSION);
        addFullInputProvenance(inputs, execution, review, decisions, findings, anchors);
        return fingerprint(inputs);
    }

    private void addFullInputProvenance(
            List<String> inputs,
            AnalysisExecution execution,
            Review review,
            List<FindingReviewDecision> decisions,
            List<Finding> findings,
            List<FindingEvidenceAnchor> anchors
    ) {
        inputs.add("EXECUTION");
        inputs.add(execution.getId().toString());
        inputs.add(nullable(execution.getProviderRiskScore()));
        inputs.add(nullable(execution.getModelVersion()));
        inputs.add(nullable(execution.getPromptVersion()));
        inputs.add(execution.getRetrievalVersion());
        inputs.add("REVIEW");
        inputs.add(review.getId().toString());
        inputs.add(review.getStatus().name());
        inputs.add(nullable(review.getReviewerId()));
        inputs.add(nullable(review.getSubmissionComment()));
        inputs.add(nullable(review.getComment()));
        // Durable review and decision IDs already bind their timestamp metadata. Hashing raw
        // OffsetDateTime strings makes managed and PostgreSQL-reloaded representations diverge.
        inputs.add("SELECTED_FINDINGS");
        inputs.add(Integer.toString(review.getSelectedFindingIds().size()));
        review.getSelectedFindingIds().stream()
                .sorted()
                .forEach(findingId -> inputs.add(findingId.toString()));
        inputs.add("DECISIONS");
        inputs.add(Integer.toString(decisions.size()));
        for (FindingReviewDecision decision : decisions) {
            inputs.add(decision.getId().toString());
            inputs.add(decision.getFindingRevisionId().toString());
            inputs.add(decision.getDecision().name());
            inputs.add(decision.getReviewerId().toString());
            inputs.add(nullable(decision.getComment()));
        }
        inputs.add("FINDINGS");
        inputs.add(Integer.toString(findings.size()));
        for (Finding finding : findings) {
            inputs.add(finding.getId().toString());
            inputs.add(finding.getLineageId());
            inputs.add(finding.getRevisionNumber().toString());
            inputs.add(finding.getSupersedesFinding() == null
                    ? "<none>"
                    : finding.getSupersedesFinding().getId().toString());
            inputs.add(finding.getStatement());
            inputs.add(finding.getSeverity().name());
            inputs.add(finding.getPolicyRuleCode() == null
                    ? "<missing>"
                    : finding.getPolicyRuleCode().name());
            inputs.add(nullable(finding.getRecommendation()));
            inputs.add(Integer.toString(finding.getAffectedPersonaTemplateIds().size()));
            finding.getAffectedPersonaTemplateIds().stream()
                    .sorted()
                    .forEach(personaId -> inputs.add(personaId.toString()));
        }
        inputs.add("ANCHORS");
        inputs.add(Integer.toString(anchors.size()));
        anchors.forEach(anchor -> addAnchorFingerprint(inputs, anchor));
    }

    private void addAnchorFingerprint(List<String> inputs, FindingEvidenceAnchor anchor) {
        inputs.add(anchor.getId().toString());
        inputs.add(anchor.getFinding().getId().toString());
        inputs.add(anchor.getSourceRole().name());
        inputs.add(nullable(anchor.getSourceDocumentId()));
        inputs.add(nullable(anchor.getSourceRevisionId()));
        inputs.add(nullable(anchor.getEvidenceDocumentId()));
        inputs.add(nullable(anchor.getRetrievedChunkId()));
        inputs.add(anchor.getSourceHash());
        inputs.add(Integer.toString(anchor.getPageNumber()));
        inputs.add(Long.toString(anchor.getUtf8StartOffset()));
        inputs.add(Long.toString(anchor.getUtf8EndOffset()));
        inputs.add(anchor.getExcerptHash());
        inputs.add(anchor.getExactExcerpt());
    }

    private static String nullable(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private boolean hasSuccessor(Finding finding) {
        return entityManager.createQuery(
                        "select count(successor) from Finding successor "
                                + "where successor.supersedesFinding.id = :findingId",
                        Long.class)
                .setParameter("findingId", finding.getId())
                .getSingleResult() > 0;
    }

    private RiskScoreRun findExisting(AnalysisExecution execution, String fingerprint) {
        return riskScoreRunRepository
                .findByAnalysisExecutionIdAndPolicyVersionAndInputFingerprint(
                        execution.getId(), EvidenceRiskScorePolicyV1.VERSION, fingerprint)
                .orElse(null);
    }

    private void mirrorAuthoritativeScore(Long analysisId, Integer score) {
        entityManager.createQuery("update Analysis analysis set analysis.riskScore = :score where analysis.id = :id")
                .setParameter("score", score)
                .setParameter("id", analysisId)
                .executeUpdate();
    }

    private void requireSuccessfulExecution(AnalysisExecution execution) {
        if (execution == null || execution.getId() == null || !execution.isSucceeded()) {
            throw new IllegalArgumentException("risk scoring requires a persisted successful execution");
        }
    }

    private static String fingerprint(List<String> inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String input : inputs) {
                byte[] bytes = Objects.requireNonNull(input, "fingerprint input")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record EligibleFinding(
            Finding finding,
            FindingReviewDecision approval,
            FindingEvidenceAnchor documentClaim,
            FindingEvidenceAnchor canonicalPolicyRequirement,
            EvidenceRiskScorePolicyV1.Components components
    ) {
    }

    private record HarmEventKey(
            FindingEvidenceAnchor.DocumentClaimIdentity documentClaimIdentity,
            String policyRuleId
    ) {
    }
}
