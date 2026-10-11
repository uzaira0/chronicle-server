SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- webhook_deliveries is bounded by retention pruning, so a brief write block is cheaper than
-- running the migration outside Flyway's transaction to build the index concurrently.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX webhook_deliveries_terminal_completed_at_idx ON webhook_deliveries (completed_at)
    WHERE delivery_state IN ('SUCCEEDED', 'FAILED');
