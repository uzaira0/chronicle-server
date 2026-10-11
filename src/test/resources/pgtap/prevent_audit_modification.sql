SELECT plan(4);
SELECT lives_ok($sql$
    INSERT INTO audit (acl_key, id, principal_type, principal_id, audit_event_type,
        study_id, organization_id, description, data, event_timestamp)
    VALUES ('tap-audit', '11111111-1111-1111-1111-111111111111', 'USER', 'tap-user',
        'GET_STUDY', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'tap-immutable', '{}', now())
$sql$, 'audit INSERT is allowed');
SELECT is((SELECT count(*) FROM audit WHERE description = 'tap-immutable'), 1::bigint, 'INSERT is durable');
SELECT throws_ok($sql$UPDATE audit SET description = 'changed' WHERE description = 'tap-immutable'$sql$,
    'P0001', 'Audit records are immutable. DELETE and UPDATE are not permitted', 'audit UPDATE rejected by trigger');
SELECT throws_ok($sql$DELETE FROM audit WHERE description = 'tap-immutable'$sql$,
    'P0001', 'Audit records are immutable. DELETE and UPDATE are not permitted', 'audit DELETE rejected by trigger');
SELECT * FROM finish();
