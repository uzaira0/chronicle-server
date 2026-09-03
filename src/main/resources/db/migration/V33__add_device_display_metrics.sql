-- =============================================================================
-- sensor_availability: add static display context (resolution + density + rotation)
-- =============================================================================
-- Adds the device's static display profile to the android_device_sensor_availability report
-- (com.openlattice.chronicle.android.AndroidDeviceSensorAvailability). Captured at least once as
-- part of the general, always-on device-capability report so raw on-screen pixel coordinates
-- (interaction_events) and orientation signals (sensor_screen_orientation) are interpretable even
-- when no pixel-capturing module is enabled:
--   * screen_width_pixels / screen_height_pixels — display resolution at report time
--   * screen_density_dpi   — display density (DPI)
--   * display_rotation     — Surface.ROTATION_* ordinal (0..3)
-- All nullable / additive (older clients omit them; mirrors the new SCREEN_WIDTH_PIXELS /
-- SCREEN_HEIGHT_PIXELS / DISPLAY_SCREEN_DENSITY_DPI / DISPLAY_ROTATION columns on the
-- ANDROID_DEVICE_SENSOR_AVAILABILITY PostgresTableDefinition). Existing rows keep NULL.
-- =============================================================================

ALTER TABLE IF EXISTS android_device_sensor_availability
    ADD COLUMN IF NOT EXISTS screen_width_pixels  INTEGER,
    ADD COLUMN IF NOT EXISTS screen_height_pixels INTEGER,
    ADD COLUMN IF NOT EXISTS screen_density_dpi   INTEGER,
    ADD COLUMN IF NOT EXISTS display_rotation     INTEGER;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V33__add_device_display_metrics', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
