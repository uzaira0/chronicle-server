-- Raw accessibility-node bounds are the authoritative, non-interfering interaction-position
-- observation. Existing center/normalized/grid columns remain for compatibility and are not
-- exact pointer coordinates.
ALTER TABLE IF EXISTS interaction_events
    ADD COLUMN IF NOT EXISTS position_source    TEXT,
    ADD COLUMN IF NOT EXISTS node_bounds_left   INTEGER,
    ADD COLUMN IF NOT EXISTS node_bounds_top    INTEGER,
    ADD COLUMN IF NOT EXISTS node_bounds_right  INTEGER,
    ADD COLUMN IF NOT EXISTS node_bounds_bottom INTEGER,
    ADD COLUMN IF NOT EXISTS display_id         INTEGER;

-- Device/API capability explains why exact pointer coordinates are unavailable: older Fire OS
-- versions lack the API, while Android 14+ would require taking ownership of touchscreen input.
ALTER TABLE IF EXISTS android_device_sensor_availability
    ADD COLUMN IF NOT EXISTS interaction_pointer_capture_capability TEXT;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V53__add_interaction_position_provenance', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE
            SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
