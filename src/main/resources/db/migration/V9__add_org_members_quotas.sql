-- V9: Organization Members, Roles & Quotas

CREATE TABLE IF NOT EXISTS organization_members (
    organization_id UUID NOT NULL,
    user_id         TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'VIEWER',
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_org_members_user_id ON organization_members (user_id);

CREATE TABLE IF NOT EXISTS organization_quotas (
    organization_id          UUID NOT NULL PRIMARY KEY,
    max_studies              INTEGER NOT NULL DEFAULT 100,
    max_participants_per_study INTEGER NOT NULL DEFAULT 10000,
    max_api_keys_per_study   INTEGER NOT NULL DEFAULT 20,
    max_webhooks_per_study   INTEGER NOT NULL DEFAULT 10,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable RLS
ALTER TABLE organization_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_members FORCE ROW LEVEL SECURITY;
ALTER TABLE organization_quotas ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_quotas FORCE ROW LEVEL SECURITY;

CREATE POLICY org_members_policy ON organization_members
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR user_id = current_setting('app.current_user_id', true)
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    );

CREATE POLICY org_quotas_policy ON organization_quotas
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    );
