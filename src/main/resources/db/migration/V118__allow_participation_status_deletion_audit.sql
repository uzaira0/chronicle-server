-- chronicle:destructive-approved Replaces only the event-type CHECK with a superset of V75; every existing row remains valid and no data changes.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE data_deletion_audit_outbox
    DROP CONSTRAINT data_deletion_audit_outbox_event_type_check,
    ADD CONSTRAINT data_deletion_audit_outbox_event_type_check
        CHECK (event_type IN (
            'CANCEL_DATA_DELETION',
            'PLACE_RETENTION_HOLD',
            'RELEASE_RETENTION_HOLD',
            'UPDATE_PARTICIPATION_STATUS'
        )) NOT VALID;

ALTER TABLE data_deletion_audit_outbox
    VALIDATE CONSTRAINT data_deletion_audit_outbox_event_type_check;
