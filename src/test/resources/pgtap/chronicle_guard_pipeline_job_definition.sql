SELECT plan(5);
INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id, status, definition, message, deleted_rows)
VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
    'USER', 'tap-user', 'PENDING',
    '{"@type":"PipelineJobDefinition","studyId":"33333333-3333-3333-3333-333333333333","config":{"enabled":true}}', '', 0);
INSERT INTO pipeline_runs (run_id, study_id, job_id, status, total_steps)
VALUES ('44444444-4444-4444-4444-444444444444', '33333333-3333-3333-3333-333333333333',
    '11111111-1111-1111-1111-111111111111', 'PENDING', 1);
SELECT lives_ok($sql$UPDATE jobs SET definition = jsonb_set(definition, '{config,enabled}', 'false')$sql$,
    'mapped pipeline definition may change config');
SELECT throws_ok($sql$UPDATE jobs SET definition = jsonb_set(definition, '{@type}', '"EmptyJobDefinition"')$sql$,
    '23503', 'A mapped pipeline job must retain its pipeline type and study', 'mapped pipeline cannot become a different job type');
SELECT throws_ok($sql$UPDATE jobs SET definition = jsonb_set(definition, '{studyId}', '"55555555-5555-5555-5555-555555555555"')$sql$,
    '23503', 'A mapped pipeline job must retain its pipeline type and study', 'mapped pipeline cannot change study');
SET CONSTRAINTS ALL IMMEDIATE;
SELECT is((SELECT study_id::text FROM jobs), '33333333-3333-3333-3333-333333333333', 'rejected updates preserve scope');
INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id, status, definition, message, deleted_rows)
VALUES ('66666666-6666-6666-6666-666666666666', '22222222-2222-2222-2222-222222222222',
    'USER', 'tap-user', 'PENDING', '{"@type":"EmptyJobDefinition"}', '', 0);
SELECT lives_ok($sql$UPDATE jobs SET definition = '{"@type":"EmptyJobDefinition","config":{"enabled":true}}'
    WHERE job_id = '66666666-6666-6666-6666-666666666666'$sql$, 'unmapped jobs may update definition');
SELECT * FROM finish();
