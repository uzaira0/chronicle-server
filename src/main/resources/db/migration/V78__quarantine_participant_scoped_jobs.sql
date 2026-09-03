-- Jobs became study/participant scoped in V74. Put them behind the same
-- study-isolation and deletion-quarantine boundary as every other registered
-- participant data asset.

ALTER TABLE jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE jobs FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS study_isolation_jobs ON jobs;
CREATE POLICY study_isolation_jobs ON jobs
    FOR ALL
    USING (
        study_id IS NULL
        OR chronicle_has_study_access(study_id)
    )
    WITH CHECK (
        study_id IS NULL
        OR chronicle_has_study_access(study_id)
    );

DROP POLICY IF EXISTS deletion_quarantine_jobs ON jobs;
CREATE POLICY deletion_quarantine_jobs ON jobs
    AS RESTRICTIVE
    FOR SELECT
    USING (
        chronicle_participant_data_visible(study_id, NULL)
        AND NOT EXISTS (
            SELECT 1
            FROM unnest(participant_ids) AS participant(participant_id)
            WHERE NOT chronicle_participant_data_visible(
                study_id,
                participant.participant_id
            )
        )
    );
