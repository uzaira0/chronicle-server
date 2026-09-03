-- New audit writes go directly to the final append-only `audit` table. Keep legacy binaries
-- safe during rolling deploys and rollback: their audit_buffer INSERTs are forwarded into the
-- final table, and an ACCESS EXCLUSIVE lock closes the copy/delete race during the handoff.

CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are immutable. DELETE and UPDATE are not permitted';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION forward_legacy_audit_buffer_insert()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.audit (
        acl_key, id, principal_type, principal_id, audit_event_type,
        study_id, organization_id, description, data, event_timestamp
    ) VALUES (
        NEW.acl_key, NEW.id, NEW.principal_type, NEW.principal_id, NEW.audit_event_type,
        NEW.study_id, NEW.organization_id, NEW.description, NEW.data, NEW.event_timestamp
    ) ON CONFLICT DO NOTHING;

    -- Suppress the staging row: the authoritative append-only row is already durable.
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Without this lock, a legacy writer could commit after the drain snapshot but before a
-- separate DELETE snapshot. The lock also makes blocked writers observe the forwarding trigger
-- immediately after this transaction commits.
LOCK TABLE public.audit_buffer IN ACCESS EXCLUSIVE MODE;

DROP TRIGGER IF EXISTS prevent_audit_buffer_modification_trigger ON public.audit_buffer;

DROP TRIGGER IF EXISTS forward_legacy_audit_buffer_insert_trigger ON public.audit_buffer;
CREATE TRIGGER forward_legacy_audit_buffer_insert_trigger
BEFORE INSERT ON public.audit_buffer
FOR EACH ROW EXECUTE FUNCTION forward_legacy_audit_buffer_insert();

WITH claimed AS (
    DELETE FROM public.audit_buffer
    RETURNING
        acl_key, id, principal_type, principal_id, audit_event_type,
        study_id, organization_id, description, data, event_timestamp
)
INSERT INTO public.audit (
    acl_key, id, principal_type, principal_id, audit_event_type,
    study_id, organization_id, description, data, event_timestamp
)
SELECT
    acl_key, id, principal_type, principal_id, audit_event_type,
    study_id, organization_id, description, data, event_timestamp
FROM claimed
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    audit_table TEXT;
BEGIN
    FOREACH audit_table IN ARRAY ARRAY['audit', 'audit_buffer'] LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_trigger
            WHERE tgname = 'prevent_' || audit_table || '_modification_trigger'
              AND tgrelid = to_regclass('public.' || audit_table)
              AND NOT tgisinternal
        ) THEN
            EXECUTE format(
                'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification()',
                'prevent_' || audit_table || '_modification_trigger',
                audit_table
            );
        END IF;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
            EXECUTE format('REVOKE UPDATE, DELETE ON %I FROM chronicle_app', audit_table);
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
            EXECUTE format('REVOKE UPDATE, DELETE ON %I FROM chronicle_admin', audit_table);
        END IF;
    END LOOP;
END $$;

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V54__direct_append_only_audit', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
