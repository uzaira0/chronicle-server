-- =============================================================================
-- Study Settings Audit Immutability: Append-Only Enforcement
-- =============================================================================
-- The study_settings_audit table is the tamper-evident trail of every change to
-- a study's settings (who changed which setting, when, from where, and the full
-- before/after JSONB snapshot). For HIPAA-grade traceability it MUST be
-- append-only: an attacker (or a buggy code path) must not be able to delete or
-- rewrite history to hide a configuration change.
--
-- Enforcement mechanism (mirrors V15__audit_log_immutability for audit_logs):
--   Revoke DELETE and UPDATE at the *role* level. Unlike RLS policies, role-level
--   privilege revocation is enforced even for roles with BYPASSRLS
--   (chronicle_admin), so it is the only mechanism that genuinely guarantees
--   append-only semantics. Only the postgres superuser (used for maintenance /
--   retention cleanup) can ever delete or amend a settings-audit record.
--
-- Why not FORCE ROW LEVEL SECURITY with a study-scoped SELECT policy?
--   The settings-audit read path (StudySettingsAuditService.getAuditHistory) runs
--   on a platform-storage connection whose RLS session context is *cleared*
--   (app.authorized_studies = '', app.is_admin = 'false' — see
--   RLSConnectionCustomizer.CONNECTION_INIT_SQL). A FORCE-RLS study-scoped SELECT
--   policy would therefore return zero rows and silently break the audit read.
--   Read authorization is already enforced one layer up
--   (StudyController.getStudySettingsAudit -> ensureReadAccess) and the query is
--   filtered by study_id, so immutability — not row visibility — is the gap this
--   migration closes.
--
-- The table itself is created at runtime by PostgresTableManager
-- (ChroniclePostgresTables.STUDY_SETTINGS_AUDIT); the owning Upgrade defers this
-- migration via requiredTableName until that table exists, so no CREATE TABLE is
-- needed here.
-- =============================================================================

-- Revoke DELETE and UPDATE from the JDBC connection user and the RLS roles.
-- Every revoke is guarded by a role-existence check so the migration applies
-- cleanly regardless of which roles a given environment has provisioned.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        REVOKE DELETE, UPDATE ON study_settings_audit FROM chronicle;
        GRANT INSERT, SELECT ON study_settings_audit TO chronicle;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE DELETE, UPDATE ON study_settings_audit FROM chronicle_app;
        GRANT INSERT, SELECT ON study_settings_audit TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        REVOKE DELETE, UPDATE ON study_settings_audit FROM chronicle_admin;
        GRANT INSERT, SELECT ON study_settings_audit TO chronicle_admin;
    END IF;
END $$;

-- Document the immutability constraint on the table.
COMMENT ON TABLE study_settings_audit IS 'Append-only study settings audit trail. DELETE and UPDATE revoked from all application roles for HIPAA-grade tamper evidence. Only the postgres superuser can purge records.';

-- =============================================================================
-- Record migration completion
-- =============================================================================
INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V25__study_settings_audit_immutability', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
