-- Normalize job scope so deletion can fence and erase queued work, then make
-- pipeline/job mappings a concurrency-safe, study-consistent state machine.

ALTER TABLE jobs
    ADD COLUMN study_id UUID,
    ADD COLUMN participant_ids TEXT[];

CREATE OR REPLACE FUNCTION chronicle_sync_job_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    raw_study_id TEXT;
    participant_values JSONB;
BEGIN
    raw_study_id := NULLIF(btrim(NEW.definition ->> 'studyId'), '');
    IF raw_study_id IS NULL THEN
        NEW.study_id := NULL;
    ELSE
        BEGIN
            NEW.study_id := raw_study_id::UUID;
        EXCEPTION WHEN invalid_text_representation THEN
            RAISE EXCEPTION 'Job definition contains an invalid studyId'
                USING ERRCODE = '23514';
        END;
    END IF;

    participant_values := NEW.definition -> 'participantIds';
    IF participant_values IS NOT NULL THEN
        IF jsonb_typeof(participant_values) <> 'array'
           OR EXISTS (
               SELECT 1
               FROM jsonb_array_elements(participant_values) AS element(value)
               WHERE jsonb_typeof(element.value) <> 'string'
                  OR btrim(element.value #>> '{}') = ''
           )
        THEN
            RAISE EXCEPTION 'Job definition participantIds must be an array of non-empty strings'
                USING ERRCODE = '23514';
        END IF;
        NEW.participant_ids := ARRAY(
            SELECT element.value #>> '{}'
            FROM jsonb_array_elements(participant_values) WITH ORDINALITY AS element(value, ordinal)
            ORDER BY element.ordinal
        );
    ELSIF NEW.definition -> 'participantId' IS NOT NULL THEN
        IF jsonb_typeof(NEW.definition -> 'participantId') <> 'string'
           OR btrim(NEW.definition ->> 'participantId') = ''
        THEN
            RAISE EXCEPTION 'Job definition participantId must be a non-empty string'
                USING ERRCODE = '23514';
        END IF;
        NEW.participant_ids := ARRAY[NEW.definition ->> 'participantId'];
    ELSE
        NEW.participant_ids := ARRAY[]::TEXT[];
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS chronicle_00_sync_job_scope ON jobs;
CREATE TRIGGER chronicle_00_sync_job_scope
    BEFORE INSERT OR UPDATE OF definition ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_sync_job_scope();

-- Fail the migration on malformed durable definitions rather than deriving an
-- incomplete scope that could later escape erasure.
DROP TRIGGER IF EXISTS chronicle_guard_pipeline_job_definition ON jobs;
UPDATE jobs SET definition = definition;

ALTER TABLE jobs
    ALTER COLUMN participant_ids SET DEFAULT ARRAY[]::TEXT[],
    ALTER COLUMN participant_ids SET NOT NULL;

CREATE INDEX jobs_study_scope_idx
    ON jobs (study_id);
CREATE INDEX jobs_participant_scope_idx
    ON jobs USING GIN (participant_ids);

CREATE OR REPLACE FUNCTION chronicle_guard_job_scope_columns()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.definition IS NOT DISTINCT FROM OLD.definition THEN
        RAISE EXCEPTION 'Job scope columns are derived from definition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS chronicle_guard_job_scope_columns ON jobs;
CREATE TRIGGER chronicle_guard_job_scope_columns
    BEFORE UPDATE OF study_id, participant_ids ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_guard_job_scope_columns();

CREATE OR REPLACE FUNCTION chronicle_guard_job_against_erasure()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.study_id IS NULL OR chronicle_is_deletion_worker() THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock_shared(
        hashtextextended('chronicle-deletion:' || NEW.study_id::text, 0)
    );

    IF EXISTS (
        SELECT 1
        FROM public.data_deletion_operations operation
        WHERE operation.study_id = NEW.study_id
          AND (
              operation.mode = 'STUDY_ERASURE'
              OR operation.participant_id = ANY(NEW.participant_ids)
              OR (
                  operation.participant_block_token IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM unnest(NEW.participant_ids) participant_id
                      WHERE operation.participant_block_token =
                          md5(NEW.study_id::text || ':' || participant_id)
                  )
              )
          )
          AND (
              (
                  operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                  AND operation.status IN (
                      'QUARANTINED', 'HELD', 'READY', 'ERASING',
                      'VERIFYING', 'FAILED', 'COMPLETED'
                  )
              )
              OR (
                  operation.mode = 'COLLECTED_DATA_PURGE'
                  AND operation.status IN ('READY', 'ERASING', 'VERIFYING', 'FAILED')
              )
          )
    ) THEN
        RAISE EXCEPTION 'Job creation or scope mutation is blocked by an erasure operation'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION chronicle_guard_job_against_erasure() FROM PUBLIC;

DROP TRIGGER IF EXISTS chronicle_20_guard_job_against_erasure ON jobs;
CREATE TRIGGER chronicle_20_guard_job_against_erasure
    BEFORE INSERT OR UPDATE OF definition ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_guard_job_against_erasure();

-- Preserve preexisting terminal orphan/invalid run history explicitly. New
-- mappings can never set this marker and must satisfy the strict mapping gate.
ALTER TABLE pipeline_runs
    ADD COLUMN legacy_orphaned_job_at TIMESTAMPTZ,
    ADD CONSTRAINT pipeline_runs_legacy_orphan_terminal_chk
        CHECK (
            legacy_orphaned_job_at IS NULL
            OR status IN ('COMPLETED', 'FAILED')
        );

-- Active invalid mappings cannot execute. Terminalize the run first, then mark
-- its relationship as detached legacy history.
UPDATE pipeline_runs run
SET status = 'FAILED',
    completed_at = now(),
    error_message = 'Legacy pipeline mapping is missing, cross-study, or not a pipeline job'
WHERE run.status IN ('PENDING', 'RUNNING')
  AND (
      NOT EXISTS (
          SELECT 1 FROM jobs job WHERE job.job_id = run.job_id
      )
      OR EXISTS (
          SELECT 1
          FROM jobs job
          WHERE job.job_id = run.job_id
            AND (
                COALESCE(job.definition ->> '@type', job.definition ->> '@class', '') NOT IN (
                    'PipelineJobDefinition',
                    'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
                )
                OR job.study_id IS DISTINCT FROM run.study_id
            )
      )
  );

UPDATE pipeline_runs run
SET legacy_orphaned_job_at = now()
WHERE NOT EXISTS (
          SELECT 1 FROM jobs job WHERE job.job_id = run.job_id
      )
   OR EXISTS (
          SELECT 1
          FROM jobs job
          WHERE job.job_id = run.job_id
            AND (
                COALESCE(job.definition ->> '@type', job.definition ->> '@class', '') NOT IN (
                    'PipelineJobDefinition',
                    'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
                )
                OR job.study_id IS DISTINCT FROM run.study_id
            )
      );

-- Reconcile every valid mapping pair, including the terminal-run/active-job
-- contradictions that V71 could leave runnable.
DROP TRIGGER IF EXISTS chronicle_jobs_status_transition ON jobs;

UPDATE pipeline_runs run
SET status = 'FAILED',
    completed_at = now(),
    error_message = 'Pipeline run and backing job state required reconciliation'
FROM jobs job
WHERE run.job_id = job.job_id
  AND run.legacy_orphaned_job_at IS NULL
  AND (
      (run.status = 'RUNNING' AND job.status <> 'RUNNING')
      OR (
          run.status = 'PENDING'
          AND job.status NOT IN ('PENDING', 'PAUSED', 'RUNNING')
      )
  );

UPDATE jobs job
SET status = 'FINISHED',
    updated_at = now(),
    completed_at = CASE
        WHEN isfinite(run.completed_at) THEN run.completed_at
        ELSE now()
    END,
    lease_token = NULL,
    lease_expires_at = NULL,
    message = CASE
        WHEN COALESCE(job.message, '') = ''
            THEN 'Job state reconciled from completed pipeline run'
        ELSE job.message
    END
FROM pipeline_runs run
WHERE run.job_id = job.job_id
  AND run.legacy_orphaned_job_at IS NULL
  AND run.status = 'COMPLETED'
  AND job.status <> 'FINISHED';

UPDATE jobs job
SET status = 'CANCELED',
    updated_at = now(),
    completed_at = CASE
        WHEN isfinite(run.completed_at) THEN run.completed_at
        ELSE now()
    END,
    lease_token = NULL,
    lease_expires_at = NULL,
    message = CASE
        WHEN COALESCE(job.message, '') = ''
            THEN COALESCE(run.error_message, 'Job state reconciled from failed pipeline run')
        ELSE job.message
    END
FROM pipeline_runs run
WHERE run.job_id = job.job_id
  AND run.legacy_orphaned_job_at IS NULL
  AND run.status = 'FAILED'
  AND job.status NOT IN ('CANCELED', 'STOPPING');

-- An active pipeline definition with no valid mapping cannot be dispatched.
UPDATE jobs job
SET status = 'CANCELED',
    updated_at = now(),
    completed_at = now(),
    lease_token = NULL,
    lease_expires_at = NULL,
    message = CASE
        WHEN COALESCE(job.message, '') = '' THEN 'Pipeline run mapping is missing or invalid'
        ELSE job.message
    END
WHERE job.status IN ('PENDING', 'PAUSED', 'RUNNING')
  AND COALESCE(job.definition ->> '@type', job.definition ->> '@class', '') IN (
      'PipelineJobDefinition',
      'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM pipeline_runs run
      WHERE run.job_id = job.job_id
        AND run.legacy_orphaned_job_at IS NULL
  );

CREATE TRIGGER chronicle_jobs_status_transition
    BEFORE UPDATE OF status ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_job_status_transition();

CREATE OR REPLACE FUNCTION chronicle_validate_pipeline_job_mapping()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    mapped_type TEXT;
    mapped_study_id UUID;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('chronicle-pipeline-job:' || NEW.job_id::text, 0)
    );

    SELECT
        COALESCE(job.definition ->> '@type', job.definition ->> '@class'),
        job.study_id
    INTO mapped_type, mapped_study_id
    FROM public.jobs job
    WHERE job.job_id = NEW.job_id;

    IF NOT FOUND
       OR mapped_type NOT IN (
           'PipelineJobDefinition',
           'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
       )
    THEN
        RAISE EXCEPTION 'Pipeline run must reference an existing pipeline job'
            USING ERRCODE = '23503';
    END IF;
    IF mapped_study_id IS DISTINCT FROM NEW.study_id THEN
        RAISE EXCEPTION 'Pipeline run study must match its backing job study'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.legacy_orphaned_job_at IS NOT NULL THEN
        RAISE EXCEPTION 'New pipeline mappings cannot be marked as legacy orphans'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS chronicle_validate_pipeline_job_mapping ON pipeline_runs;
CREATE TRIGGER chronicle_validate_pipeline_job_mapping
    BEFORE INSERT OR UPDATE OF job_id, study_id ON pipeline_runs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_pipeline_job_mapping();

CREATE OR REPLACE FUNCTION chronicle_guard_pipeline_job_definition()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    mapped_study_id UUID;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('chronicle-pipeline-job:' || NEW.job_id::text, 0)
    );
    SELECT run.study_id
    INTO mapped_study_id
    FROM public.pipeline_runs run
    WHERE run.job_id = NEW.job_id
      AND run.legacy_orphaned_job_at IS NULL;

    IF FOUND
       AND (
           COALESCE(NEW.definition ->> '@type', NEW.definition ->> '@class', '') NOT IN (
               'PipelineJobDefinition',
               'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
           )
           OR NEW.study_id IS DISTINCT FROM mapped_study_id
       )
    THEN
        RAISE EXCEPTION 'A mapped pipeline job must retain its pipeline type and study'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS chronicle_guard_pipeline_job_definition ON jobs;
CREATE TRIGGER chronicle_guard_pipeline_job_definition
    BEFORE UPDATE OF definition ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_guard_pipeline_job_definition();

CREATE OR REPLACE FUNCTION chronicle_guard_active_pipeline_job_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('chronicle-pipeline-job:' || OLD.job_id::text, 0)
    );
    IF EXISTS (
        SELECT 1
        FROM pipeline_runs
        WHERE job_id = OLD.job_id
          AND legacy_orphaned_job_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Cannot delete job % while its pipeline run is retained', OLD.job_id
            USING ERRCODE = '23503';
    END IF;
    RETURN OLD;
END
$$;

CREATE OR REPLACE FUNCTION chronicle_fail_pipeline_for_unrecoverable_job()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.status IN ('CANCELED', 'STOPPING')
       AND OLD.status IS DISTINCT FROM NEW.status
    THEN
        UPDATE pipeline_runs
        SET status = 'FAILED',
            completed_at = now(),
            error_message = left(
                COALESCE(
                    NULLIF(NEW.message, ''),
                    CASE
                        WHEN NEW.status = 'STOPPING'
                            THEN 'Pipeline backing job requires reconciliation'
                        ELSE 'Pipeline backing job was canceled'
                    END
                ),
                4096
            )
        WHERE job_id = NEW.job_id
          AND legacy_orphaned_job_at IS NULL
          AND status IN ('PENDING', 'RUNNING');
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS chronicle_fail_pipeline_on_job_cancel ON jobs;
DROP FUNCTION IF EXISTS chronicle_fail_pipeline_for_canceled_job();
DROP TRIGGER IF EXISTS chronicle_fail_pipeline_on_job_reconciliation ON jobs;
CREATE TRIGGER chronicle_fail_pipeline_on_job_reconciliation
    AFTER UPDATE OF status ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_fail_pipeline_for_unrecoverable_job();

CREATE OR REPLACE FUNCTION chronicle_validate_pipeline_job_pair()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    checked_job_id UUID;
    job_status TEXT;
    job_type TEXT;
    job_study_id UUID;
    run_status TEXT;
    run_study_id UUID;
    run_is_legacy BOOLEAN;
BEGIN
    checked_job_id := CASE
        WHEN TG_TABLE_NAME = 'jobs' THEN COALESCE(NEW.job_id, OLD.job_id)
        ELSE COALESCE(NEW.job_id, OLD.job_id)
    END;

    SELECT
        job.status,
        COALESCE(job.definition ->> '@type', job.definition ->> '@class'),
        job.study_id
    INTO job_status, job_type, job_study_id
    FROM jobs job
    WHERE job.job_id = checked_job_id;

    SELECT
        run.status,
        run.study_id,
        run.legacy_orphaned_job_at IS NOT NULL
    INTO run_status, run_study_id, run_is_legacy
    FROM pipeline_runs run
    WHERE run.job_id = checked_job_id;

    IF run_status IS NULL THEN
        IF job_status IS NOT NULL
           AND job_type IN (
               'PipelineJobDefinition',
               'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
           )
           AND job_status IN ('PENDING', 'PAUSED', 'RUNNING', 'STOPPING')
        THEN
            RAISE EXCEPTION 'Active pipeline job % has no pipeline run', checked_job_id
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF run_is_legacy THEN
        IF run_status NOT IN ('COMPLETED', 'FAILED') THEN
            RAISE EXCEPTION 'Legacy orphan pipeline run % must be terminal', checked_job_id
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF job_status IS NULL
       OR job_type NOT IN (
           'PipelineJobDefinition',
           'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
       )
       OR job_study_id IS DISTINCT FROM run_study_id
    THEN
        RAISE EXCEPTION 'Pipeline run % has no matching study-scoped pipeline job', checked_job_id
            USING ERRCODE = '23514';
    END IF;

    IF NOT (
        (run_status = 'PENDING' AND job_status IN ('PENDING', 'PAUSED', 'RUNNING'))
        OR (run_status = 'RUNNING' AND job_status = 'RUNNING')
        OR (run_status = 'COMPLETED' AND job_status = 'FINISHED')
        OR (run_status = 'FAILED' AND job_status IN ('CANCELED', 'STOPPING'))
    ) THEN
        RAISE EXCEPTION 'Invalid pipeline/job state pair: % / %', run_status, job_status
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS chronicle_pipeline_job_pair_from_jobs ON jobs;
CREATE CONSTRAINT TRIGGER chronicle_pipeline_job_pair_from_jobs
    AFTER INSERT OR UPDATE OR DELETE ON jobs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_pipeline_job_pair();

DROP TRIGGER IF EXISTS chronicle_pipeline_job_pair_from_runs ON pipeline_runs;
CREATE CONSTRAINT TRIGGER chronicle_pipeline_job_pair_from_runs
    AFTER INSERT OR UPDATE OR DELETE ON pipeline_runs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_pipeline_job_pair();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        -- Erasure workers use the restricted application role plus a dedicated
        -- RLS identity; they need to count and delete normalized job scope.
        GRANT SELECT, DELETE ON jobs, pipeline_runs TO chronicle_app;
    END IF;
END
$$;
