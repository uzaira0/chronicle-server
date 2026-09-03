-- Study-level deletion jobs delete these high-volume local tables by study_id.
-- Keep the predicates indexed so deletion does not degrade into full table scans.
CREATE INDEX IF NOT EXISTS android_sensor_data_study_id_idx
    ON android_sensor_data (study_id);

CREATE INDEX IF NOT EXISTS upload_buffer_study_id_idx
    ON upload_buffer (study_id);
