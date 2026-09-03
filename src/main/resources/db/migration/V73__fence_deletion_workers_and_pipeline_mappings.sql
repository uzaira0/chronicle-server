-- Fence deletion workers so a crashed or slow worker cannot race cancellation,
-- retention holds, or a replacement worker. Also make every new pipeline run
-- prove that its backing job exists and is actually a pipeline job.

ALTER TABLE data_deletion_operations
    ADD COLUMN IF NOT EXISTS worker_lease_token UUID,
    ADD COLUMN IF NOT EXISTS worker_lease_expires_at TIMESTAMPTZ;

-- A pre-migration active row has no durable owner. Resume it through the
-- ordinary FAILED retry path instead of pretending that the old worker lives.
UPDATE data_deletion_operations
SET status = 'FAILED',
    failure_code = COALESCE(failure_code, 'WorkerRestarted'),
    next_attempt_at = now(),
    updated_at = now(),
    worker_lease_token = NULL,
    worker_lease_expires_at = NULL
WHERE status IN ('ERASING', 'VERIFYING');

-- HELD was historically used as a lossy copy of the operation state. Active
-- hold rows already stop claims, so restore a truthful resumable state.
UPDATE data_deletion_operations operation
SET status = CASE
        WHEN operation.started_at IS NOT NULL OR operation.operation_attempt_count > 0
            THEN 'FAILED'
        WHEN operation.quarantine_until <= now() THEN 'READY'
        ELSE 'QUARANTINED'
    END,
    failure_code = CASE
        WHEN operation.started_at IS NOT NULL OR operation.operation_attempt_count > 0
            THEN COALESCE(operation.failure_code, 'LegacyHeldOperation')
        ELSE operation.failure_code
    END,
    next_attempt_at = CASE
        WHEN operation.started_at IS NOT NULL OR operation.operation_attempt_count > 0
            THEN now()
        ELSE operation.next_attempt_at
    END,
    updated_at = now(),
    worker_lease_token = NULL,
    worker_lease_expires_at = NULL
WHERE operation.status = 'HELD';

UPDATE data_deletion_operations
SET worker_lease_token = NULL,
    worker_lease_expires_at = NULL
WHERE status NOT IN ('ERASING', 'VERIFYING');

ALTER TABLE data_deletion_operations
    DROP CONSTRAINT IF EXISTS data_deletion_worker_lease_state_chk,
    ADD CONSTRAINT data_deletion_worker_lease_state_chk
        CHECK (
            (
                status IN ('ERASING', 'VERIFYING')
                AND worker_lease_token IS NOT NULL
                AND worker_lease_expires_at IS NOT NULL
                AND isfinite(worker_lease_expires_at)
                AND worker_lease_expires_at > updated_at
            )
            OR
            (
                status NOT IN ('ERASING', 'VERIFYING')
                AND worker_lease_token IS NULL
                AND worker_lease_expires_at IS NULL
            )
        );

CREATE INDEX IF NOT EXISTS data_deletion_expired_worker_lease_idx
    ON data_deletion_operations (worker_lease_expires_at, created_at)
    WHERE status IN ('ERASING', 'VERIFYING');

CREATE OR REPLACE FUNCTION chronicle_validate_pipeline_job_mapping()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.jobs job
        WHERE job.job_id = NEW.job_id
          AND COALESCE(
              job.definition ->> '@type',
              job.definition ->> '@class'
          ) IN (
              'PipelineJobDefinition',
              'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
          )
    ) THEN
        RAISE EXCEPTION 'Pipeline run must reference an existing pipeline job'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS chronicle_validate_pipeline_job_mapping ON pipeline_runs;
CREATE TRIGGER chronicle_validate_pipeline_job_mapping
    BEFORE INSERT OR UPDATE OF job_id ON pipeline_runs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_pipeline_job_mapping();

CREATE OR REPLACE FUNCTION chronicle_guard_pipeline_job_definition()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.pipeline_runs run
        WHERE run.job_id = NEW.job_id
    )
       AND COALESCE(
           NEW.definition ->> '@type',
           NEW.definition ->> '@class',
           ''
       ) NOT IN (
           'PipelineJobDefinition',
           'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
       )
    THEN
        RAISE EXCEPTION 'A mapped pipeline job cannot change to a non-pipeline definition'
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
