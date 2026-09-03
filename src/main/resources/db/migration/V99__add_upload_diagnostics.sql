-- Bounded, redacted Android upload diagnostics delivered after connectivity recovers.
CREATE TABLE IF NOT EXISTS upload_diagnostics (
    study_id UUID NOT NULL,
    participant_id TEXT NOT NULL,
    device_id UUID NOT NULL,
    event_id TEXT NOT NULL,
    diagnostic_day DATE NOT NULL,
    module_family TEXT NOT NULL CHECK (module_family IN ('USAGE_LIFECYCLE', 'BATTERY', 'DEVICE_TELEMETRY')),
    issue_code TEXT NOT NULL CHECK (issue_code IN (
        'DESTINATION_MISSING',
        'DESTINATION_IDENTITY_MISMATCH',
        'DESTINATION_SOURCE_DEVICE_MISSING',
        'DESTINATION_SETUP_INCOMPLETE',
        'DESTINATION_DISABLED',
        'DESTINATION_NONCANONICAL',
        'DESTINATION_CREDENTIAL_INCOMPLETE',
        'HTTP_SERVER_ERROR',
        'HTTP_CLIENT_ERROR',
        'TIMEOUT',
        'DNS_FAILURE',
        'TLS_FAILURE',
        'CONNECTION_FAILURE',
        'UPLOAD_FAILURE'
    )),
    occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),
    first_occurred_at TIMESTAMPTZ NOT NULL,
    last_occurred_at TIMESTAMPTZ NOT NULL,
    server_origin TEXT NOT NULL CHECK (server_origin LIKE 'https://%' AND length(server_origin) <= 512),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    error_type TEXT CHECK (length(error_type) <= 128),
    error_message TEXT CHECK (length(error_message) <= 512),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, device_id, event_id),
    CHECK (last_occurred_at >= first_occurred_at)
);

CREATE INDEX IF NOT EXISTS idx_upload_diagnostics_study_participant_last
    ON upload_diagnostics (study_id, participant_id, last_occurred_at DESC);

ALTER TABLE upload_diagnostics ENABLE ROW LEVEL SECURITY;
ALTER TABLE upload_diagnostics FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS study_isolation_upload_diagnostics ON upload_diagnostics;
CREATE POLICY study_isolation_upload_diagnostics ON upload_diagnostics
    FOR ALL
    USING (chronicle_has_study_access(study_id))
    WITH CHECK (chronicle_has_study_access(study_id));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid
        WHERE n.nspname = 'public' AND p.proname = 'chronicle_participant_data_visible'
    ) THEN
        DROP POLICY IF EXISTS deletion_quarantine_upload_diagnostics ON upload_diagnostics;
        CREATE POLICY deletion_quarantine_upload_diagnostics ON upload_diagnostics
            AS RESTRICTIVE FOR SELECT
            USING (chronicle_participant_data_visible(study_id, participant_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON upload_diagnostics TO chronicle;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON upload_diagnostics TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        GRANT SELECT ON upload_diagnostics TO chronicle_admin;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V99__add_upload_diagnostics', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE
            SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
