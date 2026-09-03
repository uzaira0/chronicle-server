-- Bring the ambient-audio table, introduced after the deletion ledger, under
-- the same application-role and mutation-barrier guarantees as the original
-- participant-data registry.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'ambient_audio_events'
    ) THEN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON ambient_audio_events TO chronicle_app;
        END IF;

        DROP TRIGGER IF EXISTS deletion_mutation_guard_insert ON ambient_audio_events;
        CREATE TRIGGER deletion_mutation_guard_insert
        AFTER INSERT ON ambient_audio_events
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

        DROP TRIGGER IF EXISTS deletion_mutation_guard_update ON ambient_audio_events;
        CREATE TRIGGER deletion_mutation_guard_update
        AFTER UPDATE ON ambient_audio_events
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V82__fence_ambient_audio_erasure', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE
            SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
