-- =============================================================================
-- Comprehensive Audit Logs Table for HIPAA Compliance
-- =============================================================================
-- This migration creates the audit_logs table for tracking all system access,
-- data modifications, and security events. This is critical for HIPAA compliance
-- as all PHI access must be tracked with who, what, when, and outcome.
--
-- The table is designed to support:
-- - Compliance reporting and audits
-- - Security monitoring and incident investigation
-- - PHI access tracking
-- - User activity analysis
-- - SIEM tool integration (via log file output)
-- =============================================================================

-- Create the audit_logs table
CREATE TABLE IF NOT EXISTS audit_logs (
    -- Unique identifier for each audit event
    id UUID PRIMARY KEY,

    -- When the event occurred (stored in UTC)
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- User context
    user_id UUID,                    -- The authenticated user's ID (null for unauthenticated)
    user_role TEXT,                  -- The role of the user (admin, researcher, etc.)
    ip_address TEXT NOT NULL,        -- IP address of the request originator
    user_agent TEXT,                 -- User-Agent header from the request

    -- Action context
    action TEXT NOT NULL,            -- The type of action (LOGIN, VIEW, CREATE, etc.)
    resource_type TEXT NOT NULL,     -- Type of resource (Study, Participant, etc.)
    resource_id UUID,                -- Specific resource ID being accessed
    study_id UUID,                   -- Study context for the action
    organization_id UUID,            -- Organization context for the action

    -- Outcome
    success BOOLEAN NOT NULL DEFAULT true,
    error_message TEXT,              -- Error details if operation failed

    -- PHI tracking (critical for HIPAA)
    accessed_phi BOOLEAN NOT NULL DEFAULT false,
    phi_fields TEXT[],               -- List of PHI field names accessed

    -- Request details
    request_path TEXT,               -- HTTP request path
    request_method TEXT,             -- HTTP method (GET, POST, etc.)
    response_code INTEGER,           -- HTTP response status code
    duration_ms BIGINT,              -- Operation duration in milliseconds

    -- Additional context
    additional_data JSONB            -- Any additional context-specific data
);

-- =============================================================================
-- Indexes for efficient querying
-- =============================================================================

-- Primary index on timestamp for time-range queries (most common)
CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs(timestamp DESC);

-- Index for user activity queries
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON audit_logs(user_id) WHERE user_id IS NOT NULL;

-- Index for study-specific queries
CREATE INDEX IF NOT EXISTS idx_audit_logs_study_id ON audit_logs(study_id) WHERE study_id IS NOT NULL;

-- Index for PHI access queries (critical for compliance)
CREATE INDEX IF NOT EXISTS idx_audit_logs_phi_access ON audit_logs(timestamp DESC) WHERE accessed_phi = true;

-- Index for failed operations (security monitoring)
CREATE INDEX IF NOT EXISTS idx_audit_logs_failures ON audit_logs(timestamp DESC) WHERE success = false;

-- Index for security events
CREATE INDEX IF NOT EXISTS idx_audit_logs_security ON audit_logs(timestamp DESC)
    WHERE action IN ('LOGIN', 'LOGOUT', 'LOGIN_FAILED', 'UNAUTHORIZED_ACCESS', 'ACCESS_DENIED', 'PERMISSION_CHANGE', 'SUSPICIOUS_ACTIVITY');

-- Compound index for user + timestamp queries
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_time ON audit_logs(user_id, timestamp DESC) WHERE user_id IS NOT NULL;

-- Index for action type queries
CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action, timestamp DESC);

-- Index for resource type queries
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource_type ON audit_logs(resource_type, timestamp DESC);

-- =============================================================================
-- Partitioning consideration
-- =============================================================================
-- For high-volume production systems, consider partitioning by timestamp:
--
-- CREATE TABLE audit_logs_partitioned (
--     LIKE audit_logs INCLUDING ALL
-- ) PARTITION BY RANGE (timestamp);
--
-- CREATE TABLE audit_logs_y2024m01 PARTITION OF audit_logs_partitioned
--     FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
-- ... etc

-- =============================================================================
-- Comments for documentation
-- =============================================================================

COMMENT ON TABLE audit_logs IS 'HIPAA-compliant audit log tracking all system access, data modifications, and security events';

COMMENT ON COLUMN audit_logs.id IS 'Unique identifier for each audit event';
COMMENT ON COLUMN audit_logs.timestamp IS 'When the event occurred (UTC)';
COMMENT ON COLUMN audit_logs.user_id IS 'Authenticated user ID (null for unauthenticated requests)';
COMMENT ON COLUMN audit_logs.user_role IS 'Role of the user performing the action';
COMMENT ON COLUMN audit_logs.ip_address IS 'IP address of the request originator';
COMMENT ON COLUMN audit_logs.user_agent IS 'User-Agent header from the request';
COMMENT ON COLUMN audit_logs.action IS 'Type of action performed (LOGIN, VIEW, CREATE, etc.)';
COMMENT ON COLUMN audit_logs.resource_type IS 'Type of resource being accessed (Study, Participant, etc.)';
COMMENT ON COLUMN audit_logs.resource_id IS 'Specific resource ID being accessed';
COMMENT ON COLUMN audit_logs.study_id IS 'Study context for study-scoped actions';
COMMENT ON COLUMN audit_logs.organization_id IS 'Organization context for org-scoped actions';
COMMENT ON COLUMN audit_logs.success IS 'Whether the operation succeeded';
COMMENT ON COLUMN audit_logs.error_message IS 'Error details if the operation failed';
COMMENT ON COLUMN audit_logs.accessed_phi IS 'Whether Protected Health Information was accessed (HIPAA critical)';
COMMENT ON COLUMN audit_logs.phi_fields IS 'List of PHI field names that were accessed';
COMMENT ON COLUMN audit_logs.request_path IS 'HTTP request path';
COMMENT ON COLUMN audit_logs.request_method IS 'HTTP method (GET, POST, PUT, DELETE, etc.)';
COMMENT ON COLUMN audit_logs.response_code IS 'HTTP response status code';
COMMENT ON COLUMN audit_logs.duration_ms IS 'Operation duration in milliseconds';
COMMENT ON COLUMN audit_logs.additional_data IS 'Additional context-specific data in JSON format';

-- =============================================================================
-- Data retention policy (HIPAA requires minimum 6 years)
-- =============================================================================
-- For production, consider implementing automatic data retention:
--
-- CREATE OR REPLACE FUNCTION audit_logs_cleanup()
-- RETURNS void AS $$
-- BEGIN
--     DELETE FROM audit_logs
--     WHERE timestamp < NOW() - INTERVAL '7 years';
-- END;
-- $$ LANGUAGE plpgsql;
--
-- Schedule this function to run periodically (e.g., monthly)

-- =============================================================================
-- Row-Level Security for audit_logs
-- =============================================================================
-- Audit logs should generally be accessible only to admins
-- Regular users should not be able to see or modify audit logs

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;

-- Only admins can view audit logs
DROP POLICY IF EXISTS admin_view_audit_logs ON audit_logs;
CREATE POLICY admin_view_audit_logs ON audit_logs
    FOR SELECT
    USING (
        COALESCE(
            NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
            false
        ) = true
    );

-- Only the application service account can insert audit logs
-- This is controlled by the connection user (chronicle_app)
DROP POLICY IF EXISTS service_insert_audit_logs ON audit_logs;
CREATE POLICY service_insert_audit_logs ON audit_logs
    FOR INSERT
    WITH CHECK (true);  -- Insert always allowed (audit service needs to write)

-- Prevent updates to audit logs (immutability)
DROP POLICY IF EXISTS no_update_audit_logs ON audit_logs;
CREATE POLICY no_update_audit_logs ON audit_logs
    FOR UPDATE
    USING (false);  -- Never allow updates

-- Prevent deletes to audit logs (except by scheduled cleanup)
DROP POLICY IF EXISTS no_delete_audit_logs ON audit_logs;
CREATE POLICY no_delete_audit_logs ON audit_logs
    FOR DELETE
    USING (
        COALESCE(
            NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
            false
        ) = true
    );  -- Only admins can delete (for cleanup purposes)

-- =============================================================================
-- Record migration completion
-- =============================================================================
INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V2__create_audit_logs_table', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
