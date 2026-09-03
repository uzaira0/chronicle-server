-- Public enrollment is two-phase: bind the one-time invitation to one exact request,
-- then make device/key installation safely repeatable when the HTTP response is lost.
-- Only hashes of client-provided identifiers and credentials are retained.

ALTER TABLE participant_form_access_codes
    ADD COLUMN enrollment_attempt_id UUID,
    ADD COLUMN enrollment_source_device_hash TEXT,
    ADD COLUMN enrollment_device_id UUID,
    ADD COLUMN enrollment_manifest_digest TEXT,
    ADD COLUMN enrollment_request_hash TEXT,
    ADD COLUMN enrollment_proposed_key_hash TEXT,
    ADD COLUMN enrollment_replay_expires_at TIMESTAMPTZ;

ALTER TABLE participant_form_access_codes
    ADD CONSTRAINT participant_form_access_codes_enrollment_receipt_complete
    CHECK (
        (
            enrollment_attempt_id IS NULL
            AND enrollment_source_device_hash IS NULL
            AND enrollment_device_id IS NULL
            AND enrollment_manifest_digest IS NULL
            AND enrollment_request_hash IS NULL
            AND enrollment_proposed_key_hash IS NULL
            AND enrollment_replay_expires_at IS NULL
        )
        OR
        (
            form_kind = 'ENROLLMENT'
            AND exchanged_at IS NOT NULL
            AND enrollment_attempt_id IS NOT NULL
            AND enrollment_device_id IS NOT NULL
            AND enrollment_replay_expires_at IS NOT NULL
            AND enrollment_replay_expires_at > exchanged_at
            AND enrollment_source_device_hash ~ '^[0-9a-f]{64}$'
            AND enrollment_manifest_digest ~ '^[0-9a-f]{64}$'
            AND enrollment_request_hash ~ '^[0-9a-f]{64}$'
            AND enrollment_proposed_key_hash ~ '^[0-9a-f]{64}$'
        )
    );

CREATE UNIQUE INDEX participant_form_access_codes_enrollment_attempt_id_key
    ON participant_form_access_codes (enrollment_attempt_id)
    WHERE enrollment_attempt_id IS NOT NULL;
