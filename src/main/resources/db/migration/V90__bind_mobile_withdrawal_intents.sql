-- A revoked credential may replay only the exact self-withdrawal it authorized while active.
-- The caller supplies a stable UUID before the first request; this immutable row is written
-- before deletion scheduling, participation changes, or API-key revocation.
--
-- api_key_id is deliberately copied evidence rather than a foreign key. Participant erasure
-- retains this receipt and its revoked credential so a lost HTTP response remains replayable;
-- full study erasure deletes both independently. An FK to api_keys would make that deletion
-- order-dependent and could prevent the study from being erased.

CREATE TABLE mobile_withdrawal_requests (
    request_id UUID PRIMARY KEY,
    api_key_id UUID NOT NULL UNIQUE,
    study_id UUID NOT NULL,
    participant_id TEXT NOT NULL,
    device_id UUID NOT NULL,
    already_withdrawn BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX mobile_withdrawal_requests_subject_idx
    ON mobile_withdrawal_requests (study_id, participant_id, device_id);

ALTER TABLE mobile_withdrawal_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE mobile_withdrawal_requests FORCE ROW LEVEL SECURITY;

CREATE POLICY study_isolation_mobile_withdrawal_requests_select
    ON mobile_withdrawal_requests
    FOR SELECT
    USING (chronicle_has_study_access(study_id));

CREATE POLICY study_isolation_mobile_withdrawal_requests_insert
    ON mobile_withdrawal_requests
    FOR INSERT
    WITH CHECK (chronicle_has_study_access(study_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON mobile_withdrawal_requests FROM chronicle;
        GRANT SELECT, INSERT ON mobile_withdrawal_requests TO chronicle;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON mobile_withdrawal_requests FROM chronicle_app;
        GRANT SELECT, INSERT ON mobile_withdrawal_requests TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON mobile_withdrawal_requests FROM chronicle_admin;
        GRANT SELECT ON mobile_withdrawal_requests TO chronicle_admin;
    END IF;
END $$;

COMMENT ON TABLE mobile_withdrawal_requests IS
    'Immutable mobile self-withdrawal intent binding one request UUID to one exact API-key subject tuple before credential revocation. Retained through participant erasure for replay; deleted by full study erasure.';

COMMENT ON COLUMN mobile_withdrawal_requests.api_key_id IS
    'Copied UUID evidence of the authorizing credential; intentionally has no FK so credential erasure cannot invalidate or block the durable receipt.';
