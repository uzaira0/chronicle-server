-- V18: Mobile API key support
-- Per-device API keys are bound to (study_id, participant_id, device_id).
-- Existing admin keys leave participant_id and device_id NULL.

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS participant_id TEXT;
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS device_id UUID;

-- Single covering partial index serves both:
--   1. REVOKE_PRIOR_MOBILE_KEYS_SQL (study_id=? AND participant_id=? AND device_id=?
--      AND revoked=false), used at re-enrollment to atomically retire prior keys.
--   2. The unique constraint that prevents two concurrent re-enrollments from each
--      seeing "no live keys", REVOKE 0, then INSERT — leaving multiple live keys
--      for the same (study, participant, device). On unique-violation the second
--      INSERT fails and that transaction rolls back, so at most one live key
--      survives per device.
CREATE UNIQUE INDEX IF NOT EXISTS idx_api_keys_active_mobile
    ON api_keys (study_id, participant_id, device_id)
    WHERE participant_id IS NOT NULL AND revoked = false;

-- Constraint: mobile keys must have all three fields (study_id is already NOT NULL).
ALTER TABLE api_keys ADD CONSTRAINT api_keys_mobile_fields_consistent
    CHECK ((participant_id IS NULL) = (device_id IS NULL));
