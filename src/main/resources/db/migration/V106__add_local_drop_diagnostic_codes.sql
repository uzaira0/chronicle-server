-- Android now reports data it discarded locally through the redacted upload-diagnostics path:
-- sensor samples expired by age (7-day TTL) or dropped at the row cap, and usage-queue rows
-- evicted while device storage is low. Counts only; still no payload, message, or stack text.
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
        'USAGE_QUEUE_EVICTED'
    ));
