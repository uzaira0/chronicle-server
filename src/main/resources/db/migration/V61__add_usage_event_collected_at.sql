-- Preserve the Android durable queue-write time separately from the framework event time and
-- server receipt time. Rows written by older clients cannot recover this value, so their existing
-- server receipt time is the explicit compatibility fallback.
ALTER TABLE chronicle_usage_events
    ADD COLUMN IF NOT EXISTS collected_at timestamptz;

UPDATE chronicle_usage_events
SET collected_at = uploaded_at
WHERE collected_at IS NULL;

ALTER TABLE chronicle_usage_events
    ALTER COLUMN collected_at SET NOT NULL;
