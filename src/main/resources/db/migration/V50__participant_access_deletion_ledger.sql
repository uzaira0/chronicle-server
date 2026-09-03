-- Secure participant capabilities, verified deletion ledger, legal holds, and
-- data-quality RLS. Wire/state values are strings so Kotlin and future Serde
-- implementations share the same durable contract.

CREATE TABLE IF NOT EXISTS participant_form_access_codes (
    access_code_id UUID PRIMARY KEY,
    token_hash BYTEA NOT NULL UNIQUE,
    study_id UUID NOT NULL,
    participant_id TEXT NOT NULL,
    form_kind TEXT NOT NULL CHECK (form_kind IN ('APP_USAGE', 'QUESTIONNAIRE', 'TIME_USE_DIARY', 'PORTAL')),
    resource_id UUID,
    logical_date DATE,
    issuer_type TEXT NOT NULL CHECK (issuer_type IN ('DEVICE', 'RESEARCHER')),
    issued_by TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    exchanged_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (study_id, participant_id)
        REFERENCES study_participants(study_id, participant_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS participant_form_access_codes_subject_idx
    ON participant_form_access_codes (study_id, participant_id, expires_at);

CREATE TABLE IF NOT EXISTS participant_form_sessions (
    session_id UUID PRIMARY KEY,
    session_hash BYTEA NOT NULL UNIQUE,
    access_code_id UUID NOT NULL REFERENCES participant_form_access_codes(access_code_id) ON DELETE CASCADE,
    study_id UUID NOT NULL,
    participant_id TEXT NOT NULL,
    form_kind TEXT NOT NULL,
    resource_id UUID,
    logical_date DATE,
    csrf_hash BYTEA NOT NULL,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS participant_form_sessions_subject_idx
    ON participant_form_sessions (study_id, participant_id, absolute_expires_at);

CREATE TABLE IF NOT EXISTS participant_form_submission_receipts (
    receipt_id UUID PRIMARY KEY,
    access_code_id UUID NOT NULL REFERENCES participant_form_access_codes(access_code_id) ON DELETE CASCADE,
    study_id UUID NOT NULL,
    participant_id TEXT NOT NULL,
    form_kind TEXT NOT NULL,
    resource_key TEXT NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash BYTEA NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED')),
    submission_id UUID,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (access_code_id, form_kind, resource_key, idempotency_key)
);

CREATE TABLE IF NOT EXISTS data_deletion_operations (
    operation_id UUID PRIMARY KEY,
    study_id UUID NOT NULL,
    participant_ref TEXT,
    participant_id TEXT,
    mode TEXT NOT NULL CHECK (mode IN ('COLLECTED_DATA_PURGE', 'WITHDRAW_AND_ERASE', 'STUDY_ERASURE')),
    status TEXT NOT NULL CHECK (status IN ('PREVIEW', 'QUARANTINED', 'HELD', 'READY', 'ERASING', 'VERIFYING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    requested_by TEXT NOT NULL,
    idempotency_key UUID NOT NULL UNIQUE,
    registry_version INTEGER NOT NULL,
    quarantine_until TIMESTAMPTZ,
    preview_expires_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    proof_hash TEXT,
    failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS data_deletion_operations_due_idx
    ON data_deletion_operations (status, quarantine_until);
CREATE INDEX IF NOT EXISTS data_deletion_operations_subject_idx
    ON data_deletion_operations (study_id, participant_ref);

CREATE TABLE IF NOT EXISTS data_deletion_steps (
    operation_id UUID NOT NULL REFERENCES data_deletion_operations(operation_id) ON DELETE CASCADE,
    study_id UUID NOT NULL,
    asset_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'VERIFIED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    expected_rows BIGINT,
    deleted_rows BIGINT,
    residual_rows BIGINT,
    error_code TEXT,
    last_attempt_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    PRIMARY KEY (operation_id, asset_id)
);

CREATE TABLE IF NOT EXISTS retention_holds (
    hold_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES data_deletion_operations(operation_id) ON DELETE CASCADE,
    study_id UUID NOT NULL,
    reason TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    review_at TIMESTAMPTZ NOT NULL,
    released_by TEXT,
    released_at TIMESTAMPTZ,
    release_reason TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS retention_holds_one_active_idx
    ON retention_holds (operation_id) WHERE released_at IS NULL;

CREATE TABLE IF NOT EXISTS data_deletion_tombstones (
    operation_id UUID PRIMARY KEY,
    study_ref TEXT NOT NULL,
    participant_ref TEXT,
    mode TEXT NOT NULL,
    registry_version INTEGER NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    proof_hash TEXT NOT NULL
);

-- Deletion quarantine is deliberately inaccessible through the application role.  A
-- restrictive policy is combined with each table's existing study-isolation policy,
-- so queued participant/study data disappears from normal reads immediately while the
-- seven-day recovery window and any explicit hold remain in force.
CREATE OR REPLACE FUNCTION chronicle_participant_data_visible(
    check_study_id UUID,
    check_participant_id TEXT
) RETURNS BOOLEAN AS $$
BEGIN
    RETURN NOT EXISTS (
        SELECT 1
        FROM public.data_deletion_operations operation
        WHERE operation.study_id = check_study_id
          AND operation.status IN ('QUARANTINED', 'HELD', 'READY', 'ERASING', 'VERIFYING', 'FAILED')
          AND (
              operation.mode = 'STUDY_ERASURE'
              OR operation.participant_id = check_participant_id
          )
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public;

REVOKE ALL ON FUNCTION chronicle_participant_data_visible(UUID, TEXT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION chronicle_participant_data_visible(
    check_study_id TEXT,
    check_participant_id TEXT
) RETURNS BOOLEAN AS $$
BEGIN
    IF check_study_id IS NULL OR check_study_id = '' THEN
        RETURN false;
    END IF;
    RETURN chronicle_participant_data_visible(check_study_id::UUID, check_participant_id);
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public;

REVOKE ALL ON FUNCTION chronicle_participant_data_visible(TEXT, TEXT) FROM PUBLIC;

CREATE TABLE IF NOT EXISTS study_encryption_keys (
    study_id UUID NOT NULL,
    key_ref TEXT NOT NULL,
    backend TEXT NOT NULL CHECK (backend IN ('VAULT', 'FILE')),
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    destroyed_at TIMESTAMPTZ,
    destruction_proof TEXT,
    PRIMARY KEY (study_id, key_ref, version)
);

ALTER TABLE data_quality_alerts
    ADD COLUMN IF NOT EXISTS evaluation_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS evaluation_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS threshold DOUBLE PRECISION;

CREATE UNIQUE INDEX IF NOT EXISTS data_quality_alerts_window_unique
    ON data_quality_alerts (study_id, participant_id, alert_type, evaluation_start, evaluation_end);

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'participant_form_access_codes',
        'participant_form_sessions',
        'participant_form_submission_receipts',
        'data_deletion_operations',
        'data_deletion_steps',
        'retention_holds',
        'study_encryption_keys',
        'data_quality_alerts'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'DROP POLICY IF EXISTS %I ON %I',
            'study_isolation_' || table_name,
            table_name
        );
        EXECUTE format(
            'CREATE POLICY %I ON %I FOR ALL USING (chronicle_has_study_access(study_id)) WITH CHECK (chronicle_has_study_access(study_id))',
            'study_isolation_' || table_name,
            table_name
        );
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            participant_form_access_codes,
            participant_form_sessions,
            participant_form_submission_receipts,
            data_deletion_operations,
            data_deletion_steps,
            retention_holds,
            study_encryption_keys,
            data_quality_alerts
        TO chronicle_app;
    END IF;
END $$;

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'chronicle_usage_events',
        'chronicle_usage_stats',
        'preprocessed_usage_events',
        'sensor_data',
        'android_sensor_data',
        'app_usage_survey',
        'questionnaire_submissions',
        'time_use_diary_submissions',
        'participant_stats',
        'upload_buffer',
        'battery_telemetry',
        'interaction_events',
        'app_audio_activity',
        'app_audio_content',
        'notification_activity',
        'sleep_events',
        'activity_recognition_events',
        'health_metrics',
        'connectivity_state_events',
        'app_network_usage',
        'device_settings',
        'encrypted_payloads',
        'data_quality_alerts',
        'time_use_diary_summarized',
        'study_event_stream',
        'android_device_sensor_availability',
        'participant_form_submission_receipts',
        'participant_form_sessions',
        'participant_form_access_codes'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'DROP POLICY IF EXISTS %I ON %I',
            'deletion_quarantine_' || table_name,
            table_name
        );
        EXECUTE format(
            'CREATE POLICY %I ON %I AS RESTRICTIVE FOR SELECT USING (chronicle_participant_data_visible(study_id, participant_id))',
            'deletion_quarantine_' || table_name,
            table_name
        );
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT EXECUTE ON FUNCTION chronicle_participant_data_visible(UUID, TEXT) TO chronicle_app;
        GRANT EXECUTE ON FUNCTION chronicle_participant_data_visible(TEXT, TEXT) TO chronicle_app;
    END IF;
END $$;

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V50__participant_access_deletion_ledger', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
