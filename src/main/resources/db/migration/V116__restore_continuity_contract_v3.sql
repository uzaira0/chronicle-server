-- chronicle:destructive-approved Drops only the contract-version CHECK to re-add it wider; no data changes.
-- Contract v3 checkpoints carry each deletion operation's participant_block_token, so a completed
-- erasure (whose participant_id completion clears) is validated and replayed without a clear
-- identifier, and they carry unexpired erased_device_key_tombstones with their original expiry.

ALTER TABLE restore_continuity_reconciliations
    DROP CONSTRAINT restore_continuity_reconciliations_contract_version_check;

ALTER TABLE restore_continuity_reconciliations
    ADD CONSTRAINT restore_continuity_reconciliations_contract_version_check
        CHECK (contract_version IN (1, 2, 3));

-- Restore reconciliation resets a tombstone the restored schema already holds (V115 writes restore-time
-- expiries for a pre-V114 backup) to the checkpoint's kind and original expiry; only the worker may.
CREATE POLICY erased_device_key_tombstones_restore ON erased_device_key_tombstones
FOR UPDATE USING (public.chronicle_is_deletion_worker()) WITH CHECK (public.chronicle_is_deletion_worker());
