-- A study-erasure quarantine revokes participant form capabilities immediately.
-- Track the pre-quarantine value for every operation/resource claim so cancelling
-- the last active erasure restores only access that the deletion workflow changed.

CREATE TABLE data_deletion_form_access_revocations (
    operation_id UUID NOT NULL,
    study_id UUID NOT NULL,
    resource_kind TEXT NOT NULL
        CHECK (resource_kind IN ('ACCESS_CODE', 'SESSION')),
    resource_id UUID NOT NULL,
    original_revoked_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operation_id, resource_kind, resource_id),
    FOREIGN KEY (operation_id, study_id)
        REFERENCES data_deletion_operations(operation_id, study_id)
        ON DELETE CASCADE
);

CREATE INDEX data_deletion_form_access_revocations_resource_idx
    ON data_deletion_form_access_revocations (resource_kind, resource_id);

CREATE INDEX data_deletion_form_access_revocations_study_idx
    ON data_deletion_form_access_revocations (study_id, operation_id);

ALTER TABLE data_deletion_form_access_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_deletion_form_access_revocations FORCE ROW LEVEL SECURITY;

CREATE POLICY study_isolation_data_deletion_form_access_revocations
    ON data_deletion_form_access_revocations
    FOR ALL
    USING (chronicle_has_study_access(study_id))
    WITH CHECK (chronicle_has_study_access(study_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, DELETE
            ON data_deletion_form_access_revocations
            TO chronicle_app;
    END IF;
END
$$;
