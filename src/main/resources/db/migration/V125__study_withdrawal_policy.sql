-- Default every existing study to keeping data collected before participant withdrawal.
UPDATE studies
SET settings = COALESCE(settings, '{}'::jsonb) || jsonb_build_object(
    'Withdrawal', jsonb_build_object(
        '@class', 'com.openlattice.chronicle.study.StudyWithdrawalSetting',
        'mode', 'KEEP_PRIOR_DATA'));

-- Withdrawal receipts remain visible to authorized researchers even while live data is
-- quarantined or after participant erasure. The underlying tables still enforce their RLS.
CREATE VIEW study_participants_with_withdrawals WITH (security_invoker = true) AS
WITH withdrawals AS (
    SELECT study_id, participant_id, min(created_at) AS withdrawn_at
    FROM mobile_withdrawal_requests GROUP BY study_id, participant_id
)
SELECT study_id, participant_id,
       COALESCE(p.candidate_id, '00000000-0000-0000-0000-000000000000'::uuid) AS candidate_id,
       CASE WHEN w.withdrawn_at IS NOT NULL THEN 'NOT_ENROLLED' ELSE p.participation_status END AS participation_status,
       p.participant_notes, p.participant_tags, w.withdrawn_at
FROM study_participants p FULL JOIN withdrawals w USING (study_id, participant_id);

DO $$
DECLARE app_role TEXT;
BEGIN
    FOREACH app_role IN ARRAY ARRAY['chronicle', 'chronicle_app', 'chronicle_admin'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
            EXECUTE format('GRANT SELECT ON study_participants_with_withdrawals TO %I', app_role);
        END IF;
    END LOOP;
END $$;

CREATE FUNCTION chronicle_stop_withdrawn_participant_collection() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.participation_status = 'ENROLLED' AND EXISTS (
        SELECT 1 FROM mobile_withdrawal_requests
        WHERE study_id = NEW.study_id AND participant_id = NEW.participant_id
    ) THEN
        RAISE EXCEPTION 'A withdrawn participant cannot resume collection' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stop_withdrawn_participant_collection
BEFORE INSERT OR UPDATE OF participation_status ON study_participants
FOR EACH ROW EXECUTE FUNCTION chronicle_stop_withdrawn_participant_collection();
