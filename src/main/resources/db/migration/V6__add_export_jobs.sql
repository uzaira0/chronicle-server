-- V6: Async Data Export Jobs
-- Tracks async export jobs with status and format.

CREATE TABLE IF NOT EXISTS export_jobs (
    export_id       UUID NOT NULL DEFAULT gen_random_uuid(),
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    format          TEXT NOT NULL DEFAULT 'CSV',
    request         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    download_token  TEXT UNIQUE,
    row_count       BIGINT NOT NULL DEFAULT 0,
    error_message   TEXT,
    PRIMARY KEY (export_id)
);

CREATE INDEX IF NOT EXISTS idx_export_jobs_study_id ON export_jobs (study_id);
CREATE INDEX IF NOT EXISTS idx_export_jobs_status ON export_jobs (status);
CREATE INDEX IF NOT EXISTS idx_export_jobs_download_token ON export_jobs (download_token) WHERE download_token IS NOT NULL;

-- Enable RLS
ALTER TABLE export_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_jobs FORCE ROW LEVEL SECURITY;

CREATE POLICY export_jobs_select_policy ON export_jobs
    FOR SELECT
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

CREATE POLICY export_jobs_all_policy ON export_jobs
    FOR ALL
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );
