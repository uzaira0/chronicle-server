-- =============================================================================
-- encrypted_payloads table — envelope-encrypted Android upload batches (HIPAA-2028 W2)
-- =============================================================================
-- Blind storage for com.openlattice.chronicle.crypto.EncryptedEnvelope batches posted
-- by an Android device to POST /chronicle/v4/study/{studyId}/participant/{participantId}/
-- android/encrypted, scoped to a study + participant + device.
--
-- The backend NEVER decrypts on ingest. encrypted_key / iv / ciphertext are opaque BYTEA;
-- the study private key lives only in Vault and is fetched solely at authorized
-- export/decrypt time. A database compromise therefore yields only ciphertext plus a
-- wrapped content key it cannot open.
--
-- content_hash = SHA-256(encrypted_key || iv || ciphertext). The
-- (study_id, participant_id, content_hash) UNIQUE constraint makes re-sends of the same
-- sealed batch idempotent (the upload service uses ON CONFLICT DO NOTHING), exactly like
-- battery_telemetry's (study_id, participant_id, sample_id) dedup key.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- ENCRYPTED_PAYLOADS PostgresTableDefinition in ChroniclePostgresTables.kt. This migration
-- CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent regardless of
-- migration/framework ordering, and — like V1/V24 — retrofits Row-Level Security so it is
-- study-isolated exactly like the other Android collection tables (android_sensor_data,
-- battery_telemetry, chronicle_usage_events, upload_buffer). This is a PHI table.
-- =============================================================================

CREATE TABLE IF NOT EXISTS encrypted_payloads (
    payload_id          UUID        NOT NULL,
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    device_id           UUID        NOT NULL,
    payload_type        TEXT        NOT NULL,
    envelope_version    INTEGER     NOT NULL,
    alg                 TEXT        NOT NULL,
    key_id              TEXT        NOT NULL,
    encrypted_key       BYTEA       NOT NULL,
    iv                  BYTEA       NOT NULL,
    ciphertext          BYTEA       NOT NULL,
    sample_count        INTEGER     NOT NULL DEFAULT 0,
    content_hash        BYTEA       NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (payload_id),
    CONSTRAINT encrypted_payloads_dedup UNIQUE (study_id, participant_id, content_hash)
);

-- Index supporting RLS-filtered, study/participant-scoped chronological export reads.
CREATE INDEX IF NOT EXISTS idx_encrypted_payloads_study_participant_uploaded
    ON encrypted_payloads (study_id, participant_id, uploaded_at);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V1 / V24
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'encrypted_payloads') THEN
        ALTER TABLE encrypted_payloads ENABLE ROW LEVEL SECURITY;
        ALTER TABLE encrypted_payloads FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_encrypted_payloads ON encrypted_payloads;
        CREATE POLICY study_isolation_encrypted_payloads ON encrypted_payloads
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Grant the application role the same DML other PHI tables get. Guarded so it is a
-- no-op where the role does not yet exist (the role is provisioned by
-- docker/init-db-roles.sql in prod and the test harness's ensureRlsAppRole()).
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON encrypted_payloads TO chronicle_app;
    END IF;
END $$;

-- Completion is recorded by EncryptedPayloadsUpgrade.completeUpgrade() keyed on the upgrade
-- CLASS name (the same mechanism V28 uses); this migration does NOT self-write an `upgrades`
-- row — a second row keyed on the filename would be an orphan the runner never reads and would
-- mislead an operator auditing the upgrades table.
