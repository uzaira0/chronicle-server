CREATE TABLE researcher_create_receipts (
    study_id UUID,
    principal_id TEXT NOT NULL,
    route TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash BYTEA NOT NULL,
    response_status INTEGER,
    response_type TEXT,
    sealed_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (principal_id, route, idempotency_key),
    CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CHECK (octet_length(request_hash) = 32)
);
ALTER TABLE researcher_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE researcher_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY researcher_create_receipts_owner ON researcher_create_receipts
    USING (principal_id = current_setting('app.current_user_id', true) OR public.chronicle_is_deletion_worker())
    WITH CHECK (principal_id = current_setting('app.current_user_id', true));
DO $$
DECLARE app_role TEXT;
BEGIN
    FOREACH app_role IN ARRAY ARRAY['chronicle_app', 'chronicle_admin', 'chronicle'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON researcher_create_receipts TO %I', app_role);
            -- V116's worker-only UPDATE policy also needs this table privilege during restore.
            EXECUTE format('GRANT UPDATE ON erased_device_key_tombstones TO %I', app_role);
        END IF;
    END LOOP;
END $$;
COMMENT ON TABLE researcher_create_receipts IS
    'Atomic create receipts; raw credential results are encrypted with server secret and authenticated scope.';
