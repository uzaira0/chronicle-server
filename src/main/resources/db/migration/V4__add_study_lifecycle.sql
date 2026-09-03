-- V4: Study Archival & Lifecycle Management
-- Adds lifecycle_status to studies table and creates lifecycle event tracking tables.

-- Add lifecycle_status column to studies table
ALTER TABLE studies
    ADD COLUMN IF NOT EXISTS lifecycle_status TEXT NOT NULL DEFAULT 'ACTIVE';

-- Create index for querying by lifecycle status
CREATE INDEX IF NOT EXISTS idx_studies_lifecycle_status ON studies (lifecycle_status);

-- Study lifecycle events: audit trail for status transitions
CREATE TABLE IF NOT EXISTS study_lifecycle_events (
    event_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    previous_status TEXT NOT NULL,
    new_status      TEXT NOT NULL,
    changed_by      TEXT NOT NULL,
    reason          TEXT DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_lifecycle_events_study_id ON study_lifecycle_events (study_id);
CREATE INDEX IF NOT EXISTS idx_lifecycle_events_created_at ON study_lifecycle_events (created_at);

-- Study deletion schedule: tracks studies scheduled for future deletion
CREATE TABLE IF NOT EXISTS study_deletion_schedule (
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    scheduled_by    TEXT NOT NULL,
    delete_after    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id)
);

CREATE INDEX IF NOT EXISTS idx_deletion_schedule_delete_after ON study_deletion_schedule (delete_after);

-- Enable RLS on new tables
ALTER TABLE study_lifecycle_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_lifecycle_events FORCE ROW LEVEL SECURITY;
ALTER TABLE study_deletion_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_deletion_schedule FORCE ROW LEVEL SECURITY;

-- RLS policies for study_lifecycle_events
CREATE POLICY study_lifecycle_events_select_policy ON study_lifecycle_events
    FOR SELECT
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

CREATE POLICY study_lifecycle_events_insert_policy ON study_lifecycle_events
    FOR INSERT
    WITH CHECK (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

-- RLS policies for study_deletion_schedule
CREATE POLICY study_deletion_schedule_select_policy ON study_deletion_schedule
    FOR SELECT
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

CREATE POLICY study_deletion_schedule_all_policy ON study_deletion_schedule
    FOR ALL
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );
