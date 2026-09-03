-- =============================================================================
-- Audit-trail immutability via Row-Level Security (HIPAA-2028 W1 hardening)
-- =============================================================================
-- Problem (verified in production 2026-06-20): `study_settings_audit` and
-- `participant_collection_acknowledgment` were immutable in INTENT (V25 / V26
-- `REVOKE DELETE, UPDATE ... FROM chronicle_app`) but MUTABLE in fact — the
-- request-path role `chronicle_app` could UPDATE and DELETE their rows.
--
-- Root cause: a one-time REVOKE cannot win against an idempotent, re-runnable
-- `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO
-- chronicle_app` (docker/init-db-roles.sql). Every role re-init re-grants
-- UPDATE/DELETE on these tables AFTER the migrations revoked them, so the grant
-- is the last writer and the REVOKE-based immutability silently lapses.
--
-- `audit_logs` did NOT have this problem because V2 defended it with RLS
-- *policies* (no_update USING(false); no_delete admin-only) on top of the
-- REVOKE. RLS policies are evaluated regardless of table privileges, so a
-- re-granted UPDATE/DELETE still cannot mutate a row the policy rejects.
--
-- Fix: bring the two REVOKE-only audit trails up to the proven `audit_logs`
-- pattern — enable + FORCE row-level security with explicit per-command
-- policies: study-scoped SELECT (so researchers/admins keep their existing
-- read path via chronicle_has_study_access), INSERT always allowed (audit
-- writes must never fail), and UPDATE/DELETE denied (USING (false)). The
-- postgres superuser bypasses RLS, so legitimate retention purges still work;
-- the application role cannot tamper. This is GRANT-proof.
--
-- Both tables carry a `study_id` column; the SELECT policy reuses the same
-- chronicle_has_study_access(study_id) function every other study-isolated
-- table uses (admin bypass + app.authorized_studies membership).
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'study_settings_audit') THEN
        ALTER TABLE study_settings_audit ENABLE ROW LEVEL SECURITY;
        ALTER TABLE study_settings_audit FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_settings_audit_select ON study_settings_audit;
        CREATE POLICY study_settings_audit_select ON study_settings_audit
            FOR SELECT USING (chronicle_has_study_access(study_id));

        DROP POLICY IF EXISTS study_settings_audit_insert ON study_settings_audit;
        CREATE POLICY study_settings_audit_insert ON study_settings_audit
            FOR INSERT WITH CHECK (true);  -- audit writes must never fail

        DROP POLICY IF EXISTS study_settings_audit_no_update ON study_settings_audit;
        CREATE POLICY study_settings_audit_no_update ON study_settings_audit
            FOR UPDATE USING (false);  -- append-only: never updatable by an app role

        DROP POLICY IF EXISTS study_settings_audit_no_delete ON study_settings_audit;
        CREATE POLICY study_settings_audit_no_delete ON study_settings_audit
            FOR DELETE USING (false);  -- append-only: only the superuser (RLS-bypassing) may purge
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'participant_collection_acknowledgment') THEN
        ALTER TABLE participant_collection_acknowledgment ENABLE ROW LEVEL SECURITY;
        ALTER TABLE participant_collection_acknowledgment FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS participant_collection_acknowledgment_select ON participant_collection_acknowledgment;
        CREATE POLICY participant_collection_acknowledgment_select ON participant_collection_acknowledgment
            FOR SELECT USING (chronicle_has_study_access(study_id));

        DROP POLICY IF EXISTS participant_collection_acknowledgment_insert ON participant_collection_acknowledgment;
        CREATE POLICY participant_collection_acknowledgment_insert ON participant_collection_acknowledgment
            FOR INSERT WITH CHECK (true);  -- consent-trail writes must never fail

        DROP POLICY IF EXISTS participant_collection_acknowledgment_no_update ON participant_collection_acknowledgment;
        CREATE POLICY participant_collection_acknowledgment_no_update ON participant_collection_acknowledgment
            FOR UPDATE USING (false);  -- append-only consent trail

        DROP POLICY IF EXISTS participant_collection_acknowledgment_no_delete ON participant_collection_acknowledgment;
        CREATE POLICY participant_collection_acknowledgment_no_delete ON participant_collection_acknowledgment
            FOR DELETE USING (false);  -- append-only consent trail; only the superuser may purge
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V44__audit_trail_rls_immutability', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
