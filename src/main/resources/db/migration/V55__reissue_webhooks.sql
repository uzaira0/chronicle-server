-- V55: Re-issue of V7 (webhook registrations & delivery tracking).
--
-- V7 was never registered with the upgrade framework, so production databases
-- baselined at V54 never received these objects even though WebhookService /
-- WebhookController are live (see docs/db/MIGRATION-LEDGER-AUDIT.md).
-- This re-issue is fully idempotent: it converges fresh installs (where V7
-- already ran) and baselined production (where it did not) to the same state.
-- Policy semantics intentionally match the original V7 convention.

CREATE TABLE IF NOT EXISTS webhook_registrations (
    webhook_id      UUID NOT NULL DEFAULT gen_random_uuid(),
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    secret_hash     TEXT NOT NULL DEFAULT '',
    event_types     TEXT[] NOT NULL DEFAULT '{}',
    enabled         BOOLEAN NOT NULL DEFAULT true,
    description     TEXT NOT NULL DEFAULT '',
    created_by      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (webhook_id)
);

CREATE INDEX IF NOT EXISTS idx_webhook_registrations_study_id ON webhook_registrations (study_id);

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    delivery_id     UUID NOT NULL DEFAULT gen_random_uuid(),
    webhook_id      UUID NOT NULL REFERENCES webhook_registrations(webhook_id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          INTEGER NOT NULL DEFAULT 0,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    response_body   TEXT,
    PRIMARY KEY (delivery_id)
);

CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_webhook_id ON webhook_deliveries (webhook_id);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_created_at ON webhook_deliveries (created_at);

ALTER TABLE webhook_registrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_registrations FORCE ROW LEVEL SECURITY;
ALTER TABLE webhook_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_deliveries FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS webhook_registrations_policy ON webhook_registrations;
CREATE POLICY webhook_registrations_policy ON webhook_registrations
    FOR ALL
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

DROP POLICY IF EXISTS webhook_deliveries_policy ON webhook_deliveries;
CREATE POLICY webhook_deliveries_policy ON webhook_deliveries
    FOR ALL
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR webhook_id IN (
            SELECT webhook_id FROM webhook_registrations
            WHERE study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
        )
    );
