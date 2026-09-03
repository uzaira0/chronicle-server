-- Record the fail-closed reconciliation performed when a newer Chronicle database is
-- replaced by an older backup. The transient checkpoint lives outside public and is
-- removed only in the same transaction that appends this immutable receipt.

CREATE TABLE restore_continuity_reconciliations (
    checkpoint_id UUID PRIMARY KEY,
    contract_version INTEGER NOT NULL CHECK (contract_version = 1),
    source_schema_version TEXT NOT NULL CHECK (btrim(source_schema_version) <> ''),
    checkpoint_sha256 TEXT NOT NULL CHECK (checkpoint_sha256 ~ '^[0-9a-f]{64}$'),
    withdrawal_receipt_count BIGINT NOT NULL CHECK (withdrawal_receipt_count >= 0),
    revoked_api_key_count BIGINT NOT NULL CHECK (revoked_api_key_count >= 0),
    withdrawn_participant_count BIGINT NOT NULL CHECK (withdrawn_participant_count >= 0),
    deletion_operation_count BIGINT NOT NULL CHECK (deletion_operation_count >= 0),
    source_tombstone_count BIGINT NOT NULL CHECK (source_tombstone_count >= 0),
    already_protected_deletion_count BIGINT NOT NULL
        CHECK (already_protected_deletion_count >= 0),
    replayed_completed_deletion_count BIGINT NOT NULL
        CHECK (replayed_completed_deletion_count >= 0),
    CHECK (
        already_protected_deletion_count + replayed_completed_deletion_count =
        source_tombstone_count
    ),
    reconciled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE restore_continuity_reconciliations IS
    'Immutable receipt proving that post-backup withdrawals, credential revocations, and deletion evidence were reconciled before a restored backend became ready.';

CREATE OR REPLACE FUNCTION chronicle_reject_restore_continuity_receipt_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'Restore continuity reconciliation receipts are immutable'
        USING ERRCODE = '55000';
END
$$;

REVOKE ALL ON FUNCTION chronicle_reject_restore_continuity_receipt_mutation() FROM PUBLIC;

CREATE TRIGGER restore_continuity_receipts_no_update_delete
BEFORE UPDATE OR DELETE ON restore_continuity_reconciliations
FOR EACH ROW EXECUTE FUNCTION chronicle_reject_restore_continuity_receipt_mutation();

CREATE TRIGGER restore_continuity_receipts_no_truncate
BEFORE TRUNCATE ON restore_continuity_reconciliations
FOR EACH STATEMENT EXECUTE FUNCTION chronicle_reject_restore_continuity_receipt_mutation();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE ALL ON restore_continuity_reconciliations FROM chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON restore_continuity_reconciliations
            FROM chronicle_admin;
        GRANT SELECT ON restore_continuity_reconciliations TO chronicle_admin;
    END IF;
END $$;
