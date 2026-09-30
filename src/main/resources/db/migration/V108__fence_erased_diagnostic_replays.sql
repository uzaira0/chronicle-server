-- Retain only opaque event UUIDs and the existing participant block token, never diagnostic detail.
CREATE TABLE upload_diagnostic_erasures (
    study_id UUID NOT NULL,
    participant_block_token TEXT NOT NULL,
    event_id UUID NOT NULL,
    PRIMARY KEY (study_id, participant_block_token, event_id)
);

ALTER TABLE upload_diagnostic_erasures ENABLE ROW LEVEL SECURITY;
ALTER TABLE upload_diagnostic_erasures FORCE ROW LEVEL SECURITY;
CREATE POLICY study_isolation_upload_diagnostic_erasures ON upload_diagnostic_erasures
    FOR SELECT USING (chronicle_has_study_access(study_id));
CREATE POLICY trigger_only_upload_diagnostic_erasure_insert ON upload_diagnostic_erasures
    FOR INSERT WITH CHECK (pg_catalog.pg_trigger_depth() > 0);
CREATE POLICY deletion_worker_upload_diagnostic_erasure_delete ON upload_diagnostic_erasures
    FOR DELETE USING (public.chronicle_is_deletion_worker());

CREATE FUNCTION chronicle_record_erased_diagnostic_ids()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    INSERT INTO public.upload_diagnostic_erasures (study_id, participant_block_token, event_id)
    SELECT DISTINCT study_id, md5(study_id::text || ':' || participant_id), event_id::uuid
    FROM erased_rows
    ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION chronicle_record_erased_diagnostic_ids() FROM PUBLIC;
CREATE TRIGGER record_erased_diagnostic_ids
AFTER DELETE ON upload_diagnostics
REFERENCING OLD TABLE AS erased_rows
FOR EACH STATEMENT EXECUTE FUNCTION chronicle_record_erased_diagnostic_ids();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        GRANT SELECT ON upload_diagnostic_erasures TO chronicle;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT ON upload_diagnostic_erasures TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        GRANT SELECT ON upload_diagnostic_erasures TO chronicle_admin;
    END IF;
END $$;

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V108__fence_erased_diagnostic_replays', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
