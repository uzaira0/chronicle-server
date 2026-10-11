CREATE TABLE jwt_session_state (
    state_group TEXT NOT NULL,
    state_key TEXT NOT NULL,
    state_value BIGINT NOT NULL,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (state_group, state_key)
);
REVOKE ALL ON jwt_session_state FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE ON jwt_session_state TO chronicle_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        GRANT SELECT, INSERT, UPDATE ON jwt_session_state TO chronicle;
    END IF;
END $$;
