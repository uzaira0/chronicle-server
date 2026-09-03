-- Close the fresh-install privilege gap left by V54/V59. Production role bootstrap runs before
-- Flyway creates these framework-owned tables, so its existence-guarded REVOKE is a no-op while
-- chronicle_admin's default ALL grant later materializes with TRUNCATE. Row triggers and RLS do
-- not intercept TRUNCATE, so the migration itself must be the last word on both audit tables.
--
-- Keep the intended append-only boundary intact: this removes only TRUNCATE. Existing SELECT and
-- INSERT privileges remain unchanged; V54/V59 already removed UPDATE and DELETE.
DO $$
DECLARE
    runtime_role TEXT;
BEGIN
    FOREACH runtime_role IN ARRAY ARRAY['chronicle_app', 'chronicle_admin'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
            EXECUTE format(
                'REVOKE TRUNCATE ON TABLE public.audit, public.audit_buffer FROM %I',
                runtime_role
            );
        END IF;
    END LOOP;
END $$;
