-- Make the legacy audit and audit_buffer tables append-only for application roles.
-- These tables can be created after the first-entrypoint init scripts run, so
-- init-time trigger creation/revocation alone is insufficient on existing hosts.

CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are immutable. DELETE and UPDATE are not permitted';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    audit_table TEXT;
BEGIN
    FOREACH audit_table IN ARRAY ARRAY['audit', 'audit_buffer'] LOOP
        IF to_regclass('public.' || audit_table) IS NOT NULL THEN
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
        END IF;
    END LOOP;
END $$;

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V49__legacy_audit_immutability', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
