ALTER TABLE participant_collection_acknowledgment
    ADD COLUMN IF NOT EXISTS settings_version INTEGER;

COMMENT ON COLUMN participant_collection_acknowledgment.settings_version IS
    'Server-controlled DataCollection settings revision whose policy the participant accepted or declined; null for legacy clients.';
