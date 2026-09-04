-- 분석 재시도는 RUNNING 상태에서 이전 retrieval run/snapshot을 교체한다.
-- 완료·검토 상태의 snapshot은 갱신할 수 없고, analysis 삭제의 FK cascade는 허용한다.

CREATE OR REPLACE FUNCTION reject_analysis_rag_run_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'analysis_rag_runs records are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM analyses
        WHERE id = OLD.analysis_id
          AND status <> 'RUNNING'
    ) THEN
        RAISE EXCEPTION 'completed analysis_rag_runs records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION reject_analysis_rag_snapshot_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'analysis_rag_retrieval_snapshots records are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM analysis_rag_runs run
        JOIN analyses analysis ON analysis.id = run.analysis_id
        WHERE run.id = OLD.rag_run_id
          AND analysis.status <> 'RUNNING'
    ) THEN
        RAISE EXCEPTION 'completed analysis_rag_retrieval_snapshots records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;
