-- =============================================================================
-- app_network_usage table — Android `app_network_usage` collection module
-- =============================================================================
-- Stores one row per AndroidAppNetworkUsageEvent
-- (com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent) uploaded by the Android
-- `app_network_usage` collection module, scoped to a study + participant.
--
-- BEHAVIORAL_METADATA-class data (default OFF) — volume counts only, never content: each row
-- carries the transmitted/received byte counts for one app over one time bucket
-- (NetworkStatsManager); it has zero visibility into payloads, destinations, domains, or URLs.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- APP_NETWORK_USAGE PostgresTableDefinition in ChroniclePostgresTables.kt. This migration
-- CREATEs it defensively (IF NOT EXISTS) and — like V31 — retrofits Row-Level Security so the
-- table is study-isolated like the other Android collection tables.
--
-- event_id is the per-event de-duplication key (AndroidAppNetworkUsageEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) PK makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS app_network_usage (
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    event_id            TEXT        NOT NULL,
    sample_timestamp    TIMESTAMPTZ NOT NULL,
    timezone            TEXT        NOT NULL,
    package_name        TEXT        NOT NULL,
    network_type        TEXT        NOT NULL,
    rx_bytes            BIGINT      NOT NULL,
    tx_bytes            BIGINT      NOT NULL,
    bucket_start_millis BIGINT      NOT NULL,
    bucket_end_millis   BIGINT      NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_app_network_usage_study_participant_ts
    ON app_network_usage (study_id, participant_id, sample_timestamp);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'app_network_usage') THEN
        ALTER TABLE app_network_usage ENABLE ROW LEVEL SECURITY;
        ALTER TABLE app_network_usage FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_app_network_usage ON app_network_usage;
        CREATE POLICY study_isolation_app_network_usage ON app_network_usage
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V42__add_app_network_usage', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
