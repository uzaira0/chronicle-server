-- usage_event_annotations (V63) and participant_pseudonyms (V56) carry study_id/participant_id
-- but were never added to the participant data-asset registry, so participant deletion left
-- them behind. The registry now deletes them; like every registered asset they also hide a
-- quarantined participant's rows (V50 pattern). Keep the pinned policy count in
-- FlywayMigrationCorpusTest.testDeletionLedgerAndParticipantAccess in sync.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid
        WHERE n.nspname = 'public' AND p.proname = 'chronicle_participant_data_visible'
    ) THEN
        DROP POLICY IF EXISTS deletion_quarantine_usage_event_annotations ON usage_event_annotations;
        CREATE POLICY deletion_quarantine_usage_event_annotations ON usage_event_annotations
            AS RESTRICTIVE FOR SELECT
            USING (chronicle_participant_data_visible(study_id, participant_id));

        DROP POLICY IF EXISTS deletion_quarantine_participant_pseudonyms ON participant_pseudonyms;
        CREATE POLICY deletion_quarantine_participant_pseudonyms ON participant_pseudonyms
            AS RESTRICTIVE FOR SELECT
            USING (chronicle_participant_data_visible(study_id, participant_id));
    END IF;
END $$;

-- The participant deletion job runs as chronicle_app, which was never granted these tables.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, DELETE ON usage_event_annotations, participant_pseudonyms TO chronicle_app;
    END IF;
END $$;
