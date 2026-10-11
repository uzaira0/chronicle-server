-- chronicle:destructive-approved Extends the receipt contract without removing evidence.
ALTER TABLE restore_continuity_reconciliations
    DROP CONSTRAINT restore_continuity_reconciliations_contract_version_check;
ALTER TABLE restore_continuity_reconciliations
    ADD CONSTRAINT restore_continuity_reconciliations_contract_version_check
        CHECK (contract_version IN (1, 2, 3, 4, 5));

ALTER TABLE study_participants ADD COLUMN capture_cutoff TIMESTAMPTZ;

CREATE FUNCTION chronicle_record_capture_cutoff() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.participation_status = 'ENROLLED' THEN
        NEW.capture_cutoff := NULL;
    ELSIF OLD.participation_status = 'ENROLLED'
          AND NEW.participation_status IN ('PAUSED', 'COLLECTION_COMPLETED') THEN
        NEW.capture_cutoff := now();
    ELSE
        NEW.capture_cutoff := OLD.capture_cutoff;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER record_capture_cutoff BEFORE UPDATE OF participation_status ON study_participants
FOR EACH ROW EXECUTE FUNCTION chronicle_record_capture_cutoff();
