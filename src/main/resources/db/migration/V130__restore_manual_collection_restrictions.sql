-- chronicle:destructive-approved Widens the immutable restore receipt contract; no data is removed.
ALTER TABLE restore_continuity_reconciliations
    DROP CONSTRAINT restore_continuity_reconciliations_contract_version_check;
ALTER TABLE restore_continuity_reconciliations
    ADD CONSTRAINT restore_continuity_reconciliations_contract_version_check
        CHECK (contract_version IN (1, 2, 3, 4)),
    ADD COLUMN restricted_participant_count BIGINT NOT NULL DEFAULT 0 CHECK (restricted_participant_count >= 0),
    ADD COLUMN archived_study_count BIGINT NOT NULL DEFAULT 0 CHECK (archived_study_count >= 0);
