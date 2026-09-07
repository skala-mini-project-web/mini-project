--
-- PostgreSQL database dump
--

\restrict NLSxFJd9efHarbbtNFzkY6EvMQ9bEP6X3NGibyrRB0oXutA5Qjb1IbtP2QNSdjC

-- Dumped from database version 16.15 (Debian 16.15-1.pgdg12+2)
-- Dumped by pg_dump version 16.15 (Debian 16.15-1.pgdg12+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: guard_document_batch_item_attempt_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_document_batch_item_attempt_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'document_batch_item_attempts cannot be deleted';
    END IF;
    IF OLD.ended_at IS NOT NULL
       OR NEW.item_id <> OLD.item_id
       OR NEW.attempt_no <> OLD.attempt_no
       OR NEW.lease_fence <> OLD.lease_fence
       OR NEW.worker_owner <> OLD.worker_owner
       OR NEW.started_at <> OLD.started_at
       OR NEW.ended_at IS NULL THEN
        RAISE EXCEPTION 'document_batch_item_attempts can only be completed once';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: reject_analysis_execution_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_analysis_execution_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'analysis_executions records cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal analysis_executions records are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.analysis_id IS DISTINCT FROM OLD.analysis_id
        OR NEW.attempt_no IS DISTINCT FROM OLD.attempt_no
        OR NEW.execution_token IS DISTINCT FROM OLD.execution_token
        OR NEW.retrieval_version IS DISTINCT FROM OLD.retrieval_version
        OR NEW.started_at IS DISTINCT FROM OLD.started_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.status = 'RUNNING'
    THEN
        RAISE EXCEPTION 'analysis_executions execution boundary is immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: reject_analysis_rag_run_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_analysis_rag_run_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'analysis_rag_runs records are immutable'
        USING ERRCODE = '55000';
END;
$$;


--
-- Name: reject_analysis_rag_snapshot_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_analysis_rag_snapshot_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'analysis_rag_retrieval_snapshots records are immutable'
        USING ERRCODE = '55000';
END;
$$;


--
-- Name: reject_audit_event_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_audit_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$;


--
-- Name: reject_document_extraction_page_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_document_extraction_page_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        OR EXISTS (
            SELECT 1
            FROM document_extraction_runs
            WHERE id = OLD.extraction_run_id
        )
    THEN
        RAISE EXCEPTION 'document_extraction_pages records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;


--
-- Name: reject_document_extraction_run_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_document_extraction_run_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        OR EXISTS (
            SELECT 1
            FROM product_documents
            WHERE id = OLD.product_document_id
        )
    THEN
        RAISE EXCEPTION 'document_extraction_runs records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;


--
-- Name: reject_document_source_revision_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_document_source_revision_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        OR EXISTS (
            SELECT 1
            FROM product_documents
            WHERE id = OLD.product_document_id
        )
    THEN
        RAISE EXCEPTION 'document_source_revisions records are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END;
$$;


--
-- Name: reject_execution_bound_finding_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_execution_bound_finding_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.analysis_execution_id IS NOT NULL THEN
        RAISE EXCEPTION 'execution-bound finding revisions are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE'
        AND (NEW.analysis_execution_id IS NOT NULL
            OR NEW.lineage_id IS NOT NULL
            OR NEW.revision_number IS NOT NULL
            OR NEW.supersedes_finding_id IS NOT NULL)
    THEN
        RAISE EXCEPTION 'legacy findings cannot be assigned a revision identity'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RETURN NEW;
    END IF;

    RETURN OLD;
END;
$$;


--
-- Name: reject_finding_evidence_anchor_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_finding_evidence_anchor_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'finding_evidence_anchors records are immutable'
        USING ERRCODE = '55000';
END;
$$;


--
-- Name: reject_finding_review_decision_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_finding_review_decision_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'finding_review_decisions records are append-only'
        USING ERRCODE = '55000';
END;
$$;


--
-- Name: reject_risk_score_record_changes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_risk_score_record_changes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION '% records are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;


--
-- Name: validate_finding_evidence_anchor(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_finding_evidence_anchor() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM findings
        WHERE id = NEW.finding_id
          AND analysis_execution_id IS NOT NULL
          AND lineage_id IS NOT NULL
          AND revision_number IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'evidence anchors require an execution-bound finding revision'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_finding_revision(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_finding_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    predecessor findings%ROWTYPE;
BEGIN
    IF NEW.analysis_execution_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.revision_number = 1 THEN
        RETURN NEW;
    END IF;

    SELECT * INTO predecessor
    FROM findings
    WHERE id = NEW.supersedes_finding_id;

    IF NOT FOUND
        OR predecessor.analysis_execution_id IS NULL
        OR predecessor.analysis_id <> NEW.analysis_id
        OR predecessor.lineage_id <> NEW.lineage_id
        OR predecessor.revision_number + 1 <> NEW.revision_number
    THEN
        RAISE EXCEPTION 'finding revision must immediately supersede the same execution-bound lineage'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_review_execution_boundary(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_review_execution_boundary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.analysis_execution_id IS NULL THEN
        RAISE EXCEPTION 'new reviews require an analysis execution boundary'
            USING ERRCODE = '23502';
    END IF;

    IF TG_OP = 'INSERT' AND NOT EXISTS (
        SELECT 1
        FROM analyses
        WHERE id = NEW.analysis_id
          AND current_successful_execution_id = NEW.analysis_execution_id
    ) THEN
        RAISE EXCEPTION 'a new review must bind the current successful execution'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE'
        AND NEW.analysis_execution_id IS DISTINCT FROM OLD.analysis_execution_id
    THEN
        RAISE EXCEPTION 'a review execution boundary is immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_risk_score_ledger_entry(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_risk_score_ledger_entry() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    run_execution_id BIGINT;
    run_created_at TIMESTAMPTZ;
    run_completed_at TIMESTAMPTZ;
BEGIN
    SELECT analysis_execution_id, created_at, completed_at
    INTO run_execution_id, run_created_at, run_completed_at
    FROM risk_score_runs
    WHERE id = NEW.risk_score_run_id
      AND state = 'SCORED';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'ledger entries require a scored risk score run'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.created_at < run_created_at OR NEW.created_at > run_completed_at THEN
        RAISE EXCEPTION 'ledger entry timestamp must be within the score run time range'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM findings f
        WHERE f.id = NEW.finding_revision_id
          AND f.analysis_execution_id = run_execution_id
          AND f.lineage_id IS NOT NULL
          AND f.revision_number IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM findings successor
              WHERE successor.supersedes_finding_id = f.id
          )
    ) THEN
        RAISE EXCEPTION 'ledger finding must be a current revision from the score run execution'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM finding_review_decisions decision
        WHERE decision.id = NEW.finding_review_decision_id
          AND decision.analysis_execution_id = run_execution_id
          AND decision.finding_revision_id = NEW.finding_revision_id
          AND decision.decision = 'APPROVED'
    ) THEN
        RAISE EXCEPTION 'ledger finding must have the referenced reviewer approval'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM finding_evidence_anchors
        WHERE id = NEW.document_claim_anchor_id
          AND finding_id = NEW.finding_revision_id
          AND source_role = 'DOCUMENT_CLAIM'
    ) THEN
        RAISE EXCEPTION 'document claim anchor must belong to the ledger finding'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM finding_evidence_anchors
        WHERE id = NEW.policy_requirement_anchor_id
          AND finding_id = NEW.finding_revision_id
          AND source_role = 'POLICY_REQUIREMENT'
    ) THEN
        RAISE EXCEPTION 'policy requirement anchor must belong to the ledger finding'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: validate_risk_score_run(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_risk_score_run() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM analysis_executions
        WHERE id = NEW.analysis_execution_id
          AND status = 'SUCCEEDED'
    ) THEN
        RAISE EXCEPTION 'risk score runs require a successful analysis execution'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: analyses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analyses (
    id bigint NOT NULL,
    product_document_id bigint NOT NULL,
    red_team_pack_id bigint NOT NULL,
    status character varying(20) DEFAULT 'CREATED'::character varying NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    risk_score integer,
    model_version character varying(50),
    prompt_version character varying(50),
    requires_human_approval boolean DEFAULT true NOT NULL,
    retryable boolean DEFAULT false NOT NULL,
    error_code character varying(60),
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    input_hash character varying(64) NOT NULL,
    execution_token character varying(36) DEFAULT 'not-started'::character varying NOT NULL,
    current_successful_execution_id bigint,
    CONSTRAINT ck_analyses_progress CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT ck_analyses_risk_score CHECK (((risk_score IS NULL) OR ((risk_score >= 0) AND (risk_score <= 100)))),
    CONSTRAINT ck_analyses_status CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'IN_REVIEW'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: COLUMN analyses.input_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.analyses.input_hash IS '분석 입력 지문 SHA-256 hex. confirmed extracted_text + red_team_pack_id + 정렬된 persona_id/evidence_document_id 목록을 서버가 정규화·해시한다.';


--
-- Name: COLUMN analyses.current_successful_execution_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.analyses.current_successful_execution_id IS 'Mutable projection pointer set only after a successful execution and all of its evidence are persisted.';


--
-- Name: analyses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.analyses ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.analyses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: analysis_evidence_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analysis_evidence_documents (
    analysis_id bigint NOT NULL,
    evidence_document_id bigint NOT NULL
);


--
-- Name: analysis_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analysis_executions (
    id bigint NOT NULL,
    analysis_id bigint NOT NULL,
    attempt_no integer NOT NULL,
    execution_token character varying(36) NOT NULL,
    status character varying(20) NOT NULL,
    error_code character varying(60),
    retryable boolean DEFAULT false NOT NULL,
    retrieval_version character varying(100) NOT NULL,
    provider_risk_score integer,
    model_version character varying(50),
    prompt_version character varying(50),
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_analysis_executions_attempt CHECK ((attempt_no > 0)),
    CONSTRAINT ck_analysis_executions_finished_at CHECK (((finished_at IS NULL) OR (finished_at >= started_at))),
    CONSTRAINT ck_analysis_executions_lifecycle CHECK (((((status)::text = 'RUNNING'::text) AND (finished_at IS NULL) AND (error_code IS NULL) AND (retryable = false) AND (provider_risk_score IS NULL) AND (model_version IS NULL) AND (prompt_version IS NULL)) OR (((status)::text = 'SUCCEEDED'::text) AND (finished_at IS NOT NULL) AND (error_code IS NULL) AND (retryable = false) AND (provider_risk_score IS NOT NULL) AND (model_version IS NOT NULL) AND (btrim((model_version)::text) <> ''::text) AND (prompt_version IS NOT NULL) AND (btrim((prompt_version)::text) <> ''::text)) OR (((status)::text = 'FAILED'::text) AND (finished_at IS NOT NULL) AND (error_code IS NOT NULL) AND (btrim((error_code)::text) <> ''::text) AND (provider_risk_score IS NULL) AND (model_version IS NULL) AND (prompt_version IS NULL)) OR (((status)::text = 'DISCARDED'::text) AND (finished_at IS NOT NULL) AND (error_code IS NOT NULL) AND (btrim((error_code)::text) <> ''::text) AND (retryable = false) AND (provider_risk_score IS NULL) AND (model_version IS NULL) AND (prompt_version IS NULL)))),
    CONSTRAINT ck_analysis_executions_retrieval_version CHECK ((btrim((retrieval_version)::text) <> ''::text)),
    CONSTRAINT ck_analysis_executions_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'DISCARDED'::character varying])::text[]))),
    CONSTRAINT ck_analysis_executions_token CHECK ((btrim((execution_token)::text) <> ''::text))
);


--
-- Name: TABLE analysis_executions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.analysis_executions IS 'One durable execution attempt. A retry appends a new attempt instead of replacing evidence.';


--
-- Name: COLUMN analysis_executions.provider_risk_score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.analysis_executions.provider_risk_score IS 'Opaque score returned by the provider; no score is derived at this boundary.';


--
-- Name: analysis_executions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.analysis_executions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.analysis_executions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: analysis_ground_truth_fact_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analysis_ground_truth_fact_snapshots (
    id bigint NOT NULL,
    analysis_id bigint NOT NULL,
    ground_truth_fact_id bigint NOT NULL,
    label character varying(255) NOT NULL,
    value text NOT NULL
);


--
-- Name: TABLE analysis_ground_truth_fact_snapshots; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.analysis_ground_truth_fact_snapshots IS '분석 수락 시점의 검증된 공식 사실 label/value 불변 복사본.';


--
-- Name: analysis_ground_truth_fact_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.analysis_ground_truth_fact_snapshots ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.analysis_ground_truth_fact_snapshots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: analysis_personas; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analysis_personas (
    analysis_id bigint NOT NULL,
    persona_template_id bigint NOT NULL
);


--
-- Name: analysis_rag_retrieval_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analysis_rag_retrieval_snapshots (
    id bigint NOT NULL,
    rag_run_id bigint NOT NULL,
    rank integer NOT NULL,
    evidence_document_id bigint NOT NULL,
    evidence_document_chunk_id bigint NOT NULL,
    source_hash character varying(64) NOT NULL,
    chunk_hash character varying(64) NOT NULL,
    chunk_text text NOT NULL,
    similarity double precision NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_analysis_rag_snapshots_chunk_hash CHECK (((chunk_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_analysis_rag_snapshots_rank CHECK ((rank > 0)),
    CONSTRAINT ck_analysis_rag_snapshots_similarity CHECK (((similarity >= ('-1.0'::numeric)::double precision) AND (similarity <= (1.0)::double precision))),
    CONSTRAINT ck_analysis_rag_snapshots_source_hash CHECK (((source_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_analysis_rag_snapshots_text CHECK ((btrim(chunk_text) <> ''::text))
);


--
-- Name: TABLE analysis_rag_retrieval_snapshots; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.analysis_rag_retrieval_snapshots IS 'Immutable ranked copies of retrieved evidence chunks used by an analysis.';


--
-- Name: COLUMN analysis_rag_retrieval_snapshots.similarity; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.analysis_rag_retrieval_snapshots.similarity IS 'Cosine similarity recorded at retrieval time; larger values are more similar.';


--
-- Name: analysis_rag_retrieval_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.analysis_rag_retrieval_snapshots ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.analysis_rag_retrieval_snapshots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: analysis_rag_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analysis_rag_runs (
    id bigint NOT NULL,
    analysis_id bigint,
    query_hash character varying(64) NOT NULL,
    embedding_model character varying(255) NOT NULL,
    retrieval_version character varying(100) NOT NULL,
    requested_result_count integer NOT NULL,
    retrieved_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    analysis_execution_id bigint,
    CONSTRAINT ck_analysis_rag_runs_embedding_model CHECK ((btrim((embedding_model)::text) <> ''::text)),
    CONSTRAINT ck_analysis_rag_runs_execution_boundary CHECK ((((analysis_execution_id IS NOT NULL) AND (analysis_id IS NULL)) OR ((analysis_execution_id IS NULL) AND (analysis_id IS NOT NULL)))),
    CONSTRAINT ck_analysis_rag_runs_query_hash CHECK (((query_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_analysis_rag_runs_requested_result_count CHECK ((requested_result_count > 0)),
    CONSTRAINT ck_analysis_rag_runs_retrieval_version CHECK ((btrim((retrieval_version)::text) <> ''::text))
);


--
-- Name: TABLE analysis_rag_runs; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.analysis_rag_runs IS 'Immutable retrieval configuration and query fingerprint captured once per analysis.';


--
-- Name: COLUMN analysis_rag_runs.analysis_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.analysis_rag_runs.analysis_id IS 'Legacy owner retained only for pre-execution-boundary rows; it is never used to infer an execution.';


--
-- Name: COLUMN analysis_rag_runs.analysis_execution_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.analysis_rag_runs.analysis_execution_id IS 'Immutable execution boundary for newly captured retrieval runs.';


--
-- Name: analysis_rag_runs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.analysis_rag_runs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.analysis_rag_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_events (
    audit_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    trace_id character varying(64) NOT NULL,
    actor_id bigint,
    action character varying(32) NOT NULL,
    resource_type character varying(20) NOT NULL,
    resource_id bigint NOT NULL,
    resource_label character varying(255),
    analysis_id bigint,
    CONSTRAINT ck_audit_events_action CHECK (((action)::text = ANY ((ARRAY['ANALYSIS_CREATED'::character varying, 'ANALYSIS_RETRIED'::character varying, 'ANALYSIS_COMPLETED'::character varying, 'ANALYSIS_FAILED'::character varying, 'REVIEW_CREATED'::character varying, 'REVIEW_APPROVED'::character varying, 'REVIEW_REJECTED'::character varying, 'RISK_PATTERN_PROMOTED'::character varying, 'RISK_PATTERN_UPDATED'::character varying, 'RISK_PATTERN_ACTIVATED'::character varying, 'GUARDFIT_ACTION_CREATED'::character varying, 'GUARDFIT_ACTION_UPDATED'::character varying, 'GUARDFIT_ACTION_APPROVED'::character varying])::text[]))),
    CONSTRAINT ck_audit_events_action_resource_type CHECK (((((action)::text = ANY ((ARRAY['ANALYSIS_CREATED'::character varying, 'ANALYSIS_RETRIED'::character varying, 'ANALYSIS_COMPLETED'::character varying, 'ANALYSIS_FAILED'::character varying])::text[])) AND ((resource_type)::text = 'ANALYSIS'::text)) OR (((action)::text = ANY ((ARRAY['REVIEW_CREATED'::character varying, 'REVIEW_APPROVED'::character varying, 'REVIEW_REJECTED'::character varying])::text[])) AND ((resource_type)::text = 'REVIEW'::text)) OR (((action)::text = ANY ((ARRAY['RISK_PATTERN_PROMOTED'::character varying, 'RISK_PATTERN_UPDATED'::character varying, 'RISK_PATTERN_ACTIVATED'::character varying])::text[])) AND ((resource_type)::text = 'RISK_PATTERN'::text)) OR (((action)::text = ANY ((ARRAY['GUARDFIT_ACTION_CREATED'::character varying, 'GUARDFIT_ACTION_UPDATED'::character varying, 'GUARDFIT_ACTION_APPROVED'::character varying])::text[])) AND ((resource_type)::text = 'GUARDFIT_ACTION'::text)))),
    CONSTRAINT ck_audit_events_resource_type CHECK (((resource_type)::text = ANY ((ARRAY['ANALYSIS'::character varying, 'REVIEW'::character varying, 'RISK_PATTERN'::character varying, 'GUARDFIT_ACTION'::character varying])::text[])))
);


--
-- Name: audit_events_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_events ALTER COLUMN audit_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.audit_events_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: demo_corpus_artifacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demo_corpus_artifacts (
    logical_id character varying(120) NOT NULL,
    artifact_path character varying(500) NOT NULL,
    artifact_sha256 character(64) NOT NULL,
    artifact_version character varying(50) NOT NULL,
    license character varying(50) NOT NULL,
    synthetic boolean NOT NULL,
    demo_only boolean NOT NULL,
    evidence_document_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_demo_corpus_artifact_license CHECK ((btrim((license)::text) <> ''::text)),
    CONSTRAINT ck_demo_corpus_artifact_sha256 CHECK ((artifact_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_demo_corpus_artifact_synthetic CHECK (((synthetic = true) AND (demo_only = true))),
    CONSTRAINT ck_demo_corpus_artifact_version CHECK ((btrim((artifact_version)::text) <> ''::text))
);


--
-- Name: TABLE demo_corpus_artifacts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.demo_corpus_artifacts IS 'Auditable logical-id-to-file/database mapping for the 100% synthetic, demo-only canonical corpus; it confers no legal or regulatory authority.';


--
-- Name: COLUMN demo_corpus_artifacts.artifact_sha256; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.demo_corpus_artifacts.artifact_sha256 IS 'Lowercase SHA-256 of the canonical PDF bytes recorded in data/demo-corpus/manifest.v1.json.';


--
-- Name: COLUMN demo_corpus_artifacts.evidence_document_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.demo_corpus_artifacts.evidence_document_id IS 'Active retrieval evidence row, or NULL when the artifact is product input rather than retrieval evidence.';


--
-- Name: demo_corpus_evidence_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demo_corpus_evidence_mappings (
    product_logical_id character varying(120) NOT NULL,
    evidence_logical_id character varying(120) NOT NULL,
    evidence_document_id bigint NOT NULL,
    selection_order smallint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_demo_corpus_mapping_distinct_artifacts CHECK (((product_logical_id)::text <> (evidence_logical_id)::text)),
    CONSTRAINT ck_demo_corpus_mapping_selection_order CHECK ((selection_order > 0))
);


--
-- Name: TABLE demo_corpus_evidence_mappings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.demo_corpus_evidence_mappings IS 'Explicit demo-only product logical-id to selected evidence logical-id and evidence_documents database-id mapping.';


--
-- Name: document_batch_item_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_batch_item_attempts (
    id bigint NOT NULL,
    item_id bigint NOT NULL,
    attempt_no integer NOT NULL,
    lease_fence bigint NOT NULL,
    worker_owner character varying(100) NOT NULL,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    outcome character varying(30),
    public_error_code character varying(60),
    public_error_message character varying(1000),
    diagnostic_reference character varying(255),
    CONSTRAINT ck_document_batch_item_attempts_completion CHECK (((ended_at IS NULL) = (outcome IS NULL))),
    CONSTRAINT ck_document_batch_item_attempts_error CHECK (((public_error_code IS NULL) = (public_error_message IS NULL))),
    CONSTRAINT ck_document_batch_item_attempts_number CHECK (((attempt_no > 0) AND (lease_fence > 0))),
    CONSTRAINT ck_document_batch_item_attempts_outcome CHECK (((outcome IS NULL) OR ((outcome)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'RETRY_WAIT'::character varying, 'QUARANTINED'::character varying, 'CANCELLED'::character varying, 'LEASE_EXPIRED_RETRY'::character varying, 'LEASE_EXPIRED_QUARANTINED'::character varying, 'LEASE_EXPIRED_CANCELLED'::character varying])::text[]))))
);


--
-- Name: document_batch_item_attempts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_batch_item_attempts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_batch_item_attempts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: document_batch_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_batch_items (
    id bigint NOT NULL,
    batch_id bigint NOT NULL,
    product_id bigint NOT NULL,
    owner_id bigint NOT NULL,
    product_document_id bigint NOT NULL,
    ordinal integer NOT NULL,
    source_file_name character varying(255) NOT NULL,
    source_media_type character varying(150) NOT NULL,
    source_file_size bigint NOT NULL,
    source_checksum character varying(64) NOT NULL,
    source_storage_key character varying(500) NOT NULL,
    due_at timestamp with time zone NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    lease_owner character varying(100),
    lease_until timestamp with time zone,
    lease_fence bigint DEFAULT 0 NOT NULL,
    cancel_requested_at timestamp with time zone,
    cancellation_reason character varying(500),
    cancelled_at timestamp with time zone,
    last_error_code character varying(60),
    last_error_message character varying(1000),
    quarantine_reason character varying(500),
    quarantined_at timestamp with time zone,
    terminal_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_document_batch_items_attempts CHECK ((((max_attempts >= 1) AND (max_attempts <= 100)) AND ((attempt_count >= 0) AND (attempt_count <= max_attempts)))),
    CONSTRAINT ck_document_batch_items_cancellation_fields CHECK ((((cancel_requested_at IS NULL) = (cancellation_reason IS NULL)) AND (((status)::text <> 'CANCELLED'::text) OR (cancellation_reason IS NOT NULL)) AND (((status)::text = 'CANCELLED'::text) = (cancelled_at IS NOT NULL)) AND ((cancelled_at IS NULL) OR ((cancelled_at = terminal_at) AND (cancelled_at >= cancel_requested_at))))),
    CONSTRAINT ck_document_batch_items_error_fields CHECK (((last_error_code IS NULL) = (last_error_message IS NULL))),
    CONSTRAINT ck_document_batch_items_lease CHECK ((((((status)::text = 'LEASED'::text) AND (lease_owner IS NOT NULL) AND (lease_until IS NOT NULL)) OR (((status)::text <> 'LEASED'::text) AND (lease_owner IS NULL) AND (lease_until IS NULL))) AND (lease_fence >= 0) AND (((status)::text <> 'LEASED'::text) OR (attempt_count > 0)))),
    CONSTRAINT ck_document_batch_items_ordinal CHECK (((ordinal >= 1) AND (ordinal <= 100))),
    CONSTRAINT ck_document_batch_items_quarantine_fields CHECK (((((status)::text = 'QUARANTINED'::text) AND (quarantine_reason IS NOT NULL) AND (quarantined_at IS NOT NULL) AND (quarantined_at = terminal_at)) OR (((status)::text <> 'QUARANTINED'::text) AND (quarantine_reason IS NULL) AND (quarantined_at IS NULL)))),
    CONSTRAINT ck_document_batch_items_source_checksum CHECK (((source_checksum)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_document_batch_items_source_file_size CHECK ((source_file_size >= 0)),
    CONSTRAINT ck_document_batch_items_source_metadata CHECK (((btrim((source_file_name)::text) <> ''::text) AND (btrim((source_media_type)::text) <> ''::text) AND (btrim((source_storage_key)::text) <> ''::text))),
    CONSTRAINT ck_document_batch_items_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'LEASED'::character varying, 'RETRY_WAIT'::character varying, 'SUCCEEDED'::character varying, 'CANCELLED'::character varying, 'QUARANTINED'::character varying])::text[]))),
    CONSTRAINT ck_document_batch_items_terminal CHECK ((((status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'CANCELLED'::character varying, 'QUARANTINED'::character varying])::text[])) = (terminal_at IS NOT NULL)))
);


--
-- Name: TABLE document_batch_items; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_batch_items IS 'Durable item queue with immutable source snapshot, due time, retry state, and fenced lease.';


--
-- Name: COLUMN document_batch_items.lease_fence; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_batch_items.lease_fence IS 'Monotonically incremented on each claim; a worker update must match both lease_owner and lease_fence.';


--
-- Name: document_batch_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_batch_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_batch_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: document_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_batches (
    id bigint NOT NULL,
    product_id bigint NOT NULL,
    owner_id bigint NOT NULL,
    scenario character varying(60) NOT NULL,
    requested_item_count integer NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    manifest_hash character varying(64) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    cancel_requested_at timestamp with time zone,
    cancellation_reason character varying(500),
    cancelled_at timestamp with time zone,
    quarantine_reason character varying(500),
    quarantined_at timestamp with time zone,
    terminal_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_document_batches_cancellation_fields CHECK ((((cancel_requested_at IS NULL) = (cancellation_reason IS NULL)) AND (((status)::text <> 'CANCELLED'::text) OR (cancellation_reason IS NOT NULL)) AND (((status)::text = 'CANCELLED'::text) = (cancelled_at IS NOT NULL)) AND ((cancelled_at IS NULL) OR ((cancelled_at = terminal_at) AND (cancelled_at >= cancel_requested_at))))),
    CONSTRAINT ck_document_batches_identity_fields CHECK (((btrim((scenario)::text) <> ''::text) AND (btrim((idempotency_key)::text) <> ''::text))),
    CONSTRAINT ck_document_batches_manifest_hash CHECK (((manifest_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_document_batches_quarantine_fields CHECK (((((status)::text = 'QUARANTINED'::text) AND (quarantine_reason IS NOT NULL) AND (quarantined_at IS NOT NULL) AND (quarantined_at = terminal_at)) OR (((status)::text <> 'QUARANTINED'::text) AND (quarantine_reason IS NULL) AND (quarantined_at IS NULL)))),
    CONSTRAINT ck_document_batches_requested_item_count CHECK (((requested_item_count >= 1) AND (requested_item_count <= 100))),
    CONSTRAINT ck_document_batches_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'CANCELLED'::character varying, 'QUARANTINED'::character varying])::text[]))),
    CONSTRAINT ck_document_batches_terminal CHECK ((((status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'CANCELLED'::character varying, 'QUARANTINED'::character varying])::text[])) = (terminal_at IS NOT NULL)))
);


--
-- Name: TABLE document_batches; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_batches IS 'Durable document submission identity and aggregate lifecycle; rows, not application events, are authoritative.';


--
-- Name: COLUMN document_batches.manifest_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_batches.manifest_hash IS 'SHA-256 of the canonical ordered upload manifest; paired with owner-scoped idempotency_key.';


--
-- Name: document_batches_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_batches ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: document_extraction_pages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_extraction_pages (
    id bigint NOT NULL,
    extraction_run_id bigint NOT NULL,
    page_number integer NOT NULL,
    selected_method character varying(30) NOT NULL,
    selected_text text NOT NULL,
    selected_text_hash character varying(64) NOT NULL,
    pdfbox_candidate_hash character varying(64) NOT NULL,
    ocr_render_artifact_key character varying(500),
    ocr_render_artifact_hash character varying(64),
    ocr_config_snapshot jsonb,
    ocr_engine character varying(100),
    ocr_model_version character varying(150),
    ocr_language character varying(50),
    ocr_confidence numeric(7,4),
    ocr_warnings jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_document_extraction_pages_method CHECK (((selected_method)::text = ANY ((ARRAY['PDFBOX_TEXT'::character varying, 'OCR_KOR_ENG'::character varying])::text[]))),
    CONSTRAINT ck_document_extraction_pages_method_metadata CHECK (((((selected_method)::text = 'PDFBOX_TEXT'::text) AND (ocr_render_artifact_key IS NULL) AND (ocr_render_artifact_hash IS NULL) AND (ocr_config_snapshot IS NULL) AND (ocr_engine IS NULL) AND (ocr_model_version IS NULL) AND (ocr_language IS NULL) AND (ocr_confidence IS NULL) AND (ocr_warnings IS NULL)) OR (((selected_method)::text = 'OCR_KOR_ENG'::text) AND (ocr_render_artifact_key IS NOT NULL) AND (btrim((ocr_render_artifact_key)::text) <> ''::text) AND (ocr_render_artifact_hash IS NOT NULL) AND (ocr_config_snapshot IS NOT NULL) AND (ocr_engine IS NOT NULL) AND (btrim((ocr_engine)::text) <> ''::text) AND (ocr_model_version IS NOT NULL) AND (btrim((ocr_model_version)::text) <> ''::text) AND (ocr_language IS NOT NULL) AND (btrim((ocr_language)::text) <> ''::text) AND (ocr_confidence IS NOT NULL) AND (ocr_warnings IS NOT NULL)))),
    CONSTRAINT ck_document_extraction_pages_ocr_confidence CHECK (((ocr_confidence IS NULL) OR ((ocr_confidence >= (0)::numeric) AND (ocr_confidence <= (100)::numeric)))),
    CONSTRAINT ck_document_extraction_pages_ocr_config CHECK (((ocr_config_snapshot IS NULL) OR (jsonb_typeof(ocr_config_snapshot) = 'object'::text))),
    CONSTRAINT ck_document_extraction_pages_ocr_render_hash CHECK (((ocr_render_artifact_hash IS NULL) OR ((ocr_render_artifact_hash)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_document_extraction_pages_ocr_warnings CHECK (((ocr_warnings IS NULL) OR (jsonb_typeof(ocr_warnings) = 'array'::text))),
    CONSTRAINT ck_document_extraction_pages_page_number CHECK ((page_number > 0)),
    CONSTRAINT ck_document_extraction_pages_pdfbox_hash CHECK (((pdfbox_candidate_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_document_extraction_pages_selected_hash CHECK (((selected_text_hash)::text ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: TABLE document_extraction_pages; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_extraction_pages IS 'Append-only ordered page provenance for the PDFBox or Korean/English OCR text selected by a run.';


--
-- Name: document_extraction_pages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_extraction_pages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_extraction_pages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: document_extraction_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_extraction_runs (
    id bigint NOT NULL,
    product_document_id bigint NOT NULL,
    run_generation integer NOT NULL,
    source_hash character varying(64) NOT NULL,
    config_version character varying(100) NOT NULL,
    config_snapshot jsonb NOT NULL,
    state character varying(20) NOT NULL,
    result_text_hash character varying(64),
    page_count integer NOT NULL,
    error_code character varying(60),
    error_message character varying(500),
    error_retryable boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_document_extraction_runs_config_snapshot CHECK ((jsonb_typeof(config_snapshot) = 'object'::text)),
    CONSTRAINT ck_document_extraction_runs_config_version CHECK ((btrim((config_version)::text) <> ''::text)),
    CONSTRAINT ck_document_extraction_runs_generation CHECK ((run_generation > 0)),
    CONSTRAINT ck_document_extraction_runs_outcome CHECK (((((state)::text = 'SUCCEEDED'::text) AND (result_text_hash IS NOT NULL) AND (page_count > 0) AND (error_code IS NULL) AND (error_message IS NULL) AND (error_retryable = false)) OR (((state)::text = 'FAILED'::text) AND (result_text_hash IS NULL) AND (error_code IS NOT NULL) AND (btrim((error_code)::text) <> ''::text) AND (error_message IS NOT NULL) AND (btrim((error_message)::text) <> ''::text)))),
    CONSTRAINT ck_document_extraction_runs_page_count CHECK ((page_count >= 0)),
    CONSTRAINT ck_document_extraction_runs_result_hash CHECK (((result_text_hash IS NULL) OR ((result_text_hash)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_document_extraction_runs_source_hash CHECK (((source_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_document_extraction_runs_state CHECK (((state)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: TABLE document_extraction_runs; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_extraction_runs IS 'Append-only terminal extraction attempts; each generation records its exact source and configuration.';


--
-- Name: document_extraction_runs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_extraction_runs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_extraction_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: document_source_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_source_revisions (
    id bigint NOT NULL,
    product_document_id bigint NOT NULL,
    revision_number integer NOT NULL,
    file_name character varying(255) NOT NULL,
    media_type character varying(150) NOT NULL,
    file_size bigint NOT NULL,
    checksum character varying(64) NOT NULL,
    storage_key character varying(500) NOT NULL,
    source_hash character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_document_source_revisions_checksum CHECK (((checksum)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_document_source_revisions_file_name CHECK ((btrim((file_name)::text) <> ''::text)),
    CONSTRAINT ck_document_source_revisions_file_size CHECK ((file_size >= 0)),
    CONSTRAINT ck_document_source_revisions_media_type CHECK ((btrim((media_type)::text) <> ''::text)),
    CONSTRAINT ck_document_source_revisions_revision CHECK ((revision_number > 0)),
    CONSTRAINT ck_document_source_revisions_source_hash CHECK (((source_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_document_source_revisions_storage_key CHECK ((btrim((storage_key)::text) <> ''::text))
);


--
-- Name: TABLE document_source_revisions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_source_revisions IS 'Append-only identity and file metadata for each persisted product document source.';


--
-- Name: COLUMN document_source_revisions.source_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_source_revisions.source_hash IS 'Lowercase SHA-256 hex of the complete immutable source bytes.';


--
-- Name: document_source_revisions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_source_revisions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_source_revisions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: evidence_document_chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evidence_document_chunks (
    id bigint NOT NULL,
    evidence_document_id bigint NOT NULL,
    source_hash character varying(64) NOT NULL,
    chunk_ordinal integer NOT NULL,
    chunking_version character varying(100) NOT NULL,
    chunk_hash character varying(64) NOT NULL,
    chunk_text text NOT NULL,
    embedding_model character varying(255) NOT NULL,
    embedding public.vector(1024) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_evidence_document_chunks_chunk_hash CHECK (((chunk_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_evidence_document_chunks_chunking_version CHECK ((btrim((chunking_version)::text) <> ''::text)),
    CONSTRAINT ck_evidence_document_chunks_embedding_model CHECK ((btrim((embedding_model)::text) <> ''::text)),
    CONSTRAINT ck_evidence_document_chunks_embedding_non_zero CHECK ((public.vector_norm(embedding) > (0)::double precision)),
    CONSTRAINT ck_evidence_document_chunks_ordinal CHECK ((chunk_ordinal >= 0)),
    CONSTRAINT ck_evidence_document_chunks_source_hash CHECK (((source_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_evidence_document_chunks_text CHECK ((btrim(chunk_text) <> ''::text))
);


--
-- Name: TABLE evidence_document_chunks; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.evidence_document_chunks IS 'Deterministically versioned evidence chunks and their Ollama embeddings for pgvector retrieval.';


--
-- Name: COLUMN evidence_document_chunks.source_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.evidence_document_chunks.source_hash IS 'Lowercase SHA-256 hex of the complete source text used for this indexing pass.';


--
-- Name: COLUMN evidence_document_chunks.chunk_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.evidence_document_chunks.chunk_hash IS 'Lowercase SHA-256 hex of chunk_text.';


--
-- Name: CONSTRAINT ck_evidence_document_chunks_embedding_non_zero ON evidence_document_chunks; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT ck_evidence_document_chunks_embedding_non_zero ON public.evidence_document_chunks IS 'Cosine retrieval requires embeddings with non-zero Euclidean norm.';


--
-- Name: evidence_document_chunks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.evidence_document_chunks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.evidence_document_chunks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: evidence_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evidence_documents (
    id bigint NOT NULL,
    source_type character varying(30) NOT NULL,
    title character varying(255) NOT NULL,
    version character varying(50),
    content text,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_evidence_documents_source_type CHECK (((source_type)::text = ANY ((ARRAY['INTERNAL_POLICY'::character varying, 'REGULATION'::character varying, 'PRODUCT_POLICY'::character varying])::text[])))
);


--
-- Name: TABLE evidence_documents; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.evidence_documents IS '공식 승인 근거 라이브러리. 담당자는 선택만 한다.';


--
-- Name: evidence_documents_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.evidence_documents ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.evidence_documents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: evidence_references; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evidence_references (
    id bigint NOT NULL,
    finding_id bigint NOT NULL,
    evidence_document_id bigint NOT NULL,
    excerpt text,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: evidence_references_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.evidence_references ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.evidence_references_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: finding_affected_personas; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.finding_affected_personas (
    finding_id bigint NOT NULL,
    persona_template_id bigint NOT NULL
);


--
-- Name: finding_evidence_anchors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.finding_evidence_anchors (
    id bigint NOT NULL,
    finding_id bigint NOT NULL,
    source_role character varying(30) NOT NULL,
    source_document_id bigint,
    source_revision_id bigint,
    evidence_document_id bigint,
    retrieved_chunk_id bigint,
    source_hash character varying(64) NOT NULL,
    page_number integer NOT NULL,
    utf8_start_offset bigint NOT NULL,
    utf8_end_offset bigint NOT NULL,
    excerpt_hash character varying(64) NOT NULL,
    exact_excerpt text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_finding_evidence_anchors_excerpt CHECK ((octet_length(exact_excerpt) = (utf8_end_offset - utf8_start_offset))),
    CONSTRAINT ck_finding_evidence_anchors_excerpt_hash CHECK (((excerpt_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_finding_evidence_anchors_page CHECK ((page_number > 0)),
    CONSTRAINT ck_finding_evidence_anchors_role CHECK (((source_role)::text = ANY ((ARRAY['DOCUMENT_CLAIM'::character varying, 'POLICY_REQUIREMENT'::character varying])::text[]))),
    CONSTRAINT ck_finding_evidence_anchors_source_hash CHECK (((source_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_finding_evidence_anchors_source_identity CHECK (((((source_role)::text = 'DOCUMENT_CLAIM'::text) AND (source_document_id IS NOT NULL) AND (source_revision_id IS NOT NULL) AND (evidence_document_id IS NULL) AND (retrieved_chunk_id IS NULL)) OR (((source_role)::text = 'POLICY_REQUIREMENT'::text) AND (source_document_id IS NULL) AND (source_revision_id IS NULL) AND (evidence_document_id IS NOT NULL) AND (retrieved_chunk_id IS NOT NULL)))),
    CONSTRAINT ck_finding_evidence_anchors_utf8_range CHECK (((utf8_start_offset >= 0) AND (utf8_end_offset > utf8_start_offset)))
);


--
-- Name: TABLE finding_evidence_anchors; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.finding_evidence_anchors IS 'Immutable exact UTF-8 source evidence for an execution-bound finding revision.';


--
-- Name: COLUMN finding_evidence_anchors.source_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.finding_evidence_anchors.source_hash IS 'Lowercase SHA-256 of the immutable document revision or indexed policy source.';


--
-- Name: COLUMN finding_evidence_anchors.excerpt_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.finding_evidence_anchors.excerpt_hash IS 'Lowercase SHA-256 of exact_excerpt UTF-8 bytes.';


--
-- Name: finding_evidence_anchors_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.finding_evidence_anchors ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.finding_evidence_anchors_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: finding_review_decisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.finding_review_decisions (
    id bigint NOT NULL,
    review_id bigint NOT NULL,
    analysis_execution_id bigint NOT NULL,
    finding_revision_id bigint NOT NULL,
    reviewer_id bigint NOT NULL,
    decision character varying(20) NOT NULL,
    comment character varying(1000),
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_finding_review_decisions_decision CHECK (((decision)::text = ANY ((ARRAY['APPROVED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: TABLE finding_review_decisions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.finding_review_decisions IS 'Append-only reviewer decisions for immutable execution-bound Finding revisions.';


--
-- Name: finding_review_decisions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.finding_review_decisions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.finding_review_decisions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: findings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.findings (
    id bigint NOT NULL,
    analysis_id bigint NOT NULL,
    statement text NOT NULL,
    severity character varying(10) NOT NULL,
    recommendation text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    analysis_execution_id bigint,
    lineage_id character varying(36),
    revision_number integer,
    supersedes_finding_id bigint,
    policy_rule_code character varying(40),
    CONSTRAINT ck_findings_not_self_superseding CHECK (((supersedes_finding_id IS NULL) OR (supersedes_finding_id <> id))),
    CONSTRAINT ck_findings_policy_rule_code CHECK (((policy_rule_code IS NULL) OR ((policy_rule_code)::text = ANY ((ARRAY['RETURN_FRAMING'::character varying, 'LOSS_SOFTENING'::character varying, 'COST_OMISSION'::character varying, 'STABILITY_KEYWORD'::character varying, 'FORMAL_CONFIRMATION'::character varying, 'COGNITIVE_ACCESSIBILITY'::character varying])::text[])))),
    CONSTRAINT ck_findings_revision_identity CHECK ((((analysis_execution_id IS NULL) AND (lineage_id IS NULL) AND (revision_number IS NULL) AND (supersedes_finding_id IS NULL)) OR ((analysis_execution_id IS NOT NULL) AND (lineage_id IS NOT NULL) AND ((lineage_id)::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'::text) AND (revision_number > 0) AND (((revision_number = 1) AND (supersedes_finding_id IS NULL)) OR ((revision_number > 1) AND (supersedes_finding_id IS NOT NULL)))))),
    CONSTRAINT ck_findings_severity CHECK (((severity)::text = ANY ((ARRAY['HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying])::text[])))
);


--
-- Name: TABLE findings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.findings IS 'HIGH Finding은 evidence_reference를 최소 1개 가져야 한다(앱 검증).';


--
-- Name: COLUMN findings.analysis_execution_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.findings.analysis_execution_id IS 'Execution boundary for new finding revisions; null only on legacy rows.';


--
-- Name: COLUMN findings.lineage_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.findings.lineage_id IS 'Stable UUID shared by all immutable revisions of one logical finding.';


--
-- Name: findings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.findings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.findings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: ground_truth_facts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ground_truth_facts (
    id bigint NOT NULL,
    document_id bigint NOT NULL,
    label character varying(255) NOT NULL,
    value text NOT NULL,
    importance character varying(10) NOT NULL,
    verification_status character varying(20) NOT NULL,
    extraction_source character varying(30) NOT NULL,
    source_text_sha256 character varying(64) NOT NULL,
    created_by bigint NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_ground_truth_facts_decision CHECK (((((verification_status)::text = 'CANDIDATE'::text) AND (decided_by IS NULL) AND (decided_at IS NULL)) OR (((verification_status)::text = ANY ((ARRAY['VERIFIED'::character varying, 'REJECTED'::character varying])::text[])) AND (decided_by IS NOT NULL) AND (decided_at IS NOT NULL)))),
    CONSTRAINT ck_ground_truth_facts_extraction_source CHECK (((extraction_source)::text = 'CONFIRMED_DOCUMENT'::text)),
    CONSTRAINT ck_ground_truth_facts_importance CHECK (((importance)::text = 'HIGH'::text)),
    CONSTRAINT ck_ground_truth_facts_source_text_sha256 CHECK (((source_text_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_ground_truth_facts_verification_status CHECK (((verification_status)::text = ANY ((ARRAY['CANDIDATE'::character varying, 'VERIFIED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: TABLE ground_truth_facts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.ground_truth_facts IS '담당자가 확인한 product_document 원문 전체의 현재 공식 사실 스냅샷. 문서당 한 건만 유지한다.';


--
-- Name: COLUMN ground_truth_facts.value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ground_truth_facts.value IS 'AI 요약이나 fixture가 아닌 product_documents.extracted_text의 사람 확인 당시 원문.';


--
-- Name: COLUMN ground_truth_facts.source_text_sha256; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ground_truth_facts.source_text_sha256 IS 'value 원문의 SHA-256 lowercase hex. 확인 텍스트 변경 여부를 판별한다.';


--
-- Name: ground_truth_facts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.ground_truth_facts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.ground_truth_facts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: guardfit_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.guardfit_actions (
    id bigint NOT NULL,
    risk_pattern_id bigint NOT NULL,
    action_type character varying(20) NOT NULL,
    label character varying(255) NOT NULL,
    placement character varying(255) NOT NULL,
    required boolean DEFAULT false NOT NULL,
    preview text,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    updated_by bigint,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_guardfit_actions_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying])::text[]))),
    CONSTRAINT ck_guardfit_actions_type CHECK (((action_type)::text = ANY ((ARRAY['LABEL'::character varying, 'WARNING'::character varying, 'QUESTION'::character varying, 'COMPARISON'::character varying])::text[])))
);


--
-- Name: TABLE guardfit_actions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.guardfit_actions IS 'WARNING_LABEL/DISCARDED는 사용하지 않는다(확정).';


--
-- Name: guardfit_actions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.guardfit_actions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.guardfit_actions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: idempotency_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_claims (
    id bigint NOT NULL,
    actor_id bigint NOT NULL,
    operation character varying(32) NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    analysis_id bigint,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_idempotency_claims_operation CHECK (((operation)::text = 'ANALYSIS_CREATE'::text)),
    CONSTRAINT ck_idempotency_claims_request_fingerprint CHECK (((request_fingerprint)::text ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: TABLE idempotency_claims; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.idempotency_claims IS '사용자별 분석 생성 멱등성 선점. analysis_id가 채워지면 승자 요청이 완료된 상태다.';


--
-- Name: COLUMN idempotency_claims.request_fingerprint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.idempotency_claims.request_fingerprint IS '정규화된 분석 생성 요청의 lowercase SHA-256 hex.';


--
-- Name: idempotency_claims_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.idempotency_claims ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.idempotency_claims_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: persona_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.persona_templates (
    id bigint NOT NULL,
    code character varying(60) NOT NULL,
    name character varying(100) NOT NULL,
    criteria jsonb,
    risk_focus jsonb,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_persona_templates_code CHECK (((code)::text = ANY ((ARRAY['FINANCIAL_BEGINNER'::character varying, 'SENIOR'::character varying, 'LOSS_EXPERIENCED'::character varying, 'SHORT_TERM_LIQUIDITY'::character varying, 'SELF_EMPLOYED'::character varying, 'LIMITED_PRODUCT_FAMILIARITY'::character varying, 'LOSS_RECOVERY_PRESSURE'::character varying, 'NEAR_TERM_LIQUIDITY_NEED'::character varying, 'VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT'::character varying, 'EXPLANATION_ACCESS_SUPPORT'::character varying, 'DIGITAL_CHANNEL_SUPPORT'::character varying, 'LIFE_EVENT_FINANCIAL_STRESS'::character varying])::text[])))
);


--
-- Name: TABLE persona_templates; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.persona_templates IS '문서 설명 실패를 검증하는 합성 상황 Persona. 비활성 legacy 행은 역사 결과 조회를 위해 보존한다.';


--
-- Name: persona_templates_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.persona_templates ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.persona_templates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: product_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_documents (
    id bigint NOT NULL,
    product_id bigint NOT NULL,
    file_name character varying(255) NOT NULL,
    media_type character varying(150) NOT NULL,
    file_size bigint,
    checksum character varying(64),
    storage_key character varying(500) NOT NULL,
    extract_status character varying(20) DEFAULT 'UPLOADED'::character varying NOT NULL,
    extracted_text text,
    confirmed boolean DEFAULT false NOT NULL,
    confirmed_by bigint,
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    extraction_error_code character varying(60),
    extraction_error_message character varying(500),
    extraction_error_retryable boolean DEFAULT false NOT NULL,
    current_extraction_run_id bigint,
    extracted_text_hash character varying(64),
    confirmed_extraction_run_id bigint,
    confirmed_text_hash character varying(64),
    CONSTRAINT ck_product_documents_confirmed_text_hash CHECK (((confirmed_text_hash IS NULL) OR ((confirmed_text_hash)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_product_documents_extract_status CHECK (((extract_status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'EXTRACTING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_product_documents_extracted_text_hash CHECK (((extracted_text_hash IS NULL) OR ((extracted_text_hash)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_product_documents_extraction_error_metadata CHECK (((((extract_status)::text = 'FAILED'::text) AND (extraction_error_code IS NOT NULL) AND (btrim((extraction_error_code)::text) <> ''::text) AND (extraction_error_message IS NOT NULL) AND (btrim((extraction_error_message)::text) <> ''::text)) OR (((extract_status)::text <> 'FAILED'::text) AND (extraction_error_code IS NULL) AND (extraction_error_message IS NULL) AND (extraction_error_retryable = false))))
);


--
-- Name: TABLE product_documents; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.product_documents IS '파일 메타 + 추출 텍스트. 바이너리는 영구 저장하지 않으며 storage_key는 mock:// 포인터다.';


--
-- Name: COLUMN product_documents.checksum; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_documents.checksum IS 'SHA-256 hex(64자). 업로드 스트림에서 계산하고 바이너리는 폐기한다.';


--
-- Name: COLUMN product_documents.extraction_error_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_documents.extraction_error_code IS 'Stable public error code populated only while extraction is FAILED.';


--
-- Name: COLUMN product_documents.extraction_error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_documents.extraction_error_message IS 'Sanitized public extraction failure message; never stores exception details.';


--
-- Name: COLUMN product_documents.extraction_error_retryable; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_documents.extraction_error_retryable IS 'Whether the persisted extraction failure is eligible for an owner-initiated retry.';


--
-- Name: COLUMN product_documents.current_extraction_run_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_documents.current_extraction_run_id IS 'Nullable for legacy documents; identifies the run that produced the current extracted text.';


--
-- Name: COLUMN product_documents.confirmed_text_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_documents.confirmed_text_hash IS 'Exact extracted-text SHA-256 acknowledged by the confirmer; pre-V24 legacy rows remain unbackfilled.';


--
-- Name: product_documents_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.product_documents ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.product_documents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.products (
    id bigint NOT NULL,
    owner_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    product_type character varying(20) NOT NULL,
    description character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_products_type CHECK (((product_type)::text = ANY ((ARRAY['INVESTMENT'::character varying, 'LOAN'::character varying, 'SAVINGS'::character varying])::text[])))
);


--
-- Name: TABLE products; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.products IS '분석의 최상위 컨텍스트. status 컬럼 없음(확정).';


--
-- Name: products_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.products ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: red_team_packs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.red_team_packs (
    id bigint NOT NULL,
    code character varying(60) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: red_team_packs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.red_team_packs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.red_team_packs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: red_team_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.red_team_rules (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    pack_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    sort_order integer,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_red_team_rules_code CHECK (((code)::text = ANY ((ARRAY['RETURN_FRAMING'::character varying, 'LOSS_SOFTENING'::character varying, 'COST_OMISSION'::character varying, 'STABILITY_KEYWORD'::character varying, 'FORMAL_CONFIRMATION'::character varying, 'COGNITIVE_ACCESSIBILITY'::character varying])::text[])))
);


--
-- Name: red_team_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.red_team_rules ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.red_team_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: review_selected_findings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.review_selected_findings (
    review_id bigint NOT NULL,
    finding_id bigint NOT NULL
);


--
-- Name: reviews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reviews (
    id bigint NOT NULL,
    analysis_id bigint NOT NULL,
    reviewer_id bigint,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    comment character varying(1000),
    decided_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    submission_comment character varying(500),
    analysis_execution_id bigint,
    CONSTRAINT ck_reviews_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: TABLE reviews; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.reviews IS '분석당 1검토(UNIQUE). 결정은 별도 decision이 아니라 status에 통합한다.';


--
-- Name: COLUMN reviews.submission_comment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.reviews.submission_comment IS '상품 담당자가 검토 요청 시 남긴 선택 의견. 검토자의 결정 사유(comment)와 별도로 보존한다.';


--
-- Name: COLUMN reviews.analysis_execution_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.reviews.analysis_execution_id IS 'Immutable successful execution reviewed by this aggregate; null only on legacy rows.';


--
-- Name: reviews_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.reviews ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.reviews_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: risk_patterns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.risk_patterns (
    id bigint NOT NULL,
    finding_id bigint NOT NULL,
    review_id bigint,
    name character varying(255) NOT NULL,
    severity character varying(10) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_risk_patterns_severity CHECK (((severity)::text = ANY ((ARRAY['HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying])::text[]))),
    CONSTRAINT ck_risk_patterns_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying])::text[])))
);


--
-- Name: TABLE risk_patterns; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.risk_patterns IS '승인된 Finding만 승격한다. 영향 Persona/근거는 finding으로 역추적한다.';


--
-- Name: risk_patterns_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.risk_patterns ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.risk_patterns_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: risk_score_ledger_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.risk_score_ledger_entries (
    id bigint NOT NULL,
    risk_score_run_id bigint NOT NULL,
    finding_revision_id bigint NOT NULL,
    finding_review_decision_id bigint NOT NULL,
    document_claim_anchor_id bigint NOT NULL,
    policy_requirement_anchor_id bigint NOT NULL,
    policy_rule_id character varying(100) NOT NULL,
    magnitude_basis_points integer NOT NULL,
    likelihood_basis_points integer NOT NULL,
    contribution_basis_points integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_risk_score_ledger_entries_contribution CHECK ((((contribution_basis_points >= 0) AND (contribution_basis_points <= 10000)) AND (contribution_basis_points = ((magnitude_basis_points * likelihood_basis_points) / 10000)))),
    CONSTRAINT ck_risk_score_ledger_entries_likelihood CHECK ((likelihood_basis_points = ANY (ARRAY[2000, 4000, 6000, 8000, 10000]))),
    CONSTRAINT ck_risk_score_ledger_entries_magnitude CHECK ((magnitude_basis_points = ANY (ARRAY[2000, 4000, 6000, 8000, 10000]))),
    CONSTRAINT ck_risk_score_ledger_entries_policy_rule CHECK ((btrim((policy_rule_id)::text) <> ''::text))
);


--
-- Name: TABLE risk_score_ledger_entries; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.risk_score_ledger_entries IS 'Immutable evidence ledger containing only reviewer-approved current Finding revisions and policy-derived M/L components.';


--
-- Name: COLUMN risk_score_ledger_entries.contribution_basis_points; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.risk_score_ledger_entries.contribution_basis_points IS 'Exact policy contribution: magnitude_basis_points multiplied by likelihood_basis_points and divided by 10000.';


--
-- Name: risk_score_ledger_entries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.risk_score_ledger_entries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.risk_score_ledger_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: risk_score_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.risk_score_runs (
    id bigint NOT NULL,
    analysis_execution_id bigint NOT NULL,
    policy_version character varying(100) NOT NULL,
    state character varying(20) NOT NULL,
    score_value integer,
    not_scored_reason character varying(500),
    input_fingerprint character varying(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT ck_risk_score_runs_input_fingerprint CHECK (((input_fingerprint)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_risk_score_runs_policy_version CHECK ((btrim((policy_version)::text) <> ''::text)),
    CONSTRAINT ck_risk_score_runs_result CHECK (((((state)::text = 'PENDING_REVIEW'::text) AND (score_value IS NULL) AND (not_scored_reason IS NULL) AND (completed_at IS NULL)) OR (((state)::text = 'NOT_SCORED'::text) AND (score_value IS NULL) AND (not_scored_reason IS NOT NULL) AND (btrim((not_scored_reason)::text) <> ''::text) AND (completed_at IS NOT NULL)) OR (((state)::text = 'SCORED'::text) AND (score_value IS NOT NULL) AND ((score_value >= 0) AND (score_value <= 100)) AND (not_scored_reason IS NULL) AND (completed_at IS NOT NULL)))),
    CONSTRAINT ck_risk_score_runs_state CHECK (((state)::text = ANY ((ARRAY['NOT_SCORED'::character varying, 'PENDING_REVIEW'::character varying, 'SCORED'::character varying])::text[]))),
    CONSTRAINT ck_risk_score_runs_timestamp_order CHECK (((completed_at IS NULL) OR (completed_at >= created_at)))
);


--
-- Name: TABLE risk_score_runs; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.risk_score_runs IS 'Immutable, policy-versioned score outcomes keyed idempotently to one analysis execution and input fingerprint.';


--
-- Name: COLUMN risk_score_runs.input_fingerprint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.risk_score_runs.input_fingerprint IS 'Lowercase SHA-256 of the canonical score inputs; provider score, severity, personas, similarity, and model confidence are excluded.';


--
-- Name: risk_score_runs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.risk_score_runs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.risk_score_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    role character varying(30) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_users_role CHECK (((role)::text = ANY ((ARRAY['PRODUCT_MANAGER'::character varying, 'COMPLIANCE_REVIEWER'::character varying])::text[])))
);


--
-- Name: TABLE users; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.users IS '실제 사용자(상품 담당자/검토자). AI 소비자 Persona는 persona_templates에 저장한다.';


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: analyses analyses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analyses
    ADD CONSTRAINT analyses_pkey PRIMARY KEY (id);


--
-- Name: analysis_evidence_documents analysis_evidence_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_evidence_documents
    ADD CONSTRAINT analysis_evidence_documents_pkey PRIMARY KEY (analysis_id, evidence_document_id);


--
-- Name: analysis_executions analysis_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_executions
    ADD CONSTRAINT analysis_executions_pkey PRIMARY KEY (id);


--
-- Name: analysis_ground_truth_fact_snapshots analysis_ground_truth_fact_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_ground_truth_fact_snapshots
    ADD CONSTRAINT analysis_ground_truth_fact_snapshots_pkey PRIMARY KEY (id);


--
-- Name: analysis_personas analysis_personas_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_personas
    ADD CONSTRAINT analysis_personas_pkey PRIMARY KEY (analysis_id, persona_template_id);


--
-- Name: analysis_rag_retrieval_snapshots analysis_rag_retrieval_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_retrieval_snapshots
    ADD CONSTRAINT analysis_rag_retrieval_snapshots_pkey PRIMARY KEY (id);


--
-- Name: analysis_rag_runs analysis_rag_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_runs
    ADD CONSTRAINT analysis_rag_runs_pkey PRIMARY KEY (id);


--
-- Name: audit_events audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_events
    ADD CONSTRAINT audit_events_pkey PRIMARY KEY (audit_id);


--
-- Name: product_documents ck_product_documents_confirmation_binding; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.product_documents
    ADD CONSTRAINT ck_product_documents_confirmation_binding CHECK (((current_extraction_run_id IS NULL) OR ((confirmed = false) AND (confirmed_extraction_run_id IS NULL) AND (confirmed_text_hash IS NULL)) OR ((confirmed = true) AND (confirmed_by IS NOT NULL) AND (confirmed_at IS NOT NULL) AND (NOT (confirmed_extraction_run_id IS DISTINCT FROM current_extraction_run_id)) AND (confirmed_text_hash IS NOT NULL) AND (extracted_text_hash IS NOT NULL) AND ((confirmed_text_hash)::text = (extracted_text_hash)::text)))) NOT VALID;


--
-- Name: demo_corpus_artifacts demo_corpus_artifacts_artifact_path_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_artifacts
    ADD CONSTRAINT demo_corpus_artifacts_artifact_path_key UNIQUE (artifact_path);


--
-- Name: demo_corpus_artifacts demo_corpus_artifacts_artifact_sha256_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_artifacts
    ADD CONSTRAINT demo_corpus_artifacts_artifact_sha256_key UNIQUE (artifact_sha256);


--
-- Name: demo_corpus_artifacts demo_corpus_artifacts_evidence_document_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_artifacts
    ADD CONSTRAINT demo_corpus_artifacts_evidence_document_id_key UNIQUE (evidence_document_id);


--
-- Name: demo_corpus_artifacts demo_corpus_artifacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_artifacts
    ADD CONSTRAINT demo_corpus_artifacts_pkey PRIMARY KEY (logical_id);


--
-- Name: demo_corpus_evidence_mappings demo_corpus_evidence_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_evidence_mappings
    ADD CONSTRAINT demo_corpus_evidence_mappings_pkey PRIMARY KEY (product_logical_id, evidence_logical_id);


--
-- Name: document_batch_item_attempts document_batch_item_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_item_attempts
    ADD CONSTRAINT document_batch_item_attempts_pkey PRIMARY KEY (id);


--
-- Name: document_batch_items document_batch_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_items
    ADD CONSTRAINT document_batch_items_pkey PRIMARY KEY (id);


--
-- Name: document_batches document_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batches
    ADD CONSTRAINT document_batches_pkey PRIMARY KEY (id);


--
-- Name: document_extraction_pages document_extraction_pages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_pages
    ADD CONSTRAINT document_extraction_pages_pkey PRIMARY KEY (id);


--
-- Name: document_extraction_runs document_extraction_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_runs
    ADD CONSTRAINT document_extraction_runs_pkey PRIMARY KEY (id);


--
-- Name: document_source_revisions document_source_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_source_revisions
    ADD CONSTRAINT document_source_revisions_pkey PRIMARY KEY (id);


--
-- Name: evidence_document_chunks evidence_document_chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_document_chunks
    ADD CONSTRAINT evidence_document_chunks_pkey PRIMARY KEY (id);


--
-- Name: evidence_documents evidence_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_documents
    ADD CONSTRAINT evidence_documents_pkey PRIMARY KEY (id);


--
-- Name: evidence_references evidence_references_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_references
    ADD CONSTRAINT evidence_references_pkey PRIMARY KEY (id);


--
-- Name: finding_affected_personas finding_affected_personas_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_affected_personas
    ADD CONSTRAINT finding_affected_personas_pkey PRIMARY KEY (finding_id, persona_template_id);


--
-- Name: finding_evidence_anchors finding_evidence_anchors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence_anchors
    ADD CONSTRAINT finding_evidence_anchors_pkey PRIMARY KEY (id);


--
-- Name: finding_review_decisions finding_review_decisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_review_decisions
    ADD CONSTRAINT finding_review_decisions_pkey PRIMARY KEY (id);


--
-- Name: findings findings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.findings
    ADD CONSTRAINT findings_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: ground_truth_facts ground_truth_facts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ground_truth_facts
    ADD CONSTRAINT ground_truth_facts_pkey PRIMARY KEY (id);


--
-- Name: guardfit_actions guardfit_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.guardfit_actions
    ADD CONSTRAINT guardfit_actions_pkey PRIMARY KEY (id);


--
-- Name: idempotency_claims idempotency_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_claims
    ADD CONSTRAINT idempotency_claims_pkey PRIMARY KEY (id);


--
-- Name: persona_templates persona_templates_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.persona_templates
    ADD CONSTRAINT persona_templates_code_key UNIQUE (code);


--
-- Name: persona_templates persona_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.persona_templates
    ADD CONSTRAINT persona_templates_pkey PRIMARY KEY (id);


--
-- Name: product_documents product_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT product_documents_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: red_team_packs red_team_packs_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.red_team_packs
    ADD CONSTRAINT red_team_packs_code_key UNIQUE (code);


--
-- Name: red_team_packs red_team_packs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.red_team_packs
    ADD CONSTRAINT red_team_packs_pkey PRIMARY KEY (id);


--
-- Name: red_team_rules red_team_rules_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.red_team_rules
    ADD CONSTRAINT red_team_rules_code_key UNIQUE (code);


--
-- Name: red_team_rules red_team_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.red_team_rules
    ADD CONSTRAINT red_team_rules_pkey PRIMARY KEY (id);


--
-- Name: review_selected_findings review_selected_findings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.review_selected_findings
    ADD CONSTRAINT review_selected_findings_pkey PRIMARY KEY (review_id, finding_id);


--
-- Name: reviews reviews_analysis_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_analysis_id_key UNIQUE (analysis_id);


--
-- Name: reviews reviews_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_pkey PRIMARY KEY (id);


--
-- Name: risk_patterns risk_patterns_finding_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_patterns
    ADD CONSTRAINT risk_patterns_finding_id_key UNIQUE (finding_id);


--
-- Name: risk_patterns risk_patterns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_patterns
    ADD CONSTRAINT risk_patterns_pkey PRIMARY KEY (id);


--
-- Name: risk_score_ledger_entries risk_score_ledger_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT risk_score_ledger_entries_pkey PRIMARY KEY (id);


--
-- Name: risk_score_runs risk_score_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_runs
    ADD CONSTRAINT risk_score_runs_pkey PRIMARY KEY (id);


--
-- Name: demo_corpus_artifacts uq_demo_corpus_artifact_evidence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_artifacts
    ADD CONSTRAINT uq_demo_corpus_artifact_evidence UNIQUE (logical_id, evidence_document_id);


--
-- Name: demo_corpus_evidence_mappings uq_demo_corpus_mapping_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_evidence_mappings
    ADD CONSTRAINT uq_demo_corpus_mapping_document UNIQUE (product_logical_id, evidence_document_id);


--
-- Name: demo_corpus_evidence_mappings uq_demo_corpus_mapping_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_evidence_mappings
    ADD CONSTRAINT uq_demo_corpus_mapping_order UNIQUE (product_logical_id, selection_order);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: analysis_executions ux_analysis_executions_attempt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_executions
    ADD CONSTRAINT ux_analysis_executions_attempt UNIQUE (analysis_id, attempt_no);


--
-- Name: analysis_executions ux_analysis_executions_id_analysis; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_executions
    ADD CONSTRAINT ux_analysis_executions_id_analysis UNIQUE (id, analysis_id);


--
-- Name: analysis_executions ux_analysis_executions_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_executions
    ADD CONSTRAINT ux_analysis_executions_token UNIQUE (execution_token);


--
-- Name: analysis_ground_truth_fact_snapshots ux_analysis_ground_truth_fact_snapshots_fact; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_ground_truth_fact_snapshots
    ADD CONSTRAINT ux_analysis_ground_truth_fact_snapshots_fact UNIQUE (analysis_id, ground_truth_fact_id);


--
-- Name: analysis_rag_retrieval_snapshots ux_analysis_rag_snapshots_chunk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_retrieval_snapshots
    ADD CONSTRAINT ux_analysis_rag_snapshots_chunk UNIQUE (rag_run_id, evidence_document_chunk_id);


--
-- Name: analysis_rag_retrieval_snapshots ux_analysis_rag_snapshots_rank; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_retrieval_snapshots
    ADD CONSTRAINT ux_analysis_rag_snapshots_rank UNIQUE (rag_run_id, rank);


--
-- Name: document_batch_item_attempts ux_document_batch_item_attempts_item_attempt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_item_attempts
    ADD CONSTRAINT ux_document_batch_item_attempts_item_attempt UNIQUE (item_id, attempt_no);


--
-- Name: document_batch_items ux_document_batch_items_batch_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_items
    ADD CONSTRAINT ux_document_batch_items_batch_document UNIQUE (batch_id, product_document_id);


--
-- Name: document_batch_items ux_document_batch_items_batch_ordinal; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_items
    ADD CONSTRAINT ux_document_batch_items_batch_ordinal UNIQUE (batch_id, ordinal);


--
-- Name: document_batches ux_document_batches_id_product_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batches
    ADD CONSTRAINT ux_document_batches_id_product_owner UNIQUE (id, product_id, owner_id);


--
-- Name: document_batches ux_document_batches_owner_idempotency_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batches
    ADD CONSTRAINT ux_document_batches_owner_idempotency_key UNIQUE (owner_id, idempotency_key);


--
-- Name: document_extraction_pages ux_document_extraction_pages_run_page; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_pages
    ADD CONSTRAINT ux_document_extraction_pages_run_page UNIQUE (extraction_run_id, page_number);


--
-- Name: document_extraction_runs ux_document_extraction_runs_document_generation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_runs
    ADD CONSTRAINT ux_document_extraction_runs_document_generation UNIQUE (product_document_id, run_generation);


--
-- Name: document_extraction_runs ux_document_extraction_runs_id_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_runs
    ADD CONSTRAINT ux_document_extraction_runs_id_document UNIQUE (id, product_document_id);


--
-- Name: document_source_revisions ux_document_source_revisions_anchor_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_source_revisions
    ADD CONSTRAINT ux_document_source_revisions_anchor_identity UNIQUE (id, product_document_id, source_hash);


--
-- Name: document_source_revisions ux_document_source_revisions_document_revision; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_source_revisions
    ADD CONSTRAINT ux_document_source_revisions_document_revision UNIQUE (product_document_id, revision_number);


--
-- Name: evidence_document_chunks ux_evidence_document_chunks_anchor_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_document_chunks
    ADD CONSTRAINT ux_evidence_document_chunks_anchor_identity UNIQUE (id, evidence_document_id, source_hash);


--
-- Name: evidence_document_chunks ux_evidence_document_chunks_id_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_document_chunks
    ADD CONSTRAINT ux_evidence_document_chunks_id_document UNIQUE (id, evidence_document_id);


--
-- Name: evidence_document_chunks ux_evidence_document_chunks_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_document_chunks
    ADD CONSTRAINT ux_evidence_document_chunks_identity UNIQUE (evidence_document_id, source_hash, chunking_version, embedding_model, chunk_ordinal);


--
-- Name: finding_review_decisions ux_finding_review_decisions_review_finding; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_review_decisions
    ADD CONSTRAINT ux_finding_review_decisions_review_finding UNIQUE (review_id, finding_revision_id);


--
-- Name: findings ux_findings_id_execution; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.findings
    ADD CONSTRAINT ux_findings_id_execution UNIQUE (id, analysis_execution_id);


--
-- Name: ground_truth_facts ux_ground_truth_facts_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ground_truth_facts
    ADD CONSTRAINT ux_ground_truth_facts_document UNIQUE (document_id);


--
-- Name: idempotency_claims ux_idempotency_claims_actor_operation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_claims
    ADD CONSTRAINT ux_idempotency_claims_actor_operation_key UNIQUE (actor_id, operation, idempotency_key);


--
-- Name: product_documents ux_product_documents_id_product; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT ux_product_documents_id_product UNIQUE (id, product_id);


--
-- Name: products ux_products_id_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT ux_products_id_owner UNIQUE (id, owner_id);


--
-- Name: reviews ux_reviews_id_execution; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT ux_reviews_id_execution UNIQUE (id, analysis_execution_id);


--
-- Name: risk_score_ledger_entries ux_risk_score_ledger_entries_harm_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT ux_risk_score_ledger_entries_harm_event UNIQUE (risk_score_run_id, document_claim_anchor_id, policy_rule_id);


--
-- Name: risk_score_runs ux_risk_score_runs_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_runs
    ADD CONSTRAINT ux_risk_score_runs_idempotency UNIQUE (analysis_execution_id, policy_version, input_fingerprint);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_analyses_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analyses_document ON public.analyses USING btree (product_document_id);


--
-- Name: idx_analyses_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analyses_status ON public.analyses USING btree (status);


--
-- Name: idx_analysis_executions_analysis; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analysis_executions_analysis ON public.analysis_executions USING btree (analysis_id, attempt_no DESC);


--
-- Name: idx_analysis_ground_truth_fact_snapshots_analysis; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analysis_ground_truth_fact_snapshots_analysis ON public.analysis_ground_truth_fact_snapshots USING btree (analysis_id);


--
-- Name: idx_analysis_rag_runs_execution; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analysis_rag_runs_execution ON public.analysis_rag_runs USING btree (analysis_execution_id);


--
-- Name: idx_analysis_rag_snapshots_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analysis_rag_snapshots_document ON public.analysis_rag_retrieval_snapshots USING btree (evidence_document_id);


--
-- Name: idx_analysis_rag_snapshots_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analysis_rag_snapshots_run ON public.analysis_rag_retrieval_snapshots USING btree (rag_run_id);


--
-- Name: idx_audit_events_created_at_audit_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_events_created_at_audit_id ON public.audit_events USING btree (created_at DESC, audit_id DESC);


--
-- Name: idx_document_batch_item_attempts_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batch_item_attempts_item ON public.document_batch_item_attempts USING btree (item_id, attempt_no);


--
-- Name: idx_document_batch_items_batch_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batch_items_batch_status ON public.document_batch_items USING btree (batch_id, status, ordinal);


--
-- Name: idx_document_batch_items_due_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batch_items_due_claim ON public.document_batch_items USING btree (due_at, id) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRY_WAIT'::character varying])::text[]));


--
-- Name: idx_document_batch_items_expired_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batch_items_expired_lease ON public.document_batch_items USING btree (lease_until, id) WHERE ((status)::text = 'LEASED'::text);


--
-- Name: idx_document_batch_items_owner_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batch_items_owner_status ON public.document_batch_items USING btree (owner_id, status);


--
-- Name: idx_document_batches_owner_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batches_owner_status ON public.document_batches USING btree (owner_id, status);


--
-- Name: idx_document_batches_product_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_batches_product_created ON public.document_batches USING btree (product_id, created_at DESC);


--
-- Name: idx_document_extraction_runs_document_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_extraction_runs_document_created ON public.document_extraction_runs USING btree (product_document_id, created_at DESC);


--
-- Name: idx_document_extraction_runs_source_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_extraction_runs_source_hash ON public.document_extraction_runs USING btree (source_hash);


--
-- Name: idx_document_source_revisions_source_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_source_revisions_source_hash ON public.document_source_revisions USING btree (source_hash);


--
-- Name: idx_evidence_document_chunks_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_evidence_document_chunks_document ON public.evidence_document_chunks USING btree (evidence_document_id);


--
-- Name: idx_evidence_document_chunks_embedding_cosine; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_evidence_document_chunks_embedding_cosine ON public.evidence_document_chunks USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: idx_evidence_document_chunks_source_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_evidence_document_chunks_source_hash ON public.evidence_document_chunks USING btree (source_hash);


--
-- Name: idx_evidence_references_finding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_evidence_references_finding ON public.evidence_references USING btree (finding_id);


--
-- Name: idx_finding_evidence_anchors_finding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_finding_evidence_anchors_finding ON public.finding_evidence_anchors USING btree (finding_id, id);


--
-- Name: idx_finding_review_decisions_review_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_finding_review_decisions_review_decision ON public.finding_review_decisions USING btree (review_id, decision, finding_revision_id);


--
-- Name: idx_findings_analysis; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_findings_analysis ON public.findings USING btree (analysis_id);


--
-- Name: idx_findings_execution; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_findings_execution ON public.findings USING btree (analysis_execution_id, id);


--
-- Name: idx_guardfit_actions_pattern; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_guardfit_actions_pattern ON public.guardfit_actions USING btree (risk_pattern_id);


--
-- Name: idx_product_documents_product; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_documents_product ON public.product_documents USING btree (product_id);


--
-- Name: idx_products_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_products_owner ON public.products USING btree (owner_id);


--
-- Name: idx_red_team_rules_pack; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_red_team_rules_pack ON public.red_team_rules USING btree (pack_id);


--
-- Name: idx_reviews_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_status ON public.reviews USING btree (status);


--
-- Name: idx_risk_score_ledger_entries_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_risk_score_ledger_entries_run ON public.risk_score_ledger_entries USING btree (risk_score_run_id, id);


--
-- Name: idx_risk_score_runs_execution_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_risk_score_runs_execution_created ON public.risk_score_runs USING btree (analysis_execution_id, created_at DESC, id DESC);


--
-- Name: ux_analyses_document_input_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_analyses_document_input_hash ON public.analyses USING btree (product_document_id, input_hash) WHERE ((status)::text <> 'FAILED'::text);


--
-- Name: ux_finding_evidence_anchors_identity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_finding_evidence_anchors_identity ON public.finding_evidence_anchors USING btree (finding_id, source_role, source_document_id, source_revision_id, evidence_document_id, retrieved_chunk_id, source_hash, page_number, utf8_start_offset, utf8_end_offset, excerpt_hash) NULLS NOT DISTINCT;


--
-- Name: ux_findings_lineage_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_findings_lineage_revision ON public.findings USING btree (lineage_id, revision_number) WHERE (lineage_id IS NOT NULL);


--
-- Name: ux_findings_superseded_once; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_findings_superseded_once ON public.findings USING btree (supersedes_finding_id) WHERE (supersedes_finding_id IS NOT NULL);


--
-- Name: document_batch_item_attempts document_batch_item_attempts_completion_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER document_batch_item_attempts_completion_only BEFORE DELETE OR UPDATE ON public.document_batch_item_attempts FOR EACH ROW EXECUTE FUNCTION public.guard_document_batch_item_attempt_mutation();


--
-- Name: analysis_executions trg_analysis_executions_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_analysis_executions_append_only BEFORE DELETE OR UPDATE ON public.analysis_executions FOR EACH ROW EXECUTE FUNCTION public.reject_analysis_execution_changes();


--
-- Name: analysis_rag_retrieval_snapshots trg_analysis_rag_retrieval_snapshots_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_analysis_rag_retrieval_snapshots_immutable BEFORE DELETE OR UPDATE ON public.analysis_rag_retrieval_snapshots FOR EACH ROW EXECUTE FUNCTION public.reject_analysis_rag_snapshot_changes();


--
-- Name: analysis_rag_runs trg_analysis_rag_runs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_analysis_rag_runs_immutable BEFORE DELETE OR UPDATE ON public.analysis_rag_runs FOR EACH ROW EXECUTE FUNCTION public.reject_analysis_rag_run_changes();


--
-- Name: audit_events trg_audit_events_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_events_append_only BEFORE DELETE OR UPDATE OR TRUNCATE ON public.audit_events FOR EACH STATEMENT EXECUTE FUNCTION public.reject_audit_event_mutation();


--
-- Name: document_extraction_pages trg_document_extraction_pages_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_document_extraction_pages_immutable BEFORE DELETE OR UPDATE ON public.document_extraction_pages FOR EACH ROW EXECUTE FUNCTION public.reject_document_extraction_page_changes();


--
-- Name: document_extraction_runs trg_document_extraction_runs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_document_extraction_runs_immutable BEFORE DELETE OR UPDATE ON public.document_extraction_runs FOR EACH ROW EXECUTE FUNCTION public.reject_document_extraction_run_changes();


--
-- Name: document_source_revisions trg_document_source_revisions_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_document_source_revisions_immutable BEFORE DELETE OR UPDATE ON public.document_source_revisions FOR EACH ROW EXECUTE FUNCTION public.reject_document_source_revision_changes();


--
-- Name: finding_evidence_anchors trg_finding_evidence_anchors_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_finding_evidence_anchors_immutable BEFORE DELETE OR UPDATE ON public.finding_evidence_anchors FOR EACH ROW EXECUTE FUNCTION public.reject_finding_evidence_anchor_changes();


--
-- Name: finding_evidence_anchors trg_finding_evidence_anchors_validate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_finding_evidence_anchors_validate BEFORE INSERT ON public.finding_evidence_anchors FOR EACH ROW EXECUTE FUNCTION public.validate_finding_evidence_anchor();


--
-- Name: finding_review_decisions trg_finding_review_decisions_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_finding_review_decisions_append_only BEFORE DELETE OR UPDATE ON public.finding_review_decisions FOR EACH ROW EXECUTE FUNCTION public.reject_finding_review_decision_changes();


--
-- Name: findings trg_findings_execution_bound_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_findings_execution_bound_immutable BEFORE DELETE OR UPDATE ON public.findings FOR EACH ROW EXECUTE FUNCTION public.reject_execution_bound_finding_changes();


--
-- Name: findings trg_findings_validate_revision; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_findings_validate_revision BEFORE INSERT ON public.findings FOR EACH ROW EXECUTE FUNCTION public.validate_finding_revision();


--
-- Name: reviews trg_reviews_execution_boundary; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_reviews_execution_boundary BEFORE INSERT OR UPDATE ON public.reviews FOR EACH ROW EXECUTE FUNCTION public.validate_review_execution_boundary();


--
-- Name: risk_score_ledger_entries trg_risk_score_ledger_entries_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_risk_score_ledger_entries_immutable BEFORE DELETE OR UPDATE ON public.risk_score_ledger_entries FOR EACH ROW EXECUTE FUNCTION public.reject_risk_score_record_changes();


--
-- Name: risk_score_ledger_entries trg_risk_score_ledger_entries_validate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_risk_score_ledger_entries_validate BEFORE INSERT ON public.risk_score_ledger_entries FOR EACH ROW EXECUTE FUNCTION public.validate_risk_score_ledger_entry();


--
-- Name: risk_score_runs trg_risk_score_runs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_risk_score_runs_immutable BEFORE DELETE OR UPDATE ON public.risk_score_runs FOR EACH ROW EXECUTE FUNCTION public.reject_risk_score_record_changes();


--
-- Name: risk_score_runs trg_risk_score_runs_validate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_risk_score_runs_validate BEFORE INSERT ON public.risk_score_runs FOR EACH ROW EXECUTE FUNCTION public.validate_risk_score_run();


--
-- Name: analyses analyses_current_successful_execution_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analyses
    ADD CONSTRAINT analyses_current_successful_execution_id_fkey FOREIGN KEY (current_successful_execution_id) REFERENCES public.analysis_executions(id);


--
-- Name: analyses analyses_product_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analyses
    ADD CONSTRAINT analyses_product_document_id_fkey FOREIGN KEY (product_document_id) REFERENCES public.product_documents(id);


--
-- Name: analyses analyses_red_team_pack_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analyses
    ADD CONSTRAINT analyses_red_team_pack_id_fkey FOREIGN KEY (red_team_pack_id) REFERENCES public.red_team_packs(id);


--
-- Name: analysis_evidence_documents analysis_evidence_documents_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_evidence_documents
    ADD CONSTRAINT analysis_evidence_documents_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id) ON DELETE CASCADE;


--
-- Name: analysis_evidence_documents analysis_evidence_documents_evidence_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_evidence_documents
    ADD CONSTRAINT analysis_evidence_documents_evidence_document_id_fkey FOREIGN KEY (evidence_document_id) REFERENCES public.evidence_documents(id);


--
-- Name: analysis_executions analysis_executions_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_executions
    ADD CONSTRAINT analysis_executions_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id);


--
-- Name: analysis_ground_truth_fact_snapshots analysis_ground_truth_fact_snapshots_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_ground_truth_fact_snapshots
    ADD CONSTRAINT analysis_ground_truth_fact_snapshots_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id) ON DELETE CASCADE;


--
-- Name: analysis_ground_truth_fact_snapshots analysis_ground_truth_fact_snapshots_ground_truth_fact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_ground_truth_fact_snapshots
    ADD CONSTRAINT analysis_ground_truth_fact_snapshots_ground_truth_fact_id_fkey FOREIGN KEY (ground_truth_fact_id) REFERENCES public.ground_truth_facts(id);


--
-- Name: analysis_personas analysis_personas_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_personas
    ADD CONSTRAINT analysis_personas_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id) ON DELETE CASCADE;


--
-- Name: analysis_personas analysis_personas_persona_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_personas
    ADD CONSTRAINT analysis_personas_persona_template_id_fkey FOREIGN KEY (persona_template_id) REFERENCES public.persona_templates(id);


--
-- Name: analysis_rag_retrieval_snapshots analysis_rag_retrieval_snapshots_evidence_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_retrieval_snapshots
    ADD CONSTRAINT analysis_rag_retrieval_snapshots_evidence_document_id_fkey FOREIGN KEY (evidence_document_id) REFERENCES public.evidence_documents(id);


--
-- Name: analysis_rag_retrieval_snapshots analysis_rag_retrieval_snapshots_rag_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_retrieval_snapshots
    ADD CONSTRAINT analysis_rag_retrieval_snapshots_rag_run_id_fkey FOREIGN KEY (rag_run_id) REFERENCES public.analysis_rag_runs(id) ON DELETE CASCADE;


--
-- Name: analysis_rag_runs analysis_rag_runs_analysis_execution_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_runs
    ADD CONSTRAINT analysis_rag_runs_analysis_execution_id_fkey FOREIGN KEY (analysis_execution_id) REFERENCES public.analysis_executions(id);


--
-- Name: analysis_rag_runs analysis_rag_runs_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_runs
    ADD CONSTRAINT analysis_rag_runs_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id) ON DELETE CASCADE;


--
-- Name: demo_corpus_artifacts demo_corpus_artifacts_evidence_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_artifacts
    ADD CONSTRAINT demo_corpus_artifacts_evidence_document_id_fkey FOREIGN KEY (evidence_document_id) REFERENCES public.evidence_documents(id);


--
-- Name: demo_corpus_evidence_mappings demo_corpus_evidence_mappings_product_logical_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_evidence_mappings
    ADD CONSTRAINT demo_corpus_evidence_mappings_product_logical_id_fkey FOREIGN KEY (product_logical_id) REFERENCES public.demo_corpus_artifacts(logical_id);


--
-- Name: document_batch_item_attempts document_batch_item_attempts_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_item_attempts
    ADD CONSTRAINT document_batch_item_attempts_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.document_batch_items(id) ON DELETE CASCADE;


--
-- Name: document_extraction_pages document_extraction_pages_extraction_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_pages
    ADD CONSTRAINT document_extraction_pages_extraction_run_id_fkey FOREIGN KEY (extraction_run_id) REFERENCES public.document_extraction_runs(id) ON DELETE CASCADE;


--
-- Name: document_extraction_runs document_extraction_runs_product_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_extraction_runs
    ADD CONSTRAINT document_extraction_runs_product_document_id_fkey FOREIGN KEY (product_document_id) REFERENCES public.product_documents(id) ON DELETE CASCADE;


--
-- Name: document_source_revisions document_source_revisions_product_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_source_revisions
    ADD CONSTRAINT document_source_revisions_product_document_id_fkey FOREIGN KEY (product_document_id) REFERENCES public.product_documents(id) ON DELETE CASCADE;


--
-- Name: evidence_document_chunks evidence_document_chunks_evidence_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_document_chunks
    ADD CONSTRAINT evidence_document_chunks_evidence_document_id_fkey FOREIGN KEY (evidence_document_id) REFERENCES public.evidence_documents(id) ON DELETE CASCADE;


--
-- Name: evidence_references evidence_references_evidence_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_references
    ADD CONSTRAINT evidence_references_evidence_document_id_fkey FOREIGN KEY (evidence_document_id) REFERENCES public.evidence_documents(id);


--
-- Name: evidence_references evidence_references_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence_references
    ADD CONSTRAINT evidence_references_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.findings(id) ON DELETE CASCADE;


--
-- Name: finding_affected_personas finding_affected_personas_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_affected_personas
    ADD CONSTRAINT finding_affected_personas_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.findings(id) ON DELETE CASCADE;


--
-- Name: finding_affected_personas finding_affected_personas_persona_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_affected_personas
    ADD CONSTRAINT finding_affected_personas_persona_template_id_fkey FOREIGN KEY (persona_template_id) REFERENCES public.persona_templates(id);


--
-- Name: finding_evidence_anchors finding_evidence_anchors_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence_anchors
    ADD CONSTRAINT finding_evidence_anchors_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.findings(id);


--
-- Name: finding_review_decisions finding_review_decisions_reviewer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_review_decisions
    ADD CONSTRAINT finding_review_decisions_reviewer_id_fkey FOREIGN KEY (reviewer_id) REFERENCES public.users(id);


--
-- Name: findings findings_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.findings
    ADD CONSTRAINT findings_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id) ON DELETE CASCADE;


--
-- Name: analysis_rag_retrieval_snapshots fk_analysis_rag_snapshots_chunk_document; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analysis_rag_retrieval_snapshots
    ADD CONSTRAINT fk_analysis_rag_snapshots_chunk_document FOREIGN KEY (evidence_document_chunk_id, evidence_document_id) REFERENCES public.evidence_document_chunks(id, evidence_document_id);


--
-- Name: demo_corpus_evidence_mappings fk_demo_corpus_mapping_evidence; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_corpus_evidence_mappings
    ADD CONSTRAINT fk_demo_corpus_mapping_evidence FOREIGN KEY (evidence_logical_id, evidence_document_id) REFERENCES public.demo_corpus_artifacts(logical_id, evidence_document_id);


--
-- Name: document_batch_items fk_document_batch_items_batch_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_items
    ADD CONSTRAINT fk_document_batch_items_batch_owner FOREIGN KEY (batch_id, product_id, owner_id) REFERENCES public.document_batches(id, product_id, owner_id) ON DELETE CASCADE;


--
-- Name: document_batch_items fk_document_batch_items_document_product; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batch_items
    ADD CONSTRAINT fk_document_batch_items_document_product FOREIGN KEY (product_document_id, product_id) REFERENCES public.product_documents(id, product_id);


--
-- Name: document_batches fk_document_batches_product_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_batches
    ADD CONSTRAINT fk_document_batches_product_owner FOREIGN KEY (product_id, owner_id) REFERENCES public.products(id, owner_id);


--
-- Name: finding_evidence_anchors fk_finding_evidence_anchors_document_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence_anchors
    ADD CONSTRAINT fk_finding_evidence_anchors_document_source FOREIGN KEY (source_revision_id, source_document_id, source_hash) REFERENCES public.document_source_revisions(id, product_document_id, source_hash);


--
-- Name: finding_evidence_anchors fk_finding_evidence_anchors_policy_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence_anchors
    ADD CONSTRAINT fk_finding_evidence_anchors_policy_source FOREIGN KEY (retrieved_chunk_id, evidence_document_id, source_hash) REFERENCES public.evidence_document_chunks(id, evidence_document_id, source_hash);


--
-- Name: finding_review_decisions fk_finding_review_decisions_finding_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_review_decisions
    ADD CONSTRAINT fk_finding_review_decisions_finding_execution FOREIGN KEY (finding_revision_id, analysis_execution_id) REFERENCES public.findings(id, analysis_execution_id);


--
-- Name: finding_review_decisions fk_finding_review_decisions_review_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_review_decisions
    ADD CONSTRAINT fk_finding_review_decisions_review_execution FOREIGN KEY (review_id, analysis_execution_id) REFERENCES public.reviews(id, analysis_execution_id);


--
-- Name: findings fk_findings_execution_analysis; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.findings
    ADD CONSTRAINT fk_findings_execution_analysis FOREIGN KEY (analysis_execution_id, analysis_id) REFERENCES public.analysis_executions(id, analysis_id);


--
-- Name: findings fk_findings_supersedes; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.findings
    ADD CONSTRAINT fk_findings_supersedes FOREIGN KEY (supersedes_finding_id) REFERENCES public.findings(id);


--
-- Name: product_documents fk_product_documents_confirmed_extraction_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT fk_product_documents_confirmed_extraction_run FOREIGN KEY (confirmed_extraction_run_id, id) REFERENCES public.document_extraction_runs(id, product_document_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: product_documents fk_product_documents_current_extraction_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT fk_product_documents_current_extraction_run FOREIGN KEY (current_extraction_run_id, id) REFERENCES public.document_extraction_runs(id, product_document_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: reviews fk_reviews_execution_analysis; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT fk_reviews_execution_analysis FOREIGN KEY (analysis_execution_id, analysis_id) REFERENCES public.analysis_executions(id, analysis_id);


--
-- Name: ground_truth_facts ground_truth_facts_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ground_truth_facts
    ADD CONSTRAINT ground_truth_facts_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: ground_truth_facts ground_truth_facts_decided_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ground_truth_facts
    ADD CONSTRAINT ground_truth_facts_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES public.users(id);


--
-- Name: ground_truth_facts ground_truth_facts_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ground_truth_facts
    ADD CONSTRAINT ground_truth_facts_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.product_documents(id) ON DELETE CASCADE;


--
-- Name: guardfit_actions guardfit_actions_risk_pattern_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.guardfit_actions
    ADD CONSTRAINT guardfit_actions_risk_pattern_id_fkey FOREIGN KEY (risk_pattern_id) REFERENCES public.risk_patterns(id) ON DELETE CASCADE;


--
-- Name: guardfit_actions guardfit_actions_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.guardfit_actions
    ADD CONSTRAINT guardfit_actions_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- Name: idempotency_claims idempotency_claims_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_claims
    ADD CONSTRAINT idempotency_claims_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id);


--
-- Name: idempotency_claims idempotency_claims_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_claims
    ADD CONSTRAINT idempotency_claims_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id);


--
-- Name: product_documents product_documents_confirmed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT product_documents_confirmed_by_fkey FOREIGN KEY (confirmed_by) REFERENCES public.users(id);


--
-- Name: product_documents product_documents_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT product_documents_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON DELETE CASCADE;


--
-- Name: products products_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id);


--
-- Name: red_team_rules red_team_rules_pack_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.red_team_rules
    ADD CONSTRAINT red_team_rules_pack_id_fkey FOREIGN KEY (pack_id) REFERENCES public.red_team_packs(id) ON DELETE CASCADE;


--
-- Name: review_selected_findings review_selected_findings_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.review_selected_findings
    ADD CONSTRAINT review_selected_findings_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.findings(id) ON DELETE CASCADE;


--
-- Name: review_selected_findings review_selected_findings_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.review_selected_findings
    ADD CONSTRAINT review_selected_findings_review_id_fkey FOREIGN KEY (review_id) REFERENCES public.reviews(id) ON DELETE CASCADE;


--
-- Name: reviews reviews_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.analyses(id) ON DELETE CASCADE;


--
-- Name: reviews reviews_reviewer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_reviewer_id_fkey FOREIGN KEY (reviewer_id) REFERENCES public.users(id);


--
-- Name: risk_patterns risk_patterns_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_patterns
    ADD CONSTRAINT risk_patterns_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.findings(id);


--
-- Name: risk_patterns risk_patterns_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_patterns
    ADD CONSTRAINT risk_patterns_review_id_fkey FOREIGN KEY (review_id) REFERENCES public.reviews(id) ON DELETE SET NULL;


--
-- Name: risk_score_ledger_entries risk_score_ledger_entries_document_claim_anchor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT risk_score_ledger_entries_document_claim_anchor_id_fkey FOREIGN KEY (document_claim_anchor_id) REFERENCES public.finding_evidence_anchors(id);


--
-- Name: risk_score_ledger_entries risk_score_ledger_entries_finding_review_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT risk_score_ledger_entries_finding_review_decision_id_fkey FOREIGN KEY (finding_review_decision_id) REFERENCES public.finding_review_decisions(id);


--
-- Name: risk_score_ledger_entries risk_score_ledger_entries_finding_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT risk_score_ledger_entries_finding_revision_id_fkey FOREIGN KEY (finding_revision_id) REFERENCES public.findings(id);


--
-- Name: risk_score_ledger_entries risk_score_ledger_entries_policy_requirement_anchor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT risk_score_ledger_entries_policy_requirement_anchor_id_fkey FOREIGN KEY (policy_requirement_anchor_id) REFERENCES public.finding_evidence_anchors(id);


--
-- Name: risk_score_ledger_entries risk_score_ledger_entries_risk_score_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_ledger_entries
    ADD CONSTRAINT risk_score_ledger_entries_risk_score_run_id_fkey FOREIGN KEY (risk_score_run_id) REFERENCES public.risk_score_runs(id);


--
-- Name: risk_score_runs risk_score_runs_analysis_execution_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_score_runs
    ADD CONSTRAINT risk_score_runs_analysis_execution_id_fkey FOREIGN KEY (analysis_execution_id) REFERENCES public.analysis_executions(id);


--
-- PostgreSQL database dump complete
--

\unrestrict NLSxFJd9efHarbbtNFzkY6EvMQ9bEP6X3NGibyrRB0oXutA5Qjb1IbtP2QNSdjC

