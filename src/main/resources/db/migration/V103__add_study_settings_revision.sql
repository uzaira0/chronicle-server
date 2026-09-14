-- Optimistic-concurrency token for study settings.
--
-- The dashboard reads the full settings map and then PATCHes individual setting types. Two
-- dashboards that read the same map and then write different types used to clobber each other
-- with no way for either to notice. This column is a per-study monotonically increasing revision
-- that every settings write bumps, so a client can supply the revision it read as an `If-Match`
-- precondition and be told (412) when its view is stale.
--
-- Existing studies start at 0, which is a valid revision: a client that reads 0 and writes with
-- `If-Match: "0"` succeeds exactly once before the value moves to 1.
ALTER TABLE studies
    ADD COLUMN IF NOT EXISTS settings_revision BIGINT NOT NULL DEFAULT 0;
