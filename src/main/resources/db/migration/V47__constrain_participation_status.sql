-- =============================================================================
-- study_participants.participation_status enum contract
-- =============================================================================
-- The application reads this text column through ParticipationStatus.valueOf(...).
-- Direct SQL seed data can bypass the typed API, so enforce the same allowlist at
-- the database boundary and fail closed on invalid participant-status values.
-- =============================================================================

ALTER TABLE IF EXISTS study_participants
    DROP CONSTRAINT IF EXISTS study_participants_participation_status_check;

ALTER TABLE IF EXISTS study_participants
    ADD CONSTRAINT study_participants_participation_status_check
    CHECK (
        participation_status IN (
            'ENROLLED',
            'NOT_ENROLLED',
            'PAUSED',
            'COLLECTION_COMPLETED',
            'UNKNOWN'
        )
    ) NOT VALID;

ALTER TABLE IF EXISTS study_participants
    VALIDATE CONSTRAINT study_participants_participation_status_check;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V47__constrain_participation_status', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
