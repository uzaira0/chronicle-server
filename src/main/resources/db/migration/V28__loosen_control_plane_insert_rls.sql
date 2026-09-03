-- =============================================================================
-- V28: Loosen INSERT WITH CHECK on control-plane creation tables
-- =============================================================================
-- Study creation writes organization_studies, study_limits, and filtered_apps for a
-- brand-new study, BEFORE the creator is authorized for that study (authorization is
-- attached immediately afterward, in the same request, but the per-request
-- app.authorized_studies session variable was captured at request start and does not
-- yet include the new study). The V1 policy on these tables is
--   FOR ALL USING (chronicle_has_study_access(study_id))
--          WITH CHECK (chronicle_has_study_access(study_id))
-- so once the request connection runs as the non-superuser chronicle_app role (and no
-- longer bypasses RLS as a superuser), that creation INSERT is rejected.
--
-- Fix: split the single FOR ALL policy into per-command policies. INSERT becomes
-- permissive — creation is gated by application-layer authorization (AuthorizationManager),
-- not by row-level study membership the creator cannot yet have. SELECT / UPDATE / DELETE
-- keep full study isolation, so a principal still cannot read, re-point, or remove another
-- study's control-plane rows. PHI / event tables (sensor_data, chronicle_usage_events,
-- study_participants, ...) are intentionally untouched and keep strict WITH CHECK.
--
-- Idempotent: drops the old FOR ALL policy and any prior per-command policies first.
-- =============================================================================

-- organization_studies (study <-> organization membership)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'organization_studies') THEN
        DROP POLICY IF EXISTS study_isolation_org_studies ON organization_studies;
        DROP POLICY IF EXISTS study_isolation_org_studies_select ON organization_studies;
        DROP POLICY IF EXISTS study_isolation_org_studies_insert ON organization_studies;
        DROP POLICY IF EXISTS study_isolation_org_studies_update ON organization_studies;
        DROP POLICY IF EXISTS study_isolation_org_studies_delete ON organization_studies;

        CREATE POLICY study_isolation_org_studies_select ON organization_studies
            FOR SELECT USING (chronicle_has_study_access(study_id));
        CREATE POLICY study_isolation_org_studies_insert ON organization_studies
            FOR INSERT WITH CHECK (true);
        CREATE POLICY study_isolation_org_studies_update ON organization_studies
            FOR UPDATE USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
        CREATE POLICY study_isolation_org_studies_delete ON organization_studies
            FOR DELETE USING (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- study_limits (study-scoped limits, initialized at creation)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'study_limits') THEN
        DROP POLICY IF EXISTS study_isolation_study_limits ON study_limits;
        DROP POLICY IF EXISTS study_isolation_study_limits_select ON study_limits;
        DROP POLICY IF EXISTS study_isolation_study_limits_insert ON study_limits;
        DROP POLICY IF EXISTS study_isolation_study_limits_update ON study_limits;
        DROP POLICY IF EXISTS study_isolation_study_limits_delete ON study_limits;

        CREATE POLICY study_isolation_study_limits_select ON study_limits
            FOR SELECT USING (chronicle_has_study_access(study_id));
        CREATE POLICY study_isolation_study_limits_insert ON study_limits
            FOR INSERT WITH CHECK (true);
        CREATE POLICY study_isolation_study_limits_update ON study_limits
            FOR UPDATE USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
        CREATE POLICY study_isolation_study_limits_delete ON study_limits
            FOR DELETE USING (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- filtered_apps (study-scoped app filtering, initialized at creation)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'filtered_apps') THEN
        DROP POLICY IF EXISTS study_isolation_filtered_apps ON filtered_apps;
        DROP POLICY IF EXISTS study_isolation_filtered_apps_select ON filtered_apps;
        DROP POLICY IF EXISTS study_isolation_filtered_apps_insert ON filtered_apps;
        DROP POLICY IF EXISTS study_isolation_filtered_apps_update ON filtered_apps;
        DROP POLICY IF EXISTS study_isolation_filtered_apps_delete ON filtered_apps;

        CREATE POLICY study_isolation_filtered_apps_select ON filtered_apps
            FOR SELECT USING (chronicle_has_study_access(study_id));
        CREATE POLICY study_isolation_filtered_apps_insert ON filtered_apps
            FOR INSERT WITH CHECK (true);
        CREATE POLICY study_isolation_filtered_apps_update ON filtered_apps
            FOR UPDATE USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
        CREATE POLICY study_isolation_filtered_apps_delete ON filtered_apps
            FOR DELETE USING (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- candidates (V14): chronicle_has_candidate_access() is true only once the candidate is
-- already linked to an authorized study via study_participants. Candidate registration
-- inserts the candidate BEFORE that link exists (registerParticipant -> registerCandidate
-- then enrollment), so the WITH CHECK can never pass at creation. Permit INSERT; keep
-- read/update/delete isolated by candidate access.
--
-- Also: an as-yet-unlinked (orphan) candidate is invisible under the V14 function, which
-- breaks the registration validity check (exists()) when enrolling a pre-existing
-- standalone candidate. Treat orphan candidates as visible — they carry no PII (the table
-- is candidate_id + expiration only) and no study association — while study-linked
-- candidates stay isolated to their authorized studies.
CREATE OR REPLACE FUNCTION chronicle_has_candidate_access(check_candidate_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    is_admin_user BOOLEAN;
    authorized_studies_setting TEXT;
    authorized_studies UUID[];
    is_linked BOOLEAN;
BEGIN
    is_admin_user := COALESCE(
        NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
        false
    );
    IF is_admin_user THEN
        RETURN true;
    END IF;

    -- Orphan candidate (not yet linked to any study) is visible: needed so candidate
    -- registration can validate the id before the study_participants link is created.
    is_linked := EXISTS (
        SELECT 1 FROM study_participants sp WHERE sp.candidate_id = check_candidate_id
    );
    IF NOT is_linked THEN
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

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'candidates') THEN
        DROP POLICY IF EXISTS study_isolation_candidates ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_select ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_insert ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_update ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_delete ON candidates;

        CREATE POLICY study_isolation_candidates_select ON candidates
            FOR SELECT USING (chronicle_has_candidate_access(candidate_id));
        CREATE POLICY study_isolation_candidates_insert ON candidates
            FOR INSERT WITH CHECK (true);
        CREATE POLICY study_isolation_candidates_update ON candidates
            FOR UPDATE USING (chronicle_has_candidate_access(candidate_id))
            WITH CHECK (chronicle_has_candidate_access(candidate_id));
        CREATE POLICY study_isolation_candidates_delete ON candidates
            FOR DELETE USING (chronicle_has_candidate_access(candidate_id));
    END IF;
END $$;
