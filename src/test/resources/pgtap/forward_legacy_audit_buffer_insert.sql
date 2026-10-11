SELECT plan(6);
SELECT lives_ok($sql$
    INSERT INTO audit_buffer (acl_key, id, principal_type, principal_id, audit_event_type,
        study_id, organization_id, description, data, event_timestamp)
    VALUES ('tap-buffer', '11111111-1111-1111-1111-111111111111', 'USER', 'tap-user',
        'GET_STUDY', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'tap-forwarded', '{}', '2026-10-06T00:00:00Z')
$sql$, 'legacy INSERT is accepted');
SELECT is((SELECT count(*) FROM audit_buffer), 0::bigint, 'staging insert is suppressed');
SELECT is((SELECT count(*) FROM audit WHERE description = 'tap-forwarded'), 1::bigint, 'legacy write reaches authoritative audit');
SELECT lives_ok($sql$
    INSERT INTO audit_buffer SELECT * FROM audit WHERE description = 'tap-forwarded'
$sql$, 'replayed identical legacy INSERT is idempotent');
SELECT is((SELECT count(*) FROM audit WHERE description = 'tap-forwarded'), 1::bigint, 'duplicate authoritative row is rejected without losing the original');
SELECT throws_ok($sql$UPDATE audit SET description = 'rewritten' WHERE description = 'tap-forwarded'$sql$,
    'P0001', 'Audit records are immutable. DELETE and UPDATE are not permitted', 'forwarded rows remain append-only');
SELECT * FROM finish();
