-- V15 skipped roles that did not yet exist; V60 can create chronicle_app later.
-- Repair audit appends without widening read or mutation privileges.
DO $$
DECLARE
    app_role TEXT;
BEGIN
    FOREACH app_role IN ARRAY ARRAY['chronicle_app', 'chronicle_admin'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
            EXECUTE format('GRANT INSERT ON audit_logs TO %I', app_role);
        END IF;
    END LOOP;
END $$;
