ALTER TABLE android_sensor_data
    ADD COLUMN IF NOT EXISTS values jsonb NOT NULL DEFAULT '[]'::jsonb;
