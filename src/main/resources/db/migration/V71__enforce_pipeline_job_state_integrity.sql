-- Make Chronicle jobs and pipeline runs honest, recoverable state machines.
--
-- Older workers marked a job FINISHED while merely claiming it, never persisted
-- terminal timestamps, and could leave pipeline runs PENDING after the backing
-- job had failed. Repair those rows before installing the invariants.

ALTER TABLE jobs
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

UPDATE jobs
SET status = 'CANCELED',
    updated_at = now(),
    completed_at = now(),
    message = left(
        coalesce(nullif(message, ''), 'Invalid legacy job status'),
        4096
    ),
    lease_token = NULL,
    lease_expires_at = NULL
WHERE status IS NULL
   OR status NOT IN ('PAUSED', 'PENDING', 'FINISHED', 'RUNNING', 'STOPPING', 'CANCELED');

-- A RUNNING row from an older process has no ownership token and cannot safely
-- be resumed. Record interruption instead of leaving it active forever.
UPDATE jobs
SET status = 'CANCELED',
    updated_at = now(),
    completed_at = now(),
    message = left(
        coalesce(nullif(message, ''), 'Worker restarted before completion'),
        4096
    ),
    lease_token = NULL,
    lease_expires_at = NULL
WHERE status = 'RUNNING';

UPDATE jobs
SET completed_at = CASE
        WHEN isfinite(updated_at) THEN greatest(updated_at, created_at)
        ELSE now()
    END,
    lease_token = NULL,
    lease_expires_at = NULL
WHERE status IN ('FINISHED', 'CANCELED')
  AND NOT isfinite(completed_at);

UPDATE jobs
SET completed_at = 'infinity',
    lease_token = NULL,
    lease_expires_at = NULL
WHERE status NOT IN ('FINISHED', 'CANCELED')
  AND isfinite(completed_at);

UPDATE pipeline_runs
SET status = 'FAILED',
    total_steps = greatest(total_steps, 1),
    steps_completed = greatest(0, least(steps_completed, greatest(total_steps, 1))),
    input_rows = greatest(input_rows, 0),
    output_rows = greatest(output_rows, 0),
    completed_at = CASE
        WHEN isfinite(started_at) THEN greatest(started_at, now())
        ELSE now()
    END,
    error_message = left(
        coalesce(nullif(error_message, ''), 'Invalid legacy pipeline state'),
        4096
    )
WHERE status IS NULL
   OR status NOT IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')
   OR total_steps <= 0
   OR steps_completed < 0
   OR steps_completed > total_steps
   OR input_rows < 0
   OR output_rows < 0
   OR (status = 'COMPLETED' AND steps_completed <> total_steps);

UPDATE pipeline_runs run
SET status = 'FAILED',
    completed_at = CASE
        WHEN isfinite(run.started_at) THEN greatest(run.started_at, now())
        ELSE now()
    END,
    error_message = 'Pipeline backing job is missing or terminal'
WHERE run.status IN ('PENDING', 'RUNNING')
  AND (
      NOT EXISTS (
          SELECT 1
          FROM jobs job
          WHERE job.job_id = run.job_id
      )
      OR EXISTS (
          SELECT 1
          FROM jobs job
          WHERE job.job_id = run.job_id
            AND job.status IN ('FINISHED', 'CANCELED')
      )
  );

UPDATE jobs job
SET status = 'CANCELED',
    updated_at = now(),
    completed_at = now(),
    message = 'Pipeline run mapping is missing',
    lease_token = NULL,
    lease_expires_at = NULL
WHERE job.status NOT IN ('FINISHED', 'CANCELED')
  AND coalesce(job.definition ->> '@type', job.definition ->> '@class') IN (
      'PipelineJobDefinition',
      'com.openlattice.chronicle.pipeline.PipelineJobDefinition'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM pipeline_runs run
      WHERE run.job_id = job.job_id
  );

UPDATE pipeline_runs
SET completed_at = 'infinity',
    error_message = NULL
WHERE status IN ('PENDING', 'RUNNING');

UPDATE pipeline_runs
SET completed_at = CASE
        WHEN isfinite(started_at) THEN greatest(started_at, now())
        ELSE now()
    END
WHERE status IN ('COMPLETED', 'FAILED')
  AND NOT isfinite(completed_at);

UPDATE pipeline_runs
SET error_message = coalesce(
        nullif(left(error_message, 4096), ''),
        'Pipeline failed without error detail'
    )
WHERE status = 'FAILED';

UPDATE pipeline_runs
SET error_message = NULL
WHERE status <> 'FAILED';

DO $$
BEGIN
    IF EXISTS (
        SELECT job_id
        FROM pipeline_runs
        GROUP BY job_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce pipeline integrity: duplicate pipeline_runs.job_id mappings exist';
    END IF;
END
$$;

ALTER TABLE jobs
    ALTER COLUMN status SET NOT NULL,
    ADD CONSTRAINT jobs_status_valid_chk
        CHECK (status IN ('PAUSED', 'PENDING', 'FINISHED', 'RUNNING', 'STOPPING', 'CANCELED')),
    ADD CONSTRAINT jobs_terminal_time_chk
        CHECK (
            (status IN ('FINISHED', 'CANCELED') AND isfinite(completed_at))
            OR
            (status NOT IN ('FINISHED', 'CANCELED') AND NOT isfinite(completed_at))
        ),
    ADD CONSTRAINT jobs_lease_state_chk
        CHECK (
            (
                status = 'RUNNING'
                AND lease_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND isfinite(lease_expires_at)
                AND lease_expires_at > updated_at
            )
            OR
            (
                status <> 'RUNNING'
                AND lease_token IS NULL
                AND lease_expires_at IS NULL
            )
        );

ALTER TABLE pipeline_runs
    ALTER COLUMN status SET NOT NULL,
    ADD CONSTRAINT pipeline_runs_status_valid_chk
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT pipeline_runs_counts_chk
        CHECK (
            total_steps > 0
            AND steps_completed BETWEEN 0 AND total_steps
            AND input_rows >= 0
            AND output_rows >= 0
        ),
    ADD CONSTRAINT pipeline_runs_terminal_time_chk
        CHECK (
            (status IN ('COMPLETED', 'FAILED') AND isfinite(completed_at))
            OR
            (status IN ('PENDING', 'RUNNING') AND NOT isfinite(completed_at))
        ),
    ADD CONSTRAINT pipeline_runs_completion_chk
        CHECK (status <> 'COMPLETED' OR steps_completed = total_steps),
    ADD CONSTRAINT pipeline_runs_error_state_chk
        CHECK (
            (
                status = 'FAILED'
                AND error_message IS NOT NULL
                AND btrim(error_message) <> ''
                AND char_length(error_message) <= 4096
            )
            OR
            (status <> 'FAILED' AND error_message IS NULL)
        );

CREATE UNIQUE INDEX pipeline_runs_job_id_unique
    ON pipeline_runs (job_id);

CREATE INDEX jobs_pending_dispatch_idx
    ON jobs (created_at)
    WHERE status = 'PENDING';

CREATE INDEX jobs_expired_lease_idx
    ON jobs (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE OR REPLACE FUNCTION chronicle_validate_job_status_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF (OLD.status = 'PENDING' AND NEW.status IN ('RUNNING', 'PAUSED', 'CANCELED'))
        OR (OLD.status = 'PAUSED' AND NEW.status IN ('PENDING', 'RUNNING', 'CANCELED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('PAUSED', 'STOPPING', 'FINISHED', 'CANCELED'))
        OR (OLD.status = 'STOPPING' AND NEW.status IN ('FINISHED', 'CANCELED')) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Invalid Chronicle job status transition: % -> %', OLD.status, NEW.status
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER chronicle_jobs_status_transition
    BEFORE UPDATE OF status ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_job_status_transition();

CREATE OR REPLACE FUNCTION chronicle_validate_pipeline_status_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF (OLD.status = 'PENDING' AND NEW.status IN ('RUNNING', 'FAILED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED')) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Invalid pipeline status transition: % -> %', OLD.status, NEW.status
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER chronicle_pipeline_runs_status_transition
    BEFORE UPDATE OF status ON pipeline_runs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_validate_pipeline_status_transition();

CREATE OR REPLACE FUNCTION chronicle_fail_pipeline_for_canceled_job()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CANCELED' AND OLD.status <> 'CANCELED' THEN
        UPDATE pipeline_runs
        SET status = 'FAILED',
            completed_at = now(),
            error_message = left(
                coalesce(nullif(NEW.message, ''), 'Pipeline backing job was canceled'),
                4096
            )
        WHERE job_id = NEW.job_id
          AND status IN ('PENDING', 'RUNNING');
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER chronicle_fail_pipeline_on_job_cancel
    AFTER UPDATE OF status ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_fail_pipeline_for_canceled_job();

CREATE OR REPLACE FUNCTION chronicle_guard_active_pipeline_job_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pipeline_runs
        WHERE job_id = OLD.job_id
          AND status IN ('PENDING', 'RUNNING')
    ) THEN
        RAISE EXCEPTION 'Cannot delete job % while its pipeline run is active', OLD.job_id
            USING ERRCODE = '23503';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER chronicle_guard_active_pipeline_job_delete
    BEFORE DELETE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION chronicle_guard_active_pipeline_job_delete();
