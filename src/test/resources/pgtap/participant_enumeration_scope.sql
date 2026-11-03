SELECT plan(6);
INSERT INTO studies (study_id, title) VALUES ('11111111-1111-1111-1111-111111111111', 'own fixture'), ('22222222-2222-2222-2222-222222222222', 'foreign fixture');
INSERT INTO candidates (candidate_id, expiration_date)
VALUES ('66666666-6666-6666-6666-666666666666', now() + interval '1 day'),
    ('77777777-7777-7777-7777-777777777777', now() + interval '1 day');
INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status, processing_blocked_at,
    processing_block_reason, processing_blocked_by)
VALUES ('11111111-1111-1111-1111-111111111111', 'tap-own', '66666666-6666-6666-6666-666666666666', 'ENROLLED', now(), 'tap', 'tap'),
    ('22222222-2222-2222-2222-222222222222', 'tap-foreign', '77777777-7777-7777-7777-777777777777', 'ENROLLED', now(), 'tap', 'tap');
INSERT INTO data_deletion_operations (operation_id, study_id, participant_id, mode, status, requested_by,
    idempotency_key, registry_version)
VALUES ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'tap-own',
        'COLLECTED_DATA_PURGE', 'QUARANTINED', 'tap', '88888888-8888-8888-8888-888888888881', 1),
    ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'tap-foreign',
        'COLLECTED_DATA_PURGE', 'QUARANTINED', 'tap', '88888888-8888-8888-8888-888888888882', 1),
    ('55555555-5555-5555-5555-555555555555', '22222222-2222-2222-2222-222222222222', NULL,
        'STUDY_ERASURE', 'QUARANTINED', 'tap', '88888888-8888-8888-8888-888888888883', 1);
CREATE FUNCTION pg_temp.as_app(command TEXT) RETURNS SETOF TEXT LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE 'SET LOCAL ROLE chronicle_app';
    RETURN QUERY EXECUTE command;
    EXECUTE 'RESET ROLE';
END
$$;
SELECT set_config('app.is_admin', 'false', true);
SELECT set_config('app.current_user_id', 'tap-user', true);
SELECT set_config('app.authorized_studies', '11111111-1111-1111-1111-111111111111', true);
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT participant_id FROM public.chronicle_erasing_participants()')$$,
    ARRAY['tap-own'], 'a study member sees only its own studies'' erasing participants');
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT participant_id FROM public.chronicle_processing_blocked_participants()')$$,
    ARRAY['tap-own'], 'a study member sees only its own studies'' blocked participants');
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT * FROM public.chronicle_erasing_study_ids()')$$,
    ARRAY[]::text[], 'a study member does not see other studies under erasure');
SELECT set_config('app.authorized_studies', '', true);
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT participant_id FROM public.chronicle_processing_blocked_participants()')$$,
    ARRAY[]::text[], 'no authorized study enumerates nothing');
SELECT set_config('app.is_admin', 'true', true);
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT participant_id FROM public.chronicle_erasing_participants() ORDER BY 1')$$,
    ARRAY['tap-foreign', 'tap-own'], 'admin and background work see every erasing participant');
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT * FROM public.chronicle_erasing_study_ids()')$$,
    ARRAY['22222222-2222-2222-2222-222222222222'], 'admin and background work see every study under erasure');
RESET ROLE;
SELECT * FROM finish();
