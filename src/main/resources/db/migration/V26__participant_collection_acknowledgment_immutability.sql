-- =============================================================================
-- Participant Collection-Acknowledgment Immutability: Append-Only Enforcement
-- =============================================================================
-- The participant_collection_acknowledgment table is the tamper-evident trail of
-- every participant's on-device acknowledgment that they were made aware of, and
-- accepted, a newly-enabled collection module (collection loop closure design §5.3).
-- It is the per-participant, per-module consent record researchers rely on for
-- IRB/consent audit, so it MUST be append-only: a participant's acknowledgment (or
-- its absence) must not be deletable or rewritable after the fact.
--
-- Enforcement mechanism (mirrors V25__study_settings_audit_immutability for
-- study_settings_audit, and V15__audit_log_immutability for audit_logs):
--   Revoke DELETE and UPDATE at the *role* level. Unlike RLS policies, role-level
--   privilege revocation is enforced even for roles with BYPASSRLS
--   (chronicle_admin), so it is the only mechanism that genuinely guarantees
--   append-only semantics. Only the postgres superuser (used for maintenance /
--   retention cleanup) can ever delete or amend an acknowledgment record.
--
-- The read path (ParticipantCollectionAcknowledgmentService.getAcknowledgments)
-- runs on a platform-storage connection whose RLS session context is *cleared*
-- (app.authorized_studies = '', app.is_admin = 'false'), so — exactly as for the
-- settings audit — a FORCE-RLS study-scoped SELECT policy would return zero rows
-- and silently break the read. Read authorization is enforced one layer up
-- (StudyController.getStudyCollectionAcknowledgments -> ensureReadAccess) and the
-- query is filtered by study_id, so immutability — not row visibility — is the gap
-- this migration closes.
--
-- The table itself is created at runtime by PostgresTableManager
-- (ChroniclePostgresTables.PARTICIPANT_COLLECTION_ACKNOWLEDGMENT); the owning
-- Upgrade defers this migration via requiredTableName until that table exists, so
-- no CREATE TABLE is needed here.
-- =============================================================================

-- Revoke DELETE and UPDATE from the JDBC connection user and the RLS roles.
-- Every revoke is guarded by a role-existence check so the migration applies
-- cleanly regardless of which roles a given environment has provisioned.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        REVOKE DELETE, UPDATE ON participant_collection_acknowledgment FROM chronicle;
        GRANT INSERT, SELECT ON participant_collection_acknowledgment TO chronicle;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE DELETE, UPDATE ON participant_collection_acknowledgment FROM chronicle_app;
        GRANT INSERT, SELECT ON participant_collection_acknowledgment TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        REVOKE DELETE, UPDATE ON participant_collection_acknowledgment FROM chronicle_admin;
        GRANT INSERT, SELECT ON participant_collection_acknowledgment TO chronicle_admin;
    END IF;
END $$;

-- Document the immutability constraint on the table.
COMMENT ON TABLE participant_collection_acknowledgment IS 'Append-only participant collection-module acknowledgment trail. DELETE and UPDATE revoked from all application roles for HIPAA-grade tamper evidence. Only the postgres superuser can purge records.';

-- =============================================================================
-- Record migration completion
-- =============================================================================
INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V26__participant_collection_acknowledgment_immutability', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
