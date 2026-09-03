-- Upload buffering now stores the server-side device UUID. Older deployments
-- still have source_device_id from the pre-derived-device-id schema.
ALTER TABLE upload_buffer ADD COLUMN IF NOT EXISTS device_id UUID;

