-- =============================================================================
-- Add honey token (canary API key) support to api_keys table
-- =============================================================================
-- Honey tokens are fake API keys that trigger security alerts when used.
-- They need a flag column to distinguish them from real keys, and a nullable
-- study_id since they don't belong to any real study.
-- =============================================================================

-- Add is_honey_token flag
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS is_honey_token BOOLEAN NOT NULL DEFAULT false;

-- Create index for fast honey token lookups by prefix pattern
CREATE INDEX IF NOT EXISTS idx_api_keys_honey_token ON api_keys (key_prefix) WHERE is_honey_token = true;

-- Allow study_id to be NULL for honey tokens only.
-- We cannot simply make the column nullable (that would break the FK for real keys).
-- Instead, insert a sentinel study row that honey tokens reference.
INSERT INTO studies (study_id, title, description, study_version, contact, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000000'::uuid,
    '[SYSTEM] Honey Token Sentinel',
    'Sentinel study for honey token API keys. Do not delete.',
    '',
    '',
    now()
)
ON CONFLICT (study_id) DO NOTHING;

-- =============================================================================
-- Record migration completion
-- =============================================================================
INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V16__add_honey_token_support', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
