-- =============================================================================
-- device_settings: add audio settings (no microphone) + screen brightness
-- =============================================================================
-- Folds the participant's audio configuration and screen brightness into the content-free
-- device_settings snapshot (com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent):
--   * screen_brightness / screen_brightness_auto — brightness level (0–255) + adaptive flag
--   * media/ring/notification/alarm volume (+ each stream's device max) — how loud, not what
--   * ringer_mode — normal / vibrate / silent
-- These describe how the device is configured, never what the participant does, and the audio
-- columns are derived purely from AudioManager stream levels — NO microphone, NO audio capture,
-- NO sound content. DEVICE_STATE_METADATA-class, default OFF.
--
-- All nullable / additive (older clients omit them; mirrors the new DEVICE_SETTINGS_* columns on
-- the DEVICE_SETTINGS PostgresTableDefinition). Existing rows keep NULL. Migrations are not
-- auto-discovered by filename — V45 runs only because DeviceSettingsAudioBrightnessUpgrade points
-- at it. The boot PostgresTables framework only CREATE TABLE IF NOT EXISTS-es and never ALTERs an
-- existing table, so without this an already-deployed device_settings would lack these columns and
-- the DeviceSettingsUploadService INSERT naming them would fail at runtime.
-- =============================================================================

ALTER TABLE IF EXISTS device_settings
    ADD COLUMN IF NOT EXISTS screen_brightness        INTEGER,
    ADD COLUMN IF NOT EXISTS screen_brightness_auto   BOOLEAN,
    ADD COLUMN IF NOT EXISTS media_volume             INTEGER,
    ADD COLUMN IF NOT EXISTS media_volume_max         INTEGER,
    ADD COLUMN IF NOT EXISTS ring_volume              INTEGER,
    ADD COLUMN IF NOT EXISTS ring_volume_max          INTEGER,
    ADD COLUMN IF NOT EXISTS notification_volume      INTEGER,
    ADD COLUMN IF NOT EXISTS notification_volume_max  INTEGER,
    ADD COLUMN IF NOT EXISTS alarm_volume             INTEGER,
    ADD COLUMN IF NOT EXISTS alarm_volume_max         INTEGER,
    ADD COLUMN IF NOT EXISTS ringer_mode              TEXT;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V45__add_device_settings_audio_brightness', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
