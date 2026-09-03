-- Keep the lifecycle restoration state and erasure operation tied to the
-- schedule row for the full quarantine. New schedules populate both columns in
-- the same transaction that creates the deletion operation. Nullable columns
-- allow upgrades from pre-ledger schedules, which the scheduler reconciles.

ALTER TABLE study_deletion_schedule
    ADD COLUMN IF NOT EXISTS operation_id UUID,
    ADD COLUMN IF NOT EXISTS previous_status TEXT;

-- Every schedule created by the lifecycle service has a matching transition
-- event. Deterministic ordering is only a compatibility fallback for old rows;
-- new rows persist the exact prior state directly.
UPDATE study_deletion_schedule AS schedule
SET previous_status = (
    SELECT event.previous_status
    FROM study_lifecycle_events AS event
    WHERE event.study_id = schedule.study_id
      AND event.new_status = 'SCHEDULED_FOR_DELETION'
    ORDER BY event.created_at DESC, event.event_id::text DESC
    LIMIT 1
)
WHERE schedule.previous_status IS NULL;

-- V50 and later created the erasure operation before inserting the schedule.
-- Leave truly pre-ledger schedules unlinked so the bounded scheduler can adopt
-- them atomically after the migration.
UPDATE study_deletion_schedule AS schedule
SET operation_id = (
    SELECT operation.operation_id
    FROM data_deletion_operations AS operation
    WHERE operation.study_id = schedule.study_id
      AND operation.mode = 'STUDY_ERASURE'
      AND operation.status IN (
          'PREVIEW', 'QUARANTINED', 'HELD', 'READY',
          'ERASING', 'VERIFYING', 'FAILED'
      )
    ORDER BY operation.created_at DESC, operation.operation_id::text DESC
    LIMIT 1
)
WHERE schedule.operation_id IS NULL;

ALTER TABLE study_deletion_schedule
    ADD CONSTRAINT study_deletion_schedule_previous_status_check
        CHECK (previous_status IS NULL OR previous_status IN ('ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT study_deletion_schedule_operation_fk
        FOREIGN KEY (operation_id, study_id)
        REFERENCES data_deletion_operations(operation_id, study_id);

CREATE UNIQUE INDEX study_deletion_schedule_operation_idx
    ON study_deletion_schedule (operation_id)
    WHERE operation_id IS NOT NULL;

COMMENT ON COLUMN study_deletion_schedule.operation_id IS
    'Durable study-erasure operation linked atomically when the schedule is created or adopted';
COMMENT ON COLUMN study_deletion_schedule.previous_status IS
    'Lifecycle status restored when a not-yet-started study erasure is cancelled';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON study_deletion_schedule TO chronicle_app;
    END IF;
END $$;
