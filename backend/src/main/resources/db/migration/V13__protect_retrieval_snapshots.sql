CREATE FUNCTION reject_analysis_rag_run_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        OR EXISTS (
            SELECT 1
            FROM analyses
            WHERE id = OLD.analysis_id
        )
    THEN
        RAISE EXCEPTION 'analysis_rag_runs records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_analysis_rag_runs_immutable
BEFORE UPDATE OR DELETE ON analysis_rag_runs
FOR EACH ROW
EXECUTE FUNCTION reject_analysis_rag_run_changes();

CREATE FUNCTION reject_analysis_rag_snapshot_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        OR EXISTS (
            SELECT 1
            FROM analysis_rag_runs
            WHERE id = OLD.rag_run_id
        )
    THEN
        RAISE EXCEPTION 'analysis_rag_retrieval_snapshots records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_analysis_rag_retrieval_snapshots_immutable
BEFORE UPDATE OR DELETE ON analysis_rag_retrieval_snapshots
FOR EACH ROW
EXECUTE FUNCTION reject_analysis_rag_snapshot_changes();
