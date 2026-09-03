-- =============================================================================
-- health_metrics table — Android `health_connect` collection module
-- =============================================================================
-- Stores one row per AndroidHealthMetricEvent
-- (com.openlattice.chronicle.collection.AndroidHealthMetricEvent) uploaded by the Android
-- `health_connect` collection module, scoped to a study + participant.
--
-- HEALTH_METRICS-class data, opt-in. Each row is a single aggregated/instantaneous record
-- read from the system Health Connect store (value + unit interpreted per metric_type).
-- Chronicle only reads records the participant's own apps/wearables wrote.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- HEALTH_METRICS PostgresTableDefinition in ChroniclePostgresTables.kt. This migration
-- CREATEs it defensively (IF NOT EXISTS) and — like V31 — retrofits Row-Level Security so
-- the table is study-isolated like the other Android collection tables.
--
-- event_id is the per-event de-duplication key (AndroidHealthMetricEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) PK makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS health_metrics (
    study_id          UUID             NOT NULL,
    participant_id    TEXT             NOT NULL,
    event_id          TEXT             NOT NULL,
    sample_timestamp  TIMESTAMPTZ      NOT NULL,
    timezone          TEXT             NOT NULL,
    metric_type       TEXT             NOT NULL,
    metric_value      DOUBLE PRECISION NOT NULL,
    unit              TEXT             NOT NULL,
    start_millis      BIGINT           NOT NULL,
    end_millis        BIGINT           NOT NULL,
    source_package    TEXT,
    uploaded_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_health_metrics_study_participant_ts
    ON health_metrics (study_id, participant_id, sample_timestamp);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'health_metrics') THEN
        ALTER TABLE health_metrics ENABLE ROW LEVEL SECURITY;
        ALTER TABLE health_metrics FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_health_metrics ON health_metrics;
        CREATE POLICY study_isolation_health_metrics ON health_metrics
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V40__add_health_metrics', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
