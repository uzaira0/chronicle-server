-- V5: API Key Management
-- Stores hashed API keys (SHA-256) for programmatic study access.

CREATE TABLE IF NOT EXISTS api_keys (
    key_id          UUID NOT NULL DEFAULT gen_random_uuid(),
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    key_hash        TEXT NOT NULL,
    key_prefix      TEXT NOT NULL,
    name            TEXT NOT NULL DEFAULT '',
    scope           TEXT NOT NULL DEFAULT 'READ_ONLY',
    created_by      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    last_used_at    TIMESTAMPTZ,
    usage_count     BIGINT NOT NULL DEFAULT 0,
    revoked         BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (key_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys (key_hash) WHERE NOT revoked;
CREATE INDEX IF NOT EXISTS idx_api_keys_study_id ON api_keys (study_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_prefix ON api_keys (key_prefix);

-- Enable RLS
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY api_keys_select_policy ON api_keys
    FOR SELECT
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

CREATE POLICY api_keys_all_policy ON api_keys
    FOR ALL
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );
