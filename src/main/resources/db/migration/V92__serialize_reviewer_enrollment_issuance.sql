-- Repair any pre-invariant duplicates before enforcing at most one live Play-reviewer invitation
-- per configured study/participant. The service also takes a transaction-scoped advisory lock so
-- rotation is deterministic rather than relying on unique-violation retries.
WITH ranked AS (
    SELECT access_code_id,
           row_number() OVER (
               PARTITION BY study_id, participant_id, form_kind, issuer_type, issued_by
               ORDER BY created_at DESC, access_code_id DESC
           ) AS live_rank
    FROM participant_form_access_codes
    WHERE form_kind = 'ENROLLMENT'
      AND issuer_type = 'RESEARCHER'
      AND issued_by = 'play-reviewer-bootstrap'
      AND exchanged_at IS NULL
      AND revoked_at IS NULL
)
UPDATE participant_form_access_codes AS codes
SET revoked_at = clock_timestamp()
FROM ranked
WHERE ranked.access_code_id = codes.access_code_id
  AND ranked.live_rank > 1
  AND codes.exchanged_at IS NULL
  AND codes.revoked_at IS NULL;

CREATE UNIQUE INDEX participant_form_access_codes_one_live_reviewer_enrollment
    ON participant_form_access_codes (study_id, participant_id, form_kind, issuer_type, issued_by)
    WHERE form_kind = 'ENROLLMENT'
      AND issuer_type = 'RESEARCHER'
      AND issued_by = 'play-reviewer-bootstrap'
      AND exchanged_at IS NULL
      AND revoked_at IS NULL;
