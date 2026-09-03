-- Chronicle cannot yet decrypt encrypted_payloads into the participant export contract.
-- Refuse to start with either an enabled study or historical ciphertext rather than silently
-- producing an incomplete export. Disabling a setting does not make already-collected ciphertext
-- exportable, so operators must remediate both conditions explicitly.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM studies
        WHERE settings #> '{Encryption,enabled}' = 'true'::JSONB
    ) THEN
        RAISE EXCEPTION
            'Enabled StudyEncryptionSetting is unsupported until encrypted participant export is implemented; disable it before upgrading';
    END IF;

    IF EXISTS (SELECT 1 FROM encrypted_payloads) THEN
        RAISE EXCEPTION
            'encrypted_payloads contains ciphertext that Chronicle cannot export; decrypt and export or remove it under an approved data-remediation procedure before upgrading';
    END IF;
END $$;

-- The application gate provides an immediate API error. These constraints are the last-word
-- database boundary for direct SQL, background code, or an accidentally re-enabled old endpoint.
ALTER TABLE studies
    ADD CONSTRAINT studies_encryption_export_supported
    CHECK (settings #> '{Encryption,enabled}' IS DISTINCT FROM 'true'::JSONB);

ALTER TABLE encrypted_payloads
    ADD CONSTRAINT encrypted_payloads_export_supported
    CHECK (false);

COMMENT ON TABLE encrypted_payloads IS
    'Reserved encrypted upload store. StudyEncryptionSetting remains fail-closed disabled until OWNER-gated decryption is integrated with participant exports.';

COMMENT ON CONSTRAINT studies_encryption_export_supported ON studies IS
    'Prevents selecting encrypted collection until Chronicle can decrypt it into participant exports.';

COMMENT ON CONSTRAINT encrypted_payloads_export_supported ON encrypted_payloads IS
    'Prevents accepting ciphertext while Chronicle has no complete participant export path for it.';
