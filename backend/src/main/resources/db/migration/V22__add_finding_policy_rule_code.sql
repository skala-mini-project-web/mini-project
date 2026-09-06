ALTER TABLE findings
    ADD COLUMN policy_rule_code VARCHAR(40),
    ADD CONSTRAINT ck_findings_policy_rule_code CHECK (
        policy_rule_code IS NULL OR policy_rule_code IN (
            'RETURN_FRAMING',
            'LOSS_SOFTENING',
            'COST_OMISSION',
            'STABILITY_KEYWORD',
            'FORMAL_CONFIRMATION',
            'COGNITIVE_ACCESSIBILITY'
        )
    );
