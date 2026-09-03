-- V8: Study Anonymization Configuration & Pseudonym Tracking

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

-- Enable RLS
ALTER TABLE study_anonymization_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_anonymization_config FORCE ROW LEVEL SECURITY;
ALTER TABLE participant_pseudonyms ENABLE ROW LEVEL SECURITY;
ALTER TABLE participant_pseudonyms FORCE ROW LEVEL SECURITY;

CREATE POLICY study_anon_config_policy ON study_anonymization_config
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

CREATE POLICY participant_pseudonyms_policy ON participant_pseudonyms
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );
