SELECT plan(3);
INSERT INTO studies (study_id, title) VALUES ('11111111-1111-1111-1111-111111111111', 'questionnaire fixture');
INSERT INTO candidates (candidate_id, expiration_date) VALUES ('66666666-6666-6666-6666-666666666666', now() + interval '1 day');
INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status, processing_blocked_at,
    processing_block_reason, processing_blocked_by)
VALUES ('11111111-1111-1111-1111-111111111111', 'tap-blocked', '66666666-6666-6666-6666-666666666666', 'ENROLLED',
    now(), 'tap', 'tap');
INSERT INTO questionnaire_submissions (submission_id, study_id, participant_id, questionnaire_id, completed_at,
    question_title, response)
VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'tap-blocked',
    '33333333-3333-3333-3333-333333333333', now(), 'q', ARRAY['a']);
CREATE FUNCTION pg_temp.as_app(command TEXT) RETURNS SETOF BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE 'SET LOCAL ROLE chronicle_app';
    RETURN QUERY EXECUTE command;
    EXECUTE 'RESET ROLE';
END
$$;
SELECT set_config('app.is_admin', 'false', true);
SELECT set_config('app.current_user_id', 'tap-user', true);
SELECT set_config('app.authorized_studies', '11111111-1111-1111-1111-111111111111', true);
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT EXISTS (SELECT 1 FROM questionnaire_submissions)')$$,
    ARRAY[false], 'the blocked participant''s submission is hidden from the study');
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT public.chronicle_questionnaire_has_submissions(
    ''11111111-1111-1111-1111-111111111111'', ''33333333-3333-3333-3333-333333333333'')')$$,
    ARRAY[true], 'a hidden submission still keeps its questionnaire');
SELECT set_config('app.authorized_studies', '44444444-4444-4444-4444-444444444444', true);
SELECT results_eq($$SELECT * FROM pg_temp.as_app('SELECT public.chronicle_questionnaire_has_submissions(
    ''11111111-1111-1111-1111-111111111111'', ''33333333-3333-3333-3333-333333333333'')')$$,
    ARRAY[false], 'another study learns nothing');
RESET ROLE;
SELECT * FROM finish();
