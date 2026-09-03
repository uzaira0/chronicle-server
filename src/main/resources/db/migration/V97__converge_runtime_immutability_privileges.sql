-- Persistent deployments may predate the last-word bootstrap revokes. A later role-bootstrap
-- rerun could also have materialized broad default privileges on the framework audit tables.
-- Converge existing databases without changing the intended append/read contract.
DO $$
DECLARE
    runtime_role TEXT;
BEGIN
    FOREACH runtime_role IN ARRAY ARRAY['chronicle_app', 'chronicle_admin'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
            EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', runtime_role);
            EXECUTE format(
                'REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.audit, public.audit_buffer FROM %I',
                runtime_role
            );
        END IF;
    END LOOP;
END $$;
