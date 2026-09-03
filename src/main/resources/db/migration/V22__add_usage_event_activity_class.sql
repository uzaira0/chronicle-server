ALTER TABLE chronicle_usage_events
    ADD COLUMN IF NOT EXISTS activity_class text;

CREATE INDEX IF NOT EXISTS chronicle_usage_events_activity_class_idx
    ON chronicle_usage_events (study_id, participant_id, activity_class);
