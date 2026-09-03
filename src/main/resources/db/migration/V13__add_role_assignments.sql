CREATE TABLE IF NOT EXISTS role_assignments (
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    scope_id UUID NOT NULL,
    role_name TEXT NOT NULL,
    assigned_by TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (principal_id, scope_type, scope_id)
);

CREATE INDEX IF NOT EXISTS idx_role_assignments_scope ON role_assignments (scope_type, scope_id);
