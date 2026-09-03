-- =============================================================================
-- interaction_events: add exact interaction position (raw pixels + screen res + normalized)
-- =============================================================================
-- Adds the exact-position bundle to the interaction_events table
-- (com.openlattice.chronicle.collection.AndroidInteractionEvent), recorded when the study
-- captures exact position (the default, InteractionPolicy.captureExactPosition = true):
--   * raw_x / raw_y          — the interacted element's center in raw device pixels
--   * screen_width / screen_height — the screen resolution at event time (so raw_x/raw_y
--                              are interpretable)
--   * normalized_x / normalized_y — raw_x/screen_width, raw_y/screen_height in [0, 1]
--                              (portable across devices/resolutions)
-- All are NULL in the coarse-grid-only privacy mode (captureExactPosition = false), so every
-- column is nullable.
--
-- Additive only (mirrors how the rhizome PostgresTables framework would add the new
-- INTERACTION_RAW_X / INTERACTION_RAW_Y / INTERACTION_SCREEN_WIDTH / INTERACTION_SCREEN_HEIGHT /
-- INTERACTION_NORMALIZED_X / INTERACTION_NORMALIZED_Y columns from the updated
-- INTERACTION_EVENTS PostgresTableDefinition). Existing rows keep NULL positions.
-- =============================================================================

ALTER TABLE IF EXISTS interaction_events
    ADD COLUMN IF NOT EXISTS raw_x         INTEGER,
    ADD COLUMN IF NOT EXISTS raw_y         INTEGER,
    ADD COLUMN IF NOT EXISTS screen_width  INTEGER,
    ADD COLUMN IF NOT EXISTS screen_height INTEGER,
    ADD COLUMN IF NOT EXISTS normalized_x  DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS normalized_y  DOUBLE PRECISION;

-- Salience kinematics + context (event_time_millis = monotonic uptime clock; episode_id groups
-- an interaction burst; dwell + scroll velocity are derived; orientation/screen_density_dpi let
-- the raw position be interpreted spatially/physically). All nullable / additive.
ALTER TABLE IF EXISTS interaction_events
    ADD COLUMN IF NOT EXISTS event_time_millis       BIGINT,
    ADD COLUMN IF NOT EXISTS episode_id              TEXT,
    ADD COLUMN IF NOT EXISTS dwell_millis_since_prev BIGINT,
    ADD COLUMN IF NOT EXISTS orientation             INTEGER,
    ADD COLUMN IF NOT EXISTS screen_density_dpi      INTEGER,
    ADD COLUMN IF NOT EXISTS scroll_velocity_x       DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS scroll_velocity_y       DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS scroll_reversed         BOOLEAN;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V32__add_interaction_exact_position', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
