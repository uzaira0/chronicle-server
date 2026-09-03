-- Keep deletion accountability in the same transaction as the state change.
-- The existing append-only audit table can live behind a separately configured
-- audit datasource, so hold/cancellation requests first persist an immutable
-- local outbox event. A bounded worker projects that event into audit. Replays
-- use the original event timestamp and payload, making Postgres audit's
-- ON CONFLICT path idempotent after an uncertain publisher outcome.

ALTER TABLE data_deletion_operations
    ADD COLUMN cancelled_by TEXT,
    ADD COLUMN cancelled_at TIMESTAMPTZ;

-- The old schema discarded the cancellation actor. Do not misattribute those
-- historical rows to the original deletion requester.
UPDATE data_deletion_operations
SET cancelled_by = 'legacy-unknown',
    cancelled_at = COALESCE(updated_at, created_at, now())
WHERE status = 'CANCELLED';

ALTER TABLE data_deletion_operations
    ADD CONSTRAINT data_deletion_operations_cancellation_actor_check
    CHECK (
        (
            status = 'CANCELLED'
            AND cancelled_by IS NOT NULL
            AND btrim(cancelled_by) <> ''
            AND cancelled_at IS NOT NULL
        )
        OR
        (
            status <> 'CANCELLED'
            AND cancelled_by IS NULL
            AND cancelled_at IS NULL
        )
    );

CREATE TABLE data_deletion_audit_outbox (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    study_id UUID NOT NULL,
    hold_id UUID,
    event_type TEXT NOT NULL
        CHECK (
            event_type IN (
                'CANCEL_DATA_DELETION',
                'PLACE_RETENTION_HOLD',
                'RELEASE_RETENTION_HOLD'
            )
        ),
    actor TEXT NOT NULL CHECK (btrim(actor) <> ''),
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    event_data JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(event_data) = 'object'),
    event_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    publish_attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (publish_attempt_count >= 0),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (operation_id, study_id)
        REFERENCES data_deletion_operations(operation_id, study_id),
    CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR
        (
            lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND published_at IS NULL
        )
    ),
    CHECK (published_at IS NULL OR (lease_token IS NULL AND lease_expires_at IS NULL))
);

CREATE UNIQUE INDEX data_deletion_audit_outbox_operation_event_idx
    ON data_deletion_audit_outbox (
        operation_id,
        event_type,
        COALESCE(hold_id, operation_id)
    );

CREATE INDEX data_deletion_audit_outbox_pending_idx
    ON data_deletion_audit_outbox (available_at, event_timestamp)
    WHERE published_at IS NULL;

ALTER TABLE data_deletion_audit_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_deletion_audit_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY study_isolation_data_deletion_audit_outbox
    ON data_deletion_audit_outbox
    FOR ALL
    USING (chronicle_has_study_access(study_id))
    WITH CHECK (chronicle_has_study_access(study_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT ON data_deletion_audit_outbox TO chronicle_app;
        GRANT UPDATE (
            available_at,
            publish_attempt_count,
            lease_token,
            lease_expires_at,
            published_at,
            last_error_code
        ) ON data_deletion_audit_outbox TO chronicle_app;
    END IF;
END
$$;
