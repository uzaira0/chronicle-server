-- Upload authorization derives the latest explicit decision for the active mobile key from
-- the immutable acknowledgment trail. Keep that gate index-backed as the trail grows.
CREATE INDEX participant_collection_ack_active_key_decisions
    ON participant_collection_acknowledgment (
        evidence_api_key_id,
        settings_version,
        recorded_at,
        id
    )
    WHERE evidence_api_key_id IS NOT NULL;

COMMENT ON INDEX participant_collection_ack_active_key_decisions IS
    'Supports the server-side collection halt gate for one active mobile enrollment generation.';
