ALTER TABLE sensor_data
    ADD COLUMN IF NOT EXISTS ios_screen_time_source TEXT,
    ADD COLUMN IF NOT EXISTS ios_screen_time_confidence TEXT,
    ADD COLUMN IF NOT EXISTS ios_screen_time_row_kind TEXT,
    ADD COLUMN IF NOT EXISTS ios_screen_time_app_label TEXT,
    ADD COLUMN IF NOT EXISTS ios_screen_time_bundle_id TEXT,
    ADD COLUMN IF NOT EXISTS ios_screen_time_web_domain TEXT,
    ADD COLUMN IF NOT EXISTS ios_screen_time_raw_source_label TEXT;
