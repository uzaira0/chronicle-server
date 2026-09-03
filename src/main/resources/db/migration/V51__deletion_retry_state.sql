ALTER TABLE data_deletion_operations
    ADD COLUMN IF NOT EXISTS operation_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;

DROP INDEX IF EXISTS data_deletion_operations_due_idx;
CREATE INDEX IF NOT EXISTS data_deletion_operations_due_idx
    ON data_deletion_operations (status, next_attempt_at, quarantine_until);

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V51__deletion_retry_state', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
