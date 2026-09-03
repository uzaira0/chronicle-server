-- =============================================================================
-- Audit Log Immutability: Revoke DELETE from Application Roles
-- =============================================================================
-- Problem: chronicle_admin has BYPASSRLS and can DELETE from audit_logs.
-- The RLS policy no_delete_audit_logs only prevents deletes when app.is_admin
-- is not set, but chronicle_admin bypasses RLS entirely.
--
-- Fix: Revoke DELETE privilege at the role level, which is enforced regardless
-- of BYPASSRLS. Only the postgres superuser (used for maintenance/retention
-- cleanup) can delete audit records.
--
-- This enforces append-only semantics for HIPAA compliance.
-- =============================================================================

-- Revoke DELETE on audit_logs from all application roles and the JDBC connection user.
-- The actual JDBC connection user is 'chronicle' (see rhizome-docker.yaml).
-- chronicle_app and chronicle_admin are revoked as defense-in-depth.
REVOKE DELETE ON audit_logs FROM chronicle;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE DELETE ON audit_logs FROM chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        REVOKE DELETE ON audit_logs FROM chronicle_admin;
    END IF;
END $$;

-- Ensure INSERT is still granted (audit writes must work)
GRANT INSERT ON audit_logs TO chronicle;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT INSERT ON audit_logs TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        GRANT INSERT ON audit_logs TO chronicle_admin;
    END IF;
END $$;

-- Ensure SELECT is granted for reads
GRANT SELECT ON audit_logs TO chronicle;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        GRANT SELECT ON audit_logs TO chronicle_admin;
    END IF;
END $$;

-- Update the table comment to document the immutability constraint
COMMENT ON TABLE audit_logs IS 'Append-only audit log. DELETE revoked from all application roles for HIPAA compliance. Only superuser can purge records.';

-- =============================================================================
-- Record migration completion
-- =============================================================================
INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V15__audit_log_immutability', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
