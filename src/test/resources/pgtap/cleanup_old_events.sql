SELECT plan(4);
SELECT lives_ok($sql$INSERT INTO study_event_stream (event_id, study_id, event_type, created_at)
    VALUES ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'old', now() - interval '25 hours')$sql$,
    'old event INSERT accepted and cleanup trigger executes');
SELECT is((SELECT count(*) FROM study_event_stream WHERE event_type = 'old'), 0::bigint, 'events beyond the retention window are rejected from storage');
INSERT INTO study_event_stream (event_id, study_id, event_type, created_at)
VALUES ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', 'boundary', now() - interval '24 hours'),
    ('44444444-4444-4444-4444-444444444444', '33333333-3333-3333-3333-333333333333', 'recent', now() - interval '1 hour');
SELECT is((SELECT count(*) FROM study_event_stream WHERE event_type = 'boundary'), 1::bigint, 'exactly 24-hour-old event survives the strict cutoff');
SELECT is((SELECT count(*) FROM study_event_stream WHERE event_type = 'recent'), 1::bigint, 'recent event survives cleanup');
SELECT * FROM finish();
