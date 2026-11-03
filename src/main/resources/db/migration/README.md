# Fresh schema and future migrations

`V1__fresh_schema.sql` replaces V1–V135 and their data backfills. It supports
fresh installations only.

Startup first runs the role bootstrap and rhizome's registered table definitions.
Flyway records that framework bootstrap at version 0, then applies V1. V1 completes
the framework tables and creates service tables, functions, views, constraints,
indexes, triggers, row-security policies, grants, and the canary sentinel study.
Role provisioning stays in the deployment bootstrap; the migration runner supplies
the `chronicle` NOLOGIN placeholder for development and tests.

V1 was derived from schema-only dumps of the framework bootstrap and the complete
V1–V135 chain on the production Percona image. Framework objects already present
are completed rather than recreated. Historical ledger rows and data repairs are
omitted. Fresh deletion schedules require an operation link and previous status.
Storage access methods and tablespaces follow the deployment defaults.

Keep Flyway's versioned migration mechanism. Add future schema changes as V2 and
higher; do not fold them into V1 after the reset release is published. Migration
errors and checksum failures abort startup. Migration connections clear the
server's statement, lock, and idle-transaction timeouts.

Run `scripts/local-ci.sh migration-safety selfhost-restore contract-drift engineering`
from the enclosing checkout. Destructive SQL requires a leading
`-- chronicle:destructive-approved <reason>` comment describing the decision.
