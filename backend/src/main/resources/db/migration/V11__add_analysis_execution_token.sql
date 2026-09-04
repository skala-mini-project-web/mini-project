ALTER TABLE analyses
    ADD COLUMN execution_token VARCHAR(36) DEFAULT 'not-started';

UPDATE analyses
SET execution_token = 'legacy-' || CAST(id AS VARCHAR(19));

ALTER TABLE analyses
    ALTER COLUMN execution_token SET NOT NULL;
