SELECT plan(5);
SELECT lives_ok($sql$
    INSERT INTO restore_continuity_reconciliations (checkpoint_id, contract_version, source_schema_version,
        checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count, withdrawn_participant_count,
        deletion_operation_count, source_tombstone_count, already_protected_deletion_count, replayed_completed_deletion_count)
    VALUES ('11111111-1111-1111-1111-111111111111', 1, '98', repeat('a', 64), 0, 0, 0, 0, 0, 0, 0)
$sql$, 'restore continuity receipt INSERT allowed');
SELECT throws_ok($sql$UPDATE restore_continuity_reconciliations SET source_schema_version = '99'$sql$,
    '55000', 'Restore continuity reconciliation receipts are immutable', 'receipt UPDATE rejected');
SELECT throws_ok($sql$DELETE FROM restore_continuity_reconciliations$sql$,
    '55000', 'Restore continuity reconciliation receipts are immutable', 'receipt DELETE rejected');
SELECT throws_ok($sql$TRUNCATE restore_continuity_reconciliations$sql$,
    '55000', 'Restore continuity reconciliation receipts are immutable', 'receipt TRUNCATE rejected');
SELECT is((SELECT count(*) FROM restore_continuity_reconciliations), 1::bigint, 'receipt survives all rejected mutations');
SELECT * FROM finish();
