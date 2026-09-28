-- Retain diagnostics for the life of the study and accept the shared Android catalog.
-- Explicit participant/study erasure remains responsible for deleting these rows.
-- chronicle:destructive-approved CHECK drop+add only widens module_family/issue_code; both new sets are supersets of V104/V106, so every existing row still passes.
ALTER TABLE upload_diagnostics DROP CONSTRAINT IF EXISTS upload_diagnostics_module_family_check;
ALTER TABLE upload_diagnostics ADD CONSTRAINT upload_diagnostics_module_family_check
    CHECK (module_family IN (
        'USAGE_LIFECYCLE', 'BATTERY', 'DEVICE_TELEMETRY', 'SENSOR', 'APP_RUNTIME',
        'INTERACTION', 'AUDIO_ACTIVITY', 'AUDIO_CONTENT', 'NOTIFICATION', 'SLEEP',
        'ACTIVITY_RECOGNITION', 'HEALTH', 'CONNECTIVITY', 'APP_NETWORK', 'DEVICE_SETTINGS',
        'LOCAL_STORE'
    ));

ALTER TABLE upload_diagnostics DROP CONSTRAINT IF EXISTS upload_diagnostics_issue_code_check;
ALTER TABLE upload_diagnostics ADD CONSTRAINT upload_diagnostics_issue_code_check
    CHECK (issue_code IN (
        'DESTINATION_MISSING',
        'DESTINATION_IDENTITY_MISMATCH',
        'DESTINATION_SOURCE_DEVICE_MISSING',
        'DESTINATION_SETUP_INCOMPLETE',
        'DESTINATION_DISABLED',
        'DESTINATION_NONCANONICAL',
        'DESTINATION_CREDENTIAL_INCOMPLETE',
        'HTTP_SERVER_ERROR',
        'HTTP_CLIENT_ERROR',
        'TIMEOUT',
        'DNS_FAILURE',
        'TLS_FAILURE',
        'CONNECTION_FAILURE',
        'UPLOAD_FAILURE',
        'SENSOR_SAMPLE_QUARANTINED',
        'SENSOR_DEAD_LETTER_DROPPED',
        'APP_CRASH',
        'APP_CRASH_NATIVE',
        'APP_ANR',
        'SENSOR_AGE_EXPIRED',
        'SENSOR_CAPACITY_DROPPED',
        'USAGE_QUEUE_EVICTED',
        'SAMPLE_QUARANTINED',
        'LOCAL_BUFFER_OVERFLOW',
        'LOCAL_REQUEUE_OVERFLOW',
        'LOCAL_WRITE_FAILED',
        'LOCAL_SHUTDOWN_DROPPED',
        'COLLECTION_GATE_DROPPED',
        'MODULE_POLICY_ERASED',
        'DISTRIBUTION_POLICY_ERASED',
        'DIRECT_BOOT_CAPACITY_DROPPED',
        'DIRECT_BOOT_CORRUPT_RECORD',
        'COLLECTION_PAUSED_STORAGE'
    ));

CREATE INDEX IF NOT EXISTS idx_upload_diagnostics_study_participant_day
    ON upload_diagnostics (study_id, participant_id, diagnostic_day);


-- Retained diagnostics are participant data: put them behind the same erasure mutation barrier
-- as every other registry table, so an upload racing a purge cannot land after its proof.
DROP TRIGGER IF EXISTS deletion_mutation_guard_insert ON upload_diagnostics;
CREATE TRIGGER deletion_mutation_guard_insert
AFTER INSERT ON upload_diagnostics
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

DROP TRIGGER IF EXISTS deletion_mutation_guard_update ON upload_diagnostics;
CREATE TRIGGER deletion_mutation_guard_update
AFTER UPDATE ON upload_diagnostics
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

DO $$
BEGIN
    IF to_regclass('usage_event_annotations') IS NOT NULL THEN
        DROP TRIGGER IF EXISTS deletion_mutation_guard_insert ON usage_event_annotations;
        CREATE TRIGGER deletion_mutation_guard_insert
        AFTER INSERT ON usage_event_annotations
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

        DROP TRIGGER IF EXISTS deletion_mutation_guard_update ON usage_event_annotations;
        CREATE TRIGGER deletion_mutation_guard_update
        AFTER UPDATE ON usage_event_annotations
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();
    END IF;

    IF to_regclass('participant_pseudonyms') IS NOT NULL THEN
        DROP TRIGGER IF EXISTS deletion_mutation_guard_insert ON participant_pseudonyms;
        CREATE TRIGGER deletion_mutation_guard_insert
        AFTER INSERT ON participant_pseudonyms
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

        DROP TRIGGER IF EXISTS deletion_mutation_guard_update ON participant_pseudonyms;
        CREATE TRIGGER deletion_mutation_guard_update
        AFTER UPDATE ON participant_pseudonyms
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();
    END IF;
END $$;
