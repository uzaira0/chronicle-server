-- V80: study_participants already has study-isolation RLS and deletion-time
-- mutation guards, but it was omitted from the restrictive SELECT policies
-- created in V50. Hide participant identities from normal application reads
-- as soon as participant or study erasure enters quarantine.

ALTER TABLE study_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_participants FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS deletion_quarantine_study_participants ON study_participants;
CREATE POLICY deletion_quarantine_study_participants ON study_participants
    AS RESTRICTIVE
    FOR SELECT
    USING (chronicle_participant_data_visible(study_id, participant_id));

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V80__hide_study_participants_during_deletion_quarantine', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
