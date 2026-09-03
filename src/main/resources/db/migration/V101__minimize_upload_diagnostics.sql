-- Diagnostic destination is inherent in the authenticated request, and unrestricted exception
-- messages cannot be proven free of participant identifiers or credentials. Retain only the
-- closed issue code, bounded exception class, HTTP status, counts, and timestamps.
ALTER TABLE upload_diagnostics
    DROP COLUMN IF EXISTS server_origin,
    DROP COLUMN IF EXISTS error_message;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V101__minimize_upload_diagnostics', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE
            SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
