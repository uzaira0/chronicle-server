-- =============================================================================
-- notification_activity table — Android notification_activity collection module
-- =============================================================================
-- Stores one row per AndroidNotificationActivityEvent
-- (com.openlattice.chronicle.collection.AndroidNotificationActivityEvent) uploaded by the
-- Android `notification_activity` module, scoped to a study + participant.
--
-- BEHAVIORAL_METADATA-class data — content-free by construction: each row carries the posting
-- package, the Android notification category constant (msg/call/email/alarm/social…), whether
-- the notification was posted or removed, and a few content-free flags, but never the
-- notification's title, text, or any free-form payload.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- NOTIFICATION_ACTIVITY PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent
-- regardless of migration/framework ordering, and — like V31 — retrofits Row-Level
-- Security so notification_activity is study-isolated exactly like the other Android
-- collection tables.
--
-- event_id is the per-event de-duplication key (AndroidNotificationActivityEvent.id is a
-- free-form String). The (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS notification_activity (
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    event_id            TEXT        NOT NULL,
    sample_timestamp    TIMESTAMPTZ NOT NULL,
    timezone            TEXT        NOT NULL,
    event_type          TEXT        NOT NULL,
    package_name        TEXT        NOT NULL,
    category            TEXT,
    ongoing             BOOLEAN,
    importance          INTEGER,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

-- Index supporting RLS-filtered, study/participant-scoped time-range queries.
CREATE INDEX IF NOT EXISTS idx_notification_activity_study_participant_ts
    ON notification_activity (study_id, participant_id, sample_timestamp);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V31__add_interaction_events
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'notification_activity') THEN
        ALTER TABLE notification_activity ENABLE ROW LEVEL SECURITY;
        ALTER TABLE notification_activity FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_notification_activity ON notification_activity;
        CREATE POLICY study_isolation_notification_activity ON notification_activity
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V36__add_notification_activity', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
