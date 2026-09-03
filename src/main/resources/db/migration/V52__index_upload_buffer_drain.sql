CREATE INDEX IF NOT EXISTS upload_buffer_type_uploaded_at_idx
    ON upload_buffer (upload_type, uploaded_at);

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V52__index_upload_buffer_drain', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
