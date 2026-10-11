SELECT plan(8);
INSERT INTO candidates (candidate_id, expiration_date)
VALUES ('11111111-1111-1111-1111-111111111111', now() + interval '1 day'),
    ('22222222-2222-2222-2222-222222222222', now() + interval '1 day');
INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
VALUES ('33333333-3333-3333-3333-333333333333', 'tap-participant', '11111111-1111-1111-1111-111111111111', 'ENROLLED');
CREATE FUNCTION pg_temp.candidate_change(command TEXT) RETURNS SETOF TEXT LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE 'SET LOCAL ROLE chronicle_app';
    RETURN QUERY EXECUTE command;
    EXECUTE 'RESET ROLE';
END
$$;
SELECT set_config('app.is_admin', 'false', true);
SELECT set_config('app.current_user_id', 'tap-user', true);
SELECT set_config('app.authorized_studies', '33333333-3333-3333-3333-333333333333', true);
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$UPDATE candidates SET expiration_date = now() + interval '2 days'
    WHERE candidate_id = '11111111-1111-1111-1111-111111111111' RETURNING candidate_id::text$sql$),
    ARRAY['11111111-1111-1111-1111-111111111111'], 'authorized linked candidate can be updated through RLS');
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$UPDATE candidates SET expiration_date = now() + interval '2 days'
    WHERE candidate_id = '22222222-2222-2222-2222-222222222222' RETURNING candidate_id::text$sql$),
    ARRAY[]::text[], 'orphan candidate UPDATE denied through RLS');
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$DELETE FROM candidates WHERE candidate_id = '22222222-2222-2222-2222-222222222222' RETURNING candidate_id::text$sql$),
    ARRAY[]::text[], 'orphan candidate DELETE denied through RLS');
SELECT set_config('app.authorized_studies', '44444444-4444-4444-4444-444444444444', true);
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$UPDATE candidates SET expiration_date = now() WHERE candidate_id = '11111111-1111-1111-1111-111111111111' RETURNING candidate_id::text$sql$),
    ARRAY[]::text[], 'nonmatching study cannot mutate linked candidate');
SELECT set_config('app.authorized_studies', 'malformed', true);
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$DELETE FROM candidates WHERE candidate_id = '11111111-1111-1111-1111-111111111111' RETURNING candidate_id::text$sql$),
    ARRAY[]::text[], 'malformed study context fails closed');
SELECT set_config('app.authorized_studies', '', true);
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$UPDATE candidates SET expiration_date = now() WHERE candidate_id = '11111111-1111-1111-1111-111111111111' RETURNING candidate_id::text$sql$),
    ARRAY[]::text[], 'absent authorized studies fails closed');
SELECT set_config('app.is_admin', 'true', true);
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$DELETE FROM candidates WHERE candidate_id = '22222222-2222-2222-2222-222222222222' RETURNING candidate_id::text$sql$),
    ARRAY['22222222-2222-2222-2222-222222222222'], 'admin may delete orphan candidate');
SELECT results_eq(format('SELECT * FROM pg_temp.candidate_change(%L)', $sql$DELETE FROM candidates WHERE candidate_id = '11111111-1111-1111-1111-111111111111' RETURNING candidate_id::text$sql$),
    ARRAY['11111111-1111-1111-1111-111111111111'], 'admin may delete linked candidate');
RESET ROLE;
SELECT * FROM finish();
