-- V56: Re-issue of V8 (study anonymization config & pseudonym tracking).
--
-- V8 was never registered with the upgrade framework, so production databases
-- baselined at V54 never received these objects even though AnonymizationService /
-- AnonymizationController are live (see docs/db/MIGRATION-LEDGER-AUDIT.md).
-- Fully idempotent; policy semantics match the original V8 convention.

CREATE TABLE IF NOT EXISTS study_anonymization_config (
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id)
);

CREATE TABLE IF NOT EXISTS participant_pseudonyms (
    study_id        UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    participant_id  TEXT NOT NULL,
    pseudonym       TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_pseudonyms_unique ON participant_pseudonyms (study_id, pseudonym);

ALTER TABLE study_anonymization_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_anonymization_config FORCE ROW LEVEL SECURITY;
ALTER TABLE participant_pseudonyms ENABLE ROW LEVEL SECURITY;
ALTER TABLE participant_pseudonyms FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS study_anon_config_policy ON study_anonymization_config;
CREATE POLICY study_anon_config_policy ON study_anonymization_config
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

DROP POLICY IF EXISTS participant_pseudonyms_policy ON participant_pseudonyms;
CREATE POLICY participant_pseudonyms_policy ON participant_pseudonyms
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );
