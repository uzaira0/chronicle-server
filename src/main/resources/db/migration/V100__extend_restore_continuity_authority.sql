-- Contract v2 proves that restore reconciliation also preserved collection-settings
-- authority and enrollment-invitation state. Existing v1 receipts remain valid historical
-- evidence and receive zero counts for fields that did not exist in that contract.

ALTER TABLE restore_continuity_reconciliations
    DROP CONSTRAINT restore_continuity_reconciliations_contract_version_check;

ALTER TABLE restore_continuity_reconciliations
    ADD CONSTRAINT restore_continuity_reconciliations_contract_version_check
        CHECK (contract_version IN (1, 2)),
    ADD COLUMN collection_revision_count BIGINT NOT NULL DEFAULT 0
        CHECK (collection_revision_count >= 0),
    ADD COLUMN published_collection_settings_count BIGINT NOT NULL DEFAULT 0
        CHECK (published_collection_settings_count >= 0),
    ADD COLUMN enrollment_invitation_count BIGINT NOT NULL DEFAULT 0
        CHECK (enrollment_invitation_count >= 0);

COMMENT ON TABLE restore_continuity_reconciliations IS
    'Immutable receipt proving that post-backup withdrawals, credential revocations, deletion evidence, collection-settings authority, and enrollment-invitation state were reconciled before a restored backend became ready.';
