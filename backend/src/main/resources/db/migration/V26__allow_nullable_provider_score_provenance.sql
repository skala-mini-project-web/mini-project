ALTER TABLE analysis_executions
    DROP CONSTRAINT ck_analysis_executions_lifecycle,
    ADD CONSTRAINT ck_analysis_executions_lifecycle CHECK (
        (status = 'RUNNING'
            AND finished_at IS NULL
            AND error_code IS NULL
            AND retryable = FALSE
            AND provider_risk_score IS NULL
            AND model_version IS NULL
            AND prompt_version IS NULL)
        OR
        (status = 'SUCCEEDED'
            AND finished_at IS NOT NULL
            AND error_code IS NULL
            AND retryable = FALSE
            AND model_version IS NOT NULL
            AND btrim(model_version) <> ''
            AND prompt_version IS NOT NULL
            AND btrim(prompt_version) <> '')
        OR
        (status = 'FAILED'
            AND finished_at IS NOT NULL
            AND error_code IS NOT NULL
            AND btrim(error_code) <> ''
            AND provider_risk_score IS NULL
            AND model_version IS NULL
            AND prompt_version IS NULL)
        OR
        (status = 'DISCARDED'
            AND finished_at IS NOT NULL
            AND error_code IS NOT NULL
            AND btrim(error_code) <> ''
            AND retryable = FALSE
            AND provider_risk_score IS NULL
            AND model_version IS NULL
            AND prompt_version IS NULL)
    ) NOT VALID;
