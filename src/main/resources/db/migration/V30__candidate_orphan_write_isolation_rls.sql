-- =============================================================================
-- V30: Restrict orphan-candidate visibility to SELECT only
-- =============================================================================
-- V28 made an as-yet-unlinked (orphan) candidate visible under
-- chronicle_has_candidate_access() so candidate registration's validity check
-- (CandidateService.exists -> SELECT) can confirm a pre-existing standalone candidate
-- before the study_participants link is created. But that single function gates SELECT,
-- UPDATE, and DELETE alike, so it ALSO let any authenticated principal UPDATE or DELETE an
-- orphan candidate row it does not (yet) own — a latent over-grant.
--
-- CandidateService only ever SELECTs (exists / getCandidate) and INSERTs candidates; it
-- never UPDATEs or DELETEs them, and orphan rows carry no PII or study association
-- (candidate_id only). So orphan visibility is needed for SELECT alone.
--
-- This migration adds chronicle_has_candidate_write_access(): admin OR study-linked-and-
-- authorized only, with NO orphan branch. The candidate policies are then split so SELECT
-- keeps orphan visibility (the registration validity check), INSERT stays permissive
-- (creation precedes the study link, gated by application-layer authorization), and
-- UPDATE/DELETE require the strict write-access function — an orphan candidate can be read
-- for validity but cannot be mutated or removed by a principal that does not own its study.
--
-- Idempotent: CREATE OR REPLACE FUNCTION + DROP POLICY IF EXISTS / CREATE POLICY. Re-runnable.
-- =============================================================================

CREATE OR REPLACE FUNCTION chronicle_has_candidate_write_access(check_candidate_id UUID)
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

    -- No orphan branch: an unlinked candidate is NOT writable by a non-admin. Writability
    -- requires the candidate to be linked to a study the caller is authorized for.
    RETURN EXISTS (
        SELECT 1 FROM study_participants sp
        WHERE sp.candidate_id = check_candidate_id
          AND sp.study_id = ANY(authorized_studies)
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION chronicle_has_candidate_write_access(UUID) TO PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'candidates') THEN
        DROP POLICY IF EXISTS study_isolation_candidates ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_select ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_insert ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_update ON candidates;
        DROP POLICY IF EXISTS study_isolation_candidates_delete ON candidates;

        -- SELECT keeps orphan visibility so registration can validate a standalone candidate id.
        CREATE POLICY study_isolation_candidates_select ON candidates
            FOR SELECT USING (chronicle_has_candidate_access(candidate_id));
        -- INSERT stays permissive: creation precedes the study_participants link (see V28).
        CREATE POLICY study_isolation_candidates_insert ON candidates
            FOR INSERT WITH CHECK (true);
        -- UPDATE/DELETE require study-linked write access — orphans are not mutable/removable.
        CREATE POLICY study_isolation_candidates_update ON candidates
            FOR UPDATE USING (chronicle_has_candidate_write_access(candidate_id))
            WITH CHECK (chronicle_has_candidate_write_access(candidate_id));
        CREATE POLICY study_isolation_candidates_delete ON candidates
            FOR DELETE USING (chronicle_has_candidate_write_access(candidate_id));
    END IF;
END $$;
