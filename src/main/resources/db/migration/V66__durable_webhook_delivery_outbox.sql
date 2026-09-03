-- V66: make webhook_deliveries the durable delivery queue and retry ledger.
--
-- Existing delivery history predates durable queueing. Completed responses stay
-- terminal; records that explicitly say the executor never ran are safe to
-- replay. The new binary explicitly inserts PENDING rows. A compatibility
-- trigger classifies rows written by a pre-V66 binary as terminal because
-- that binary only records a delivery after its HTTP attempt has completed.

ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS delivery_state TEXT,
    ADD COLUMN IF NOT EXISTS available_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_token UUID,
    ADD COLUMN IF NOT EXISTS outcome_code TEXT,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE webhook_deliveries
SET delivery_state = CASE
        WHEN status BETWEEN 200 AND 299 THEN 'SUCCEEDED'
        WHEN status = 0 AND response_body IN (
            'queue_rejected',
            'executor_shutdown',
            'shutdown_interrupted',
            'interrupted',
            'worker_failure'
        ) THEN 'PENDING'
        ELSE 'FAILED'
    END,
    available_at = COALESCE(last_attempt_at, created_at, now()),
    outcome_code = response_body,
    completed_at = CASE
        WHEN status BETWEEN 200 AND 299 THEN COALESCE(last_attempt_at, created_at, now())
        WHEN status = 0 AND response_body IN (
            'queue_rejected',
            'executor_shutdown',
            'shutdown_interrupted',
            'interrupted',
            'worker_failure'
        ) THEN NULL
        ELSE COALESCE(last_attempt_at, created_at, now())
    END,
    updated_at = COALESCE(last_attempt_at, created_at, now())
WHERE delivery_state IS NULL;

-- A malformed legacy/manual event type must never become a permanently
-- reclaimable poison row. Preserve the history, but make it terminal.
UPDATE webhook_deliveries
SET delivery_state = 'FAILED',
    outcome_code = 'invalid_event_type',
    completed_at = COALESCE(completed_at, last_attempt_at, created_at, now()),
    lease_token = NULL,
    lease_expires_at = NULL,
    updated_at = now()
WHERE delivery_state IN ('PENDING', 'IN_FLIGHT')
  AND event_type NOT IN (
      'PARTICIPANT_ENROLLED',
      'DATA_SUBMITTED',
      'STUDY_STATUS_CHANGED',
      'EXPORT_COMPLETED'
  );

ALTER TABLE webhook_deliveries
    ALTER COLUMN delivery_state DROP DEFAULT,
    ALTER COLUMN delivery_state SET NOT NULL,
    ALTER COLUMN available_at SET DEFAULT now(),
    ALTER COLUMN available_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL;

CREATE OR REPLACE FUNCTION chronicle_classify_legacy_webhook_delivery()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.delivery_state IS NULL THEN
        NEW.delivery_state := CASE
            WHEN NEW.status BETWEEN 200 AND 299 THEN 'SUCCEEDED'
            ELSE 'FAILED'
        END;
        NEW.available_at := COALESCE(NEW.available_at, NEW.last_attempt_at, NEW.created_at, now());
        NEW.outcome_code := COALESCE(NEW.outcome_code, NEW.response_body, 'legacy_delivery');
        NEW.completed_at := COALESCE(NEW.completed_at, NEW.last_attempt_at, NEW.created_at, now());
        NEW.updated_at := COALESCE(NEW.updated_at, NEW.last_attempt_at, NEW.created_at, now());
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS classify_legacy_webhook_delivery ON webhook_deliveries;
CREATE TRIGGER classify_legacy_webhook_delivery
BEFORE INSERT ON webhook_deliveries
FOR EACH ROW
EXECUTE FUNCTION chronicle_classify_legacy_webhook_delivery();

ALTER TABLE webhook_deliveries
    DROP CONSTRAINT IF EXISTS webhook_deliveries_state_check,
    ADD CONSTRAINT webhook_deliveries_state_check
        CHECK (delivery_state IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED')),
    DROP CONSTRAINT IF EXISTS webhook_deliveries_pending_event_type_check,
    ADD CONSTRAINT webhook_deliveries_pending_event_type_check
        CHECK (
            delivery_state IN ('SUCCEEDED', 'FAILED')
            OR event_type IN (
                'PARTICIPANT_ENROLLED',
                'DATA_SUBMITTED',
                'STUDY_STATUS_CHANGED',
                'EXPORT_COMPLETED'
            )
        ),
    DROP CONSTRAINT IF EXISTS webhook_deliveries_lease_check,
    ADD CONSTRAINT webhook_deliveries_lease_check
        CHECK (
            (
                delivery_state = 'IN_FLIGHT'
                AND lease_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
            )
            OR
            (
                delivery_state <> 'IN_FLIGHT'
                AND lease_token IS NULL
                AND lease_expires_at IS NULL
            )
        ),
    DROP CONSTRAINT IF EXISTS webhook_deliveries_completion_check,
    ADD CONSTRAINT webhook_deliveries_completion_check
        CHECK (
            (
                delivery_state IN ('SUCCEEDED', 'FAILED')
                AND completed_at IS NOT NULL
            )
            OR
            (
                delivery_state IN ('PENDING', 'IN_FLIGHT')
                AND completed_at IS NULL
            )
        );

CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_pending
    ON webhook_deliveries (available_at, created_at)
    WHERE delivery_state = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_expired_lease
    ON webhook_deliveries (lease_expires_at)
    WHERE delivery_state = 'IN_FLIGHT';
