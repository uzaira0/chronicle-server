SELECT plan(6);
INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id, status, definition, message, deleted_rows)
VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
    'USER', 'tap-user', 'PENDING',
    '{"@type":"EmptyJobDefinition","studyId":"33333333-3333-3333-3333-333333333333","participantIds":["participant-a"]}', '', 0);
SELECT throws_ok($sql$UPDATE jobs SET study_id = '44444444-4444-4444-4444-444444444444'$sql$,
    '23514', 'Job scope columns are derived from definition', 'direct study scope update is rejected');
SELECT throws_ok($sql$UPDATE jobs SET participant_ids = ARRAY['other']$sql$,
    '23514', 'Job scope columns are derived from definition', 'direct participant scope update is rejected');
SELECT throws_ok($sql$UPDATE jobs SET study_id = study_id$sql$,
    '23514', 'Job scope columns are derived from definition', 'even a scope-only no-op is rejected');
SELECT lives_ok($sql$UPDATE jobs SET study_id = '44444444-4444-4444-4444-444444444444',
    definition = '{"@type":"EmptyJobDefinition","studyId":"44444444-4444-4444-4444-444444444444","participantIds":["participant-b"]}'$sql$,
    'definition change may update derived scope');
SELECT is((SELECT study_id::text FROM jobs), '44444444-4444-4444-4444-444444444444', 'study scope follows definition');
SELECT is((SELECT participant_ids FROM jobs), ARRAY['participant-b'], 'participant scope follows definition');
SELECT * FROM finish();
