-- =============================================================================
-- Row-Level Security (RLS) for candidates table
-- =============================================================================
-- The candidates table does not have a direct study_id column. Access is
-- controlled by checking whether the candidate_id appears in study_participants
-- for any study the current session is authorized to access.
-- =============================================================================

CREATE OR REPLACE FUNCTION chronicle_has_candidate_access(check_candidate_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    is_admin_user BOOLEAN;
    authorized_studies_setting TEXT;
    authorized_studies UUID[];
BEGIN
    is_admin_user := COALESCE(
        NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
        false
    );

    IF is_admin_user THEN
        RETURN true;
    END IF;

    authorized_studies_setting := current_setting('app.authorized_studies', true);

    IF authorized_studies_setting IS NULL OR authorized_studies_setting = '' THEN
        RETURN false;
    END IF;

    BEGIN
        authorized_studies := string_to_array(authorized_studies_setting, ',')::UUID[];
    EXCEPTION WHEN OTHERS THEN
        RETURN false;
    END;

    RETURN EXISTS (
        SELECT 1 FROM study_participants sp
        WHERE sp.candidate_id = check_candidate_id
          AND sp.study_id = ANY(authorized_studies)
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION chronicle_has_candidate_access(UUID) TO PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'candidates') THEN
        ALTER TABLE candidates ENABLE ROW LEVEL SECURITY;
        ALTER TABLE candidates FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_candidates ON candidates;
        CREATE POLICY study_isolation_candidates ON candidates
            FOR ALL
            USING (chronicle_has_candidate_access(candidate_id))
            WITH CHECK (chronicle_has_candidate_access(candidate_id));
    END IF;
END $$;
