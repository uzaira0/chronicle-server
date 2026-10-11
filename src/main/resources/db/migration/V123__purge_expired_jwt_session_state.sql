CREATE INDEX jwt_session_state_expiry_idx ON jwt_session_state (expires_at)
    WHERE expires_at IS NOT NULL;

DO $$
DECLARE
    app_role TEXT;
BEGIN
    FOREACH app_role IN ARRAY ARRAY['chronicle_app', 'chronicle'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
            EXECUTE format('GRANT DELETE ON jwt_session_state TO %I', app_role);
        END IF;
    END LOOP;
END $$;
