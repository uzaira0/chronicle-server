-- A phone that stays offline through a deletion quarantine must still learn NOT_ENROLLED after
-- erasure deletes its api_keys row. The erasure keeps only the SHA-256 hash of each erased
-- device key (the hash api_keys already held), the erasure kind, and an expiry: no study,
-- participant or device identifier. A matching unexpired hash reaches only the participation
-- status read (NOT_ENROLLED) and the self-withdrawal acknowledgment ("already withdrawn").
-- Expired rows are deleted on lookup and by each run of the deletion worker.

CREATE TABLE erased_device_key_tombstones (
    key_hash TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX erased_device_key_tombstones_expiry_idx ON erased_device_key_tombstones (expires_at);

ALTER TABLE erased_device_key_tombstones ENABLE ROW LEVEL SECURITY;
ALTER TABLE erased_device_key_tombstones FORCE ROW LEVEL SECURITY;

-- Authentication lookups run in the admin context; only the deletion worker writes tombstones.
CREATE POLICY erased_device_key_tombstones_read ON erased_device_key_tombstones
FOR SELECT USING (current_setting('app.is_admin', true) = 'true');
CREATE POLICY erased_device_key_tombstones_write ON erased_device_key_tombstones
FOR INSERT WITH CHECK (public.chronicle_is_deletion_worker());
CREATE POLICY erased_device_key_tombstones_expire ON erased_device_key_tombstones
FOR DELETE USING (current_setting('app.is_admin', true) = 'true' AND expires_at <= now());

DO $$
DECLARE app_role TEXT;
BEGIN
    FOREACH app_role IN ARRAY ARRAY['chronicle_app', 'chronicle_admin', 'chronicle'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
            EXECUTE format('GRANT SELECT, INSERT, DELETE ON erased_device_key_tombstones TO %I', app_role);
        END IF;
    END LOOP;
END $$;

COMMENT ON TABLE erased_device_key_tombstones IS
    'Hash, erasure kind and expiry of device API keys deleted by participant or study erasure; holds no study, participant or device identifier.';
