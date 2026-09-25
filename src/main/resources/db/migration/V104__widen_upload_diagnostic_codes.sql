-- Android now reports local sensor dead-letter counts and process-exit (crash/ANR) counts through
-- the existing redacted upload-diagnostics path. Widen the closed vocabularies; still no payload,
-- message, or stack text.
ALTER TABLE upload_diagnostics DROP CONSTRAINT IF EXISTS upload_diagnostics_module_family_check;
ALTER TABLE upload_diagnostics ADD CONSTRAINT upload_diagnostics_module_family_check
    CHECK (module_family IN ('USAGE_LIFECYCLE', 'BATTERY', 'DEVICE_TELEMETRY', 'SENSOR', 'APP_RUNTIME'));

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
        'APP_ANR'
    ));
