package com.crosschecklab.analysis.application;

import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic policy inputs and aggregation for document disclosure risk priority policy v1. */
public final class EvidenceRiskScorePolicyV1 {

    public static final String VERSION = "1.1.0";

    private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000);
    private static final Map<RedTeamRuleCode, Components> COMPONENTS = Map.of(
            RedTeamRuleCode.RETURN_FRAMING, new Components(8_000, 8_000),
            RedTeamRuleCode.LOSS_SOFTENING, new Components(10_000, 8_000),
            RedTeamRuleCode.COST_OMISSION, new Components(8_000, 8_000),
            RedTeamRuleCode.STABILITY_KEYWORD, new Components(10_000, 10_000),
            RedTeamRuleCode.FORMAL_CONFIRMATION, new Components(6_000, 6_000),
            RedTeamRuleCode.COGNITIVE_ACCESSIBILITY, new Components(6_000, 6_000));

    private EvidenceRiskScorePolicyV1() {
    }

    public static Components components(RedTeamRuleCode ruleCode) {
        return ruleCode == null ? null : COMPONENTS.get(ruleCode);
    }

    /** Applies exact basis-point noisy-OR and rounds only the final 0-100 value. */
    public static int aggregate(List<Integer> contributionBasisPoints) {
        Objects.requireNonNull(contributionBasisPoints, "contributionBasisPoints");
        BigDecimal remainingProbability = BigDecimal.ONE;
        for (Integer contribution : contributionBasisPoints) {
            if (contribution == null || contribution < 0 || contribution > 10_000) {
                throw new IllegalArgumentException("contribution must be between 0 and 10000 basis points");
            }
            remainingProbability = remainingProbability.multiply(
                    BigDecimal.valueOf(10_000L - contribution).divide(BASIS_POINTS));
        }
        return BigDecimal.ONE.subtract(remainingProbability)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    public record Components(int magnitudeBasisPoints, int likelihoodBasisPoints) {

        public int contributionBasisPoints() {
            return magnitudeBasisPoints * likelihoodBasisPoints / 10_000;
        }
    }
}
