-- V69: export artifacts are derived participant/study data and must cross the
-- same quarantine and erasure boundary as their source rows.

CREATE UNIQUE INDEX IF NOT EXISTS export_jobs_export_study_unique
    ON export_jobs (export_id, study_id);

CREATE UNIQUE INDEX IF NOT EXISTS deletion_operations_operation_study_unique
    ON data_deletion_operations (operation_id, study_id);

CREATE TABLE IF NOT EXISTS export_job_revocations (
    export_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    study_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (export_id, operation_id),
    CONSTRAINT export_job_revocations_export_study_fk
        FOREIGN KEY (export_id, study_id)
        REFERENCES export_jobs(export_id, study_id)
        ON DELETE CASCADE,
    CONSTRAINT export_job_revocations_operation_study_fk
        FOREIGN KEY (operation_id, study_id)
        REFERENCES data_deletion_operations(operation_id, study_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS export_job_revocations_operation_idx
    ON export_job_revocations (operation_id);

ALTER TABLE export_job_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_job_revocations FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS study_isolation_export_job_revocations ON export_job_revocations;
CREATE POLICY study_isolation_export_job_revocations
ON export_job_revocations
FOR ALL
USING (chronicle_has_study_access(study_id))
WITH CHECK (chronicle_has_study_access(study_id));

-- Close the insert-after-scan race. Export inserts take the shared side of the
-- same per-study lock used by deletion queue/claim. Whichever transaction gets
-- the lock first forces the other to observe its committed row.
CREATE OR REPLACE FUNCTION chronicle_revoke_new_export_for_active_erasure()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(
        hashtextextended('chronicle-deletion:' || NEW.study_id::text, 0)
    );

    INSERT INTO export_job_revocations (export_id, operation_id, study_id)
    SELECT NEW.export_id, operation.operation_id, NEW.study_id
    FROM data_deletion_operations AS operation
    WHERE operation.study_id = NEW.study_id
      AND operation.status NOT IN ('CANCELLED', 'COMPLETED')
      AND (
          operation.mode = 'STUDY_ERASURE'
          OR CASE
              WHEN NOT jsonb_exists(NEW.request, 'participantIds') THEN TRUE
              WHEN jsonb_typeof(NEW.request -> 'participantIds') <> 'array' THEN TRUE
              ELSE jsonb_array_length(NEW.request -> 'participantIds') = 0
                   OR jsonb_exists(NEW.request -> 'participantIds', operation.participant_id)
          END
      )
    ON CONFLICT (export_id, operation_id) DO NOTHING;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS revoke_new_export_for_active_erasure ON export_jobs;
CREATE TRIGGER revoke_new_export_for_active_erasure
AFTER INSERT ON export_jobs
FOR EACH ROW
EXECUTE FUNCTION chronicle_revoke_new_export_for_active_erasure();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        -- V6 can run before the V60 fresh-install fallback creates this role.
        GRANT SELECT, INSERT, DELETE ON export_jobs TO chronicle_app;
        REVOKE UPDATE ON export_jobs FROM chronicle_app;
        GRANT UPDATE (
            status,
            completed_at,
            download_token,
            row_count,
            error_message,
            file_path
        ) ON export_jobs TO chronicle_app;
        GRANT SELECT, INSERT, DELETE ON export_job_revocations TO chronicle_app;
    END IF;
END $$;
