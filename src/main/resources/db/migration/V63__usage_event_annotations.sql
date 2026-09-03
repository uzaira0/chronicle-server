-- Durable annotations over usage-event rows so data-quality findings can flag or exclude rows
-- without mutating or deleting research data. First use: the historical false 'Device Startup'
-- rows synthesized from deferred BOOT_COMPLETED broadcasts (see docs/db/
-- FALSE-STARTUP-AUDIT-2026-07-15.md); researchers exclude flagged rows by anti-joining this table.
CREATE TABLE IF NOT EXISTS usage_event_annotations (
    study_id uuid NOT NULL,
    participant_id text NOT NULL,
    event_timestamp timestamptz NOT NULL,
    event_type integer NOT NULL,
    annotation text NOT NULL,
    reason text NOT NULL,
    annotated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_timestamp, event_type, annotation)
);

ALTER TABLE usage_event_annotations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS study_isolation_usage_event_annotations ON usage_event_annotations;
CREATE POLICY study_isolation_usage_event_annotations ON usage_event_annotations
    FOR ALL
    USING (chronicle_has_study_access((study_id)::text));
