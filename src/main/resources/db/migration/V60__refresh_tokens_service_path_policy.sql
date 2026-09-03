-- V60: refresh_tokens access for the request-path application role.
--
-- V58 (re-issue of V17) enabled+forced RLS on refresh_tokens with zero policies —
-- "service-role-only" — but V17 predates the W1 SET ROLE regime: every AUTHENTICATED
-- request now drops its connection to chronicle_app (NOSUPERUSER, no BYPASSRLS), so a
-- proactive token refresh by a logged-in web session hit deny-all: the SELECT saw no
-- rows (a valid refresh token looked stolen, triggering family revocation) and the
-- rotated-token INSERT violated RLS. Unauthenticated refresh (expired access token)
-- never SET ROLEs and was unaffected.
--
-- The service layer (AuthTokenController/RefreshTokenService) is the security boundary
-- for this table — lookups are by unique token hash, tokens are stored hashed. Scope
-- access to the application role; every other non-BYPASSRLS role stays deny-all under
-- FORCE ROW LEVEL SECURITY.
--
-- The NOLOGIN placeholder mirrors FlywayMigrationService.ENSURE_CHRONICLE_ROLE_SQL:
-- production init scripts own the real chronicle_app login role; fresh test/dev
-- databases get a placeholder so the corpus is self-sufficient.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        CREATE ROLE chronicle_app NOLOGIN;
    END IF;
END $$;

DROP POLICY IF EXISTS refresh_tokens_service_path ON refresh_tokens;
CREATE POLICY refresh_tokens_service_path ON refresh_tokens
    FOR ALL TO chronicle_app
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens TO chronicle_app;
