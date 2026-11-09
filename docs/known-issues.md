# Known issues

Found in a code review on 2026-10-24. GitHub issues are disabled on uzaira0/chronicle-server, so these are tracked here.

## Tests overwrite Principals' static state through reflection

### What is wrong

Tests replace the private static state of `Principals` through reflection because the class has no injection point. Tests that do this share one JVM-wide object, so a test that forgets to restore, or runs in parallel, changes what other tests see.

`Principals` keeps its dependencies in private `lateinit` companion fields set once by `init(...)`:
[`Principals.kt:51-58`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/authorization/principals/Principals.kt#L51-L58) (`securablePrincipals`, `principalManager`, `principalResolver`, `principals`).

Tests overwrite them directly:

[`ChronicleServerTests.kt:267-275`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/test/kotlin/com/openlattice/chronicle/ChronicleServerTests.kt#L267-L275)

```kotlin
private fun restorePrincipalMaps() {
    val securableField = Principals::class.java.getDeclaredField("securablePrincipals")
    securableField.isAccessible = true
    securableField.set(null, HazelcastMap.SECURABLE_PRINCIPALS.getMap(hazelcastInstance))
    val principalsField = Principals::class.java.getDeclaredField("principals")
    principalsField.isAccessible = true
    principalsField.set(null, HazelcastMap.RESOLVED_PRINCIPAL_TREES.getMap(hazelcastInstance))
}
```

and `seedResolvedPrincipalTrees()` at [L278-L285](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/test/kotlin/com/openlattice/chronicle/ChronicleServerTests.kt#L278-L285) writes test users into the shared `principals` map.

[`RootPrincipalTest.kt:54-64`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/test/kotlin/com/openlattice/chronicle/authorization/principals/RootPrincipalTest.kt#L54-L64) swaps all four fields for mocks inside `try` and restores them in `finally` (L81-L83).

There are 56 `getDeclaredField` / `isAccessible = true` uses under `src/test`.

### Failure scenario

`RootPrincipalTest` sets `Principals.principals` and `securablePrincipals` to Mockito mocks for the duration of the test. Test classes run serially today (no `maxParallelForks` or JUnit parallel configuration in `build.gradle`), so this holds only while that stays true. As soon as tests run concurrently in one JVM, any test that calls `Principals.getCurrentPrincipals()` while the mocks are in place reads the mock and gets empty or stubbed principals, so an authorization test fails or passes against the wrong data, depending on timing. The same applies to `ChronicleServerTests` seeding `test_user1..3` into the shared map: other tests see those principals.

Separately, renaming any of these private fields breaks the tests only at runtime (`NoSuchFieldException`), not at compile time.

### Suggested fix

Give `Principals` an explicit seam instead of reflection: for example an `internal fun resetForTests(...)`/`withOverrides(...)` that sets and restores all four fields in one call, or (better) turn `Principals` into an injected Spring bean and pass it to callers. Mark tests that mutate `Principals` as non-parallel (`@Execution(SAME_THREAD)` / `@ResourceLock("Principals")`) until the static state is gone. Then remove the reflective field access from tests.

## Changes from the 24 Oct skills alignment pass

The alignment pass between the engineering skills and the product repos produced four changes that apply to this repo (items 2, 7, 10 and 11 of its ranked list). All were re-checked at `origin/develop` `c974975d`.

### Test-time injection instead of reflection on static state (item 7)

This is the pattern-level form of the issue above. **Make the principal store a constructor dependency** (a Spring bean holding `securablePrincipals`, `principalManager`, `principalResolver` and `principals`, passed to callers) so tests construct it with fakes instead of overwriting [`Principals`' private companion fields](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/authorization/principals/Principals.kt#L51-L58). At `c974975d`, `git grep -o 'getDeclaredField\|isAccessible = true' -- src/test` finds 51 occurrences on 49 lines (61 including `setAccessible` and `getDeclaredMethod`); the source even carries a suppression whose reason is that the field name "is a reflection contract — `TestSecurityUtils.injectStaticMock` looks up the static field by the literal name". Once the dependency is injected, add a detekt or ast-grep rule that forbids `getDeclaredField(` in `src/test` outside an allowlist, so the count only goes down.

**Why.** `software-design-checklist` L4 (a Kotlin `object`/companion with mutable or lazily initialised state, or one that is a reflection target, fails; the stateless objects `DeletionStudyFence`, `DeletionTableLockOrder`, `ParticipantCollectionGate` and `ParticipantErasureFence` were checked and pass) and T2 (tests use seams, not reflection).

**Test.** `RootPrincipalTest` and `ChronicleServerTests` rewritten to pass fakes through the constructor, run with JUnit parallel execution enabled for those classes, with no `getDeclaredField` left in either.

### Audit tables: call them append-only, or add an anchored hash chain (item 10)

[`V25__study_settings_audit_immutability.sql`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/resources/db/migration/V25__study_settings_audit_immutability.sql#L4) and [`V26__participant_collection_acknowledgment_immutability.sql`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/resources/db/migration/V26__participant_collection_acknowledgment_immutability.sql#L4) describe each table as "the tamper-evident trail" and set table comments claiming "HIPAA-grade tamper evidence" (L54 in both). [`V44__audit_trail_rls_immutability.sql`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/resources/db/migration/V44__audit_trail_rls_immutability.sql#L6-L26) replaces the REVOKE with RLS policies so "the application role cannot tamper". These are access controls: they make the tables **append-only for the application role** (tamper-resistant). They do not make tampering **evident**: the postgres superuser, a restored backup, or anyone who disables RLS can change or delete rows and nothing records that it happened.

**What to change.** Either (a) reword V25/V26's comments and the table `COMMENT ON` text to "append-only for application roles" (a new migration that re-issues `COMMENT ON TABLE`; do not edit applied migrations), or (b) if tamper evidence is the requirement, add a hash chain per table (`previous_hash`, `row_hash`) and record the chain head outside the database (signed periodic checkpoint to object storage, and in every export), with a verifier that checks the stored head.

**Why.** `data-structure-algorithm-selection` DSA-DATA-039 (a hash chain needs an external anchor; Schneier–Kelsey, Crosby–Wallach, RFC 9162). sleep-scoring-web has the chain but not the anchor: uzaira0/sleep-scoring-web https://github.com/uzaira0/sleep-scoring-web/issues/300.

**Test.** For (b): insert rows, checkpoint, then as superuser delete the last row; the verifier must report it.

### One owner for the usage buffer drain (item 11)

Two code paths claim Android usage rows from the upload buffer and write them to event storage: the scheduled [`MoveToEventStorageTask.moveToEventStorage`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/storage/tasks/MoveToEventStorageTask.kt#L55) and [`AppDataUploadService.moveToEventStorage` / `moveToEventStorageBatch`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/services/upload/AppDataUploadService.kt#L323-L364) (used by the admin drain at [`AdminController.kt:65`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/controllers/AdminController.kt#L65) and the scoped per-participant drain). Both build their claim SQL from [`ChroniclePostgresTables.buildMoveSql`, L1195](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/storage/ChroniclePostgresTables.kt#L1195), but each has its own writer, and the platform claim/delete and the event insert run on different connections; the service commits the platform connection only after the event writer returns. A crash between the event write and the claim commit relies on deduplication, and the usage DTO carries no event UUID, so byte-identical replays with identical transport stamps are not removed.

**What to change.** Make the scheduled task, the admin drain and the scoped drain call one application-level owner. When buffer and event storage are the same database, pass one connection through claim and insert so they commit together; otherwise carry a stable client event or batch identity (and source device) into the final table and enforce uniqueness on it.

**Why.** `software-design-checklist` O4 (one owner per state transition) and L5. Architecture review finding C4, still present at `c974975d`.

**Test.** A test that runs the scheduled task and the admin drain concurrently over one buffer and asserts every row lands exactly once; and a kill-point test (exception injected after the event insert, before the claim commit) followed by a second drain, asserting no duplicate rows.

### One timestamp encoding across Android, iOS and the server (item 2)

The server stores `timestamptz`, which sorts as time. Its part is to parse every device encoding to the same instant and to share the test vectors with the apps: Android sends `OffsetDateTime.toString()` text (zero seconds and milliseconds omitted, sometimes a local offset), iOS sends whole-second `.iso8601` next to millisecond fields. Add a test that feeds the shared vectors (exact minute `2026-10-24T12:00:00.000Z` = 1792843200000, exact second, sub-second `…12:00:05.100Z` = 1792843205100, negative offset `2026-10-24T07:00:05.100-05:00` = 1792843205100, DST fall-back `2026-11-01T01:30:00-04:00` = 1793511000000 and `…-05:00` = 1793514600000), in each device's format, through the upload deserializers (for example [`InteractionEventsUploadService`](https://github.com/uzaira0/chronicle-server/blob/c974975dad854861af8477ff1c14afa5b12aca27/src/main/kotlin/com/openlattice/chronicle/services/upload/InteractionEventsUploadService.kt#L85-L103)) and asserts the stored `timestamptz` values.

**Why.** `software-design-checklist` Y9, Y10; DSA-DATA-037. Android: the uzaira0/chronicle known-issues file on branch `docs/known-issues-20261024` (`KNOWN_ISSUES.md`). iOS: uzaira0/chronicle-ios https://github.com/uzaira0/chronicle-ios/issues/2.
