-- Stamp the authoritative enrollment disclosure and module scope alongside the V89 replay
-- receipt. Existing V89 receipts stay valid for credential recovery, but their null evidence
-- cannot be used to create a new consent acknowledgment.
ALTER TABLE participant_form_access_codes
    ADD COLUMN enrollment_settings_version INTEGER,
    ADD COLUMN enrollment_disclosure_version TEXT,
    ADD COLUMN enrollment_enabled_modules JSONB,
    ADD COLUMN enrollment_required_modules JSONB;

ALTER TABLE participant_form_access_codes
    ADD CONSTRAINT participant_form_access_codes_enrollment_evidence_complete CHECK (
        (
            enrollment_settings_version IS NULL
            AND enrollment_disclosure_version IS NULL
            AND enrollment_enabled_modules IS NULL
            AND enrollment_required_modules IS NULL
        )
        OR
        (
            enrollment_attempt_id IS NOT NULL
            AND enrollment_settings_version IS NOT NULL
            AND enrollment_disclosure_version IS NOT NULL
            AND enrollment_enabled_modules IS NOT NULL
            AND enrollment_required_modules IS NOT NULL
            AND enrollment_settings_version > 0
            AND length(btrim(enrollment_disclosure_version)) > 0
            AND jsonb_typeof(enrollment_enabled_modules) = 'array'
            AND jsonb_typeof(enrollment_required_modules) = 'array'
            AND enrollment_enabled_modules @> enrollment_required_modules
        )
    );

ALTER TABLE participant_collection_acknowledgment
    ADD COLUMN IF NOT EXISTS unavailable_modules JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS evidence_access_code_id UUID,
    ADD COLUMN IF NOT EXISTS evidence_api_key_id UUID;

ALTER TABLE participant_collection_acknowledgment
    ADD CONSTRAINT participant_collection_ack_evidence_pair_check CHECK (
        (evidence_access_code_id IS NULL) = (evidence_api_key_id IS NULL)
    ),
    ADD CONSTRAINT participant_collection_ack_unavailable_modules_array_check CHECK (
        jsonb_typeof(unavailable_modules) = 'array'
    );

COMMENT ON COLUMN participant_collection_acknowledgment.unavailable_modules IS
    'Enabled per-sensor modules physically unavailable on this device; capability evidence, not a decline.';
COMMENT ON COLUMN participant_collection_acknowledgment.evidence_access_code_id IS
    'Copied UUID of the enrollment invitation receipt whose authoritative disclosure and module scope were validated; intentionally has no FK because consent evidence outlives erasable enrollment rows.';
COMMENT ON COLUMN participant_collection_acknowledgment.evidence_api_key_id IS
    'Copied UUID of the exact mobile API key installation tied to the enrollment receipt and reporting device; intentionally has no FK because consent evidence outlives erasable credentials.';

CREATE UNIQUE INDEX participant_collection_ack_one_enrollment_per_receipt
    ON participant_collection_acknowledgment (evidence_access_code_id, evidence_api_key_id)
    WHERE collection_trigger = 'ENROLLMENT'
      AND evidence_access_code_id IS NOT NULL
      AND evidence_api_key_id IS NOT NULL;
