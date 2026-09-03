ALTER TABLE participant_collection_acknowledgment
    ADD COLUMN IF NOT EXISTS disclosure_version TEXT,
    ADD COLUMN IF NOT EXISTS manifest_digest TEXT;

ALTER TABLE participant_collection_acknowledgment
    DROP CONSTRAINT IF EXISTS participant_collection_ack_disclosure_pair_check;

ALTER TABLE participant_collection_acknowledgment
    ADD CONSTRAINT participant_collection_ack_disclosure_pair_check CHECK (
        (disclosure_version IS NULL AND manifest_digest IS NULL)
        OR
        (
            disclosure_version IS NOT NULL
            AND manifest_digest IS NOT NULL
            AND
            length(btrim(disclosure_version)) > 0
            AND manifest_digest ~ '^[0-9a-f]{64}$'
        )
    );

COMMENT ON COLUMN participant_collection_acknowledgment.disclosure_version IS
    'Study consent/disclosure version presented with this participant decision; paired with manifest_digest.';

COMMENT ON COLUMN participant_collection_acknowledgment.manifest_digest IS
    'Lowercase SHA-256 digest of the authoritative enrollment manifest the participant reviewed; paired with disclosure_version.';
