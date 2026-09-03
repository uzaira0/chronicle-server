-- =============================================================================
-- Per-Module Consent: Decision Trail Columns
-- =============================================================================
-- Generalizes the append-only participant_collection_acknowledgment trail from an
-- accept-only record into a per-module DECISION snapshot
-- (docs/superpowers/specs/2026-06-10-per-module-consent-design.md §3.3, §9):
--
--   declined_modules  — JSON array of the module wire-ids the participant DECLINED in
--                       this snapshot (accept-only rows keep the default empty array).
--   collection_trigger — what produced the decision: ENROLLMENT | PARTICIPANT_TOGGLE |
--                        SETTINGS_CHANGE | WITHDRAWAL (legacy rows default ENROLLMENT).
--
-- Additive and idempotent: ADD COLUMN IF NOT EXISTS with a NOT NULL DEFAULT backfills
-- existing rows. The table itself is code-defined (PostgresTableManager,
-- ChroniclePostgresTables.PARTICIPANT_COLLECTION_ACKNOWLEDGMENT), so fresh deployments
-- already create these columns; this migration covers existing deployments. The V26
-- append-only REVOKEs are unaffected — adding a column is owner-level DDL.
-- =============================================================================

ALTER TABLE participant_collection_acknowledgment
    ADD COLUMN IF NOT EXISTS declined_modules JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE participant_collection_acknowledgment
    ADD COLUMN IF NOT EXISTS collection_trigger TEXT NOT NULL DEFAULT 'ENROLLMENT';

COMMENT ON COLUMN participant_collection_acknowledgment.declined_modules IS 'JSON array of collection module wire-ids the participant declined in this snapshot (per-module consent design §3.3).';
COMMENT ON COLUMN participant_collection_acknowledgment.collection_trigger IS 'What produced this decision: ENROLLMENT | PARTICIPANT_TOGGLE | SETTINGS_CHANGE | WITHDRAWAL.';

-- =============================================================================
-- Record migration completion
-- =============================================================================
INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V27__collection_decision_trail', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
