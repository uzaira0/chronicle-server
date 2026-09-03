-- Bound lease-crash recovery independently from ordinary generation retries,
-- reject malformed durable formats, and coordinate storage admission across
-- every server replica sharing the managed export volume.

ALTER TABLE export_jobs
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN recovery_cleanup_available_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity';

UPDATE export_jobs
SET status = 'FAILED',
    format = 'CSV',
    completed_at = CASE
        WHEN completed_at IS NOT NULL AND isfinite(completed_at) THEN completed_at
        ELSE now()
    END,
    download_token = NULL,
    error_message = format(
        'Stored export format %L is invalid; normalized to CSV and artifact cleanup required',
        export_jobs.format
    ),
    lease_token = NULL,
    lease_expires_at = NULL,
    updated_at = now()
WHERE format NOT IN ('CSV', 'JSON', 'EXCEL');

ALTER TABLE export_jobs
    ADD CONSTRAINT export_jobs_format_check
        CHECK (format IN ('CSV', 'JSON', 'EXCEL')),
    ADD CONSTRAINT export_jobs_recovery_count_check
        CHECK (recovery_count BETWEEN 0 AND 3);

CREATE TABLE export_capacity_reservations (
    export_id UUID PRIMARY KEY,
    study_id UUID NOT NULL,
    lease_token UUID NOT NULL,
    reserved_bytes BIGINT NOT NULL CHECK (reserved_bytes > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT export_capacity_reservations_job_fk
        FOREIGN KEY (export_id, study_id)
        REFERENCES export_jobs(export_id, study_id)
        ON DELETE CASCADE
);

CREATE INDEX export_capacity_reservations_study_idx
    ON export_capacity_reservations (study_id);

ALTER TABLE export_capacity_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_capacity_reservations FORCE ROW LEVEL SECURITY;

CREATE POLICY study_isolation_export_capacity_reservations
ON export_capacity_reservations
FOR ALL
USING (chronicle_has_study_access(study_id))
WITH CHECK (chronicle_has_study_access(study_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON export_capacity_reservations TO chronicle_app;
        GRANT UPDATE (recovery_count, recovery_cleanup_available_at) ON export_jobs TO chronicle_app;
    END IF;
END
$$;
