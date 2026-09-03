-- Move the last service-created permanent tables into Chronicle's Flyway chain.
--
-- Older releases created these tables lazily from NotificationService and
-- LocalBlobDataService constructors. CREATE TABLE IF NOT EXISTS adopts those exact
-- legacy tables without rewriting researcher settings, phone metadata, or blob data.

CREATE TABLE IF NOT EXISTS researcher_phone_numbers (
    principal_id TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (principal_id)
);

CREATE TABLE IF NOT EXISTS researcher_notification_settings (
    study_id UUID NOT NULL,
    principal_id TEXT NOT NULL,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (study_id, principal_id)
);

-- The legacy PostgresTableDefinition allowed nullable objects; preserve that contract
-- while moving table ownership out of LocalBlobDataService startup.
CREATE TABLE IF NOT EXISTS local_blob_store (
    key TEXT NOT NULL,
    object BYTEA,
    PRIMARY KEY (key)
);

COMMENT ON TABLE researcher_phone_numbers IS
    'Researcher-owned phone metadata; provisioned by Flyway rather than application startup';
COMMENT ON TABLE researcher_notification_settings IS
    'Per-study researcher notification preferences; provisioned by Flyway';
COMMENT ON TABLE local_blob_store IS
    'Local binary-object storage used by the media-local profile; provisioned by Flyway';

-- The controller enforces the same study + self-or-admin checks. Repeat those
-- boundaries in PostgreSQL so a bypassed application check cannot expose another
-- researcher's contact details or preferences.
ALTER TABLE researcher_phone_numbers ENABLE ROW LEVEL SECURITY;
ALTER TABLE researcher_phone_numbers FORCE ROW LEVEL SECURITY;
ALTER TABLE researcher_notification_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE researcher_notification_settings FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS researcher_phone_numbers_self_or_admin ON researcher_phone_numbers;
CREATE POLICY researcher_phone_numbers_self_or_admin ON researcher_phone_numbers
    FOR ALL
    USING (
        COALESCE(NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN, FALSE)
        OR principal_id = current_setting('app.current_user_id', true)
    )
    WITH CHECK (
        COALESCE(NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN, FALSE)
        OR principal_id = current_setting('app.current_user_id', true)
    );

DROP POLICY IF EXISTS researcher_notification_settings_study_self_or_admin
    ON researcher_notification_settings;
CREATE POLICY researcher_notification_settings_study_self_or_admin
    ON researcher_notification_settings
    FOR ALL
    USING (
        chronicle_has_study_access(study_id)
        AND (
            COALESCE(NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN, FALSE)
            OR principal_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        chronicle_has_study_access(study_id)
        AND (
            COALESCE(NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN, FALSE)
            OR principal_id = current_setting('app.current_user_id', true)
        )
    );

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            researcher_phone_numbers,
            researcher_notification_settings,
            local_blob_store
        TO chronicle_app;
    END IF;
END $$;
