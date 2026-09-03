-- V70: PostgreSQL, not an in-memory executor, owns the export queue.
-- Leases fence stale workers and make interrupted RUNNING jobs reclaimable.

ALTER TABLE export_jobs
    ADD COLUMN IF NOT EXISTS available_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER,
    ADD COLUMN IF NOT EXISTS lease_token UUID,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE export_jobs
SET available_at = COALESCE(available_at, created_at, now()),
    attempt_count = COALESCE(attempt_count, 0),
    updated_at = COALESCE(updated_at, created_at, now()),
    completed_at = CASE
        WHEN status IN ('COMPLETED', 'FAILED') THEN
            CASE
                WHEN completed_at IS NOT NULL AND isfinite(completed_at) THEN completed_at
                ELSE COALESCE(updated_at, created_at, now())
            END
        ELSE 'infinity'::timestamptz
    END,
    lease_token = CASE
        WHEN status = 'RUNNING' THEN COALESCE(lease_token, gen_random_uuid())
        ELSE NULL
    END,
    lease_expires_at = CASE
        -- Existing in-memory work has no durable owner after this migration.
        WHEN status = 'RUNNING' THEN COALESCE(lease_expires_at, now())
        ELSE NULL
    END;

ALTER TABLE export_jobs
    ALTER COLUMN available_at SET DEFAULT now(),
    ALTER COLUMN available_at SET NOT NULL,
    ALTER COLUMN attempt_count SET DEFAULT 0,
    ALTER COLUMN attempt_count SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN completed_at SET DEFAULT 'infinity',
    ALTER COLUMN completed_at SET NOT NULL;

ALTER TABLE export_jobs
    DROP CONSTRAINT IF EXISTS export_jobs_status_check,
    DROP CONSTRAINT IF EXISTS export_jobs_attempt_count_check,
    DROP CONSTRAINT IF EXISTS export_jobs_lease_state_check,
    DROP CONSTRAINT IF EXISTS export_jobs_terminal_time_check;

ALTER TABLE export_jobs
    ADD CONSTRAINT export_jobs_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT export_jobs_attempt_count_check
        CHECK (attempt_count BETWEEN 0 AND 3),
    ADD CONSTRAINT export_jobs_lease_state_check
        CHECK (
            (status = 'RUNNING' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR
            (status <> 'RUNNING' AND lease_token IS NULL AND lease_expires_at IS NULL)
        ),
    ADD CONSTRAINT export_jobs_terminal_time_check
        CHECK (
            (status IN ('COMPLETED', 'FAILED') AND isfinite(completed_at))
            OR
            (status IN ('PENDING', 'RUNNING') AND NOT isfinite(completed_at))
        );

CREATE INDEX IF NOT EXISTS export_jobs_pending_dispatch_idx
    ON export_jobs (available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS export_jobs_expired_lease_idx
    ON export_jobs (lease_expires_at, created_at)
    WHERE status = 'RUNNING';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE UPDATE ON export_jobs FROM chronicle_app;
        GRANT UPDATE (
            status,
            completed_at,
            download_token,
            row_count,
            error_message,
            file_path,
            available_at,
            attempt_count,
            lease_token,
            lease_expires_at,
            updated_at
        ) ON export_jobs TO chronicle_app;
    END IF;
END $$;
