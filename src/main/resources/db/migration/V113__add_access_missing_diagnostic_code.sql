-- Accept the Android diagnostic for an accepted module that lost the Android access it collects
-- through (accessibility service, notification listener, usage access, Health Connect).
-- chronicle:destructive-approved CHECK drop+add only widens issue_code; the new set is a superset of V107, so every existing row still passes.
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
        'COLLECTION_PAUSED_STORAGE',
        'COLLECTION_ACCESS_MISSING'
    ));
