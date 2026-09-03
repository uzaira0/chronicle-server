-- Keep operator credential-rotation history in the one authoritative Flyway schema chain.
--
-- Older self-host releases created this exact table lazily from rotate-secret.sh. The
-- IF NOT EXISTS clause adopts those installations without rewriting or discarding their
-- existing timestamps; fresh installations create it during normal backend migration.
CREATE TABLE IF NOT EXISTS secret_rotation_tracking (
    secret_name TEXT PRIMARY KEY,
    last_rotated TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_by TEXT,
    notes TEXT
);

COMMENT ON TABLE secret_rotation_tracking IS
    'Non-secret operator receipts for self-host credential and TDE key rotations';
COMMENT ON COLUMN secret_rotation_tracking.notes IS
    'Operational note only; credential values and key material must never be stored here';
