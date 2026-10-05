Server session and write preconditions
=====================================

JWT inactivity expires after 15 minutes by default, independently of absolute JWT expiry.
The shared Hazelcast store records authenticated activity by JTI (token hash when no JTI exists).
Set `sessionIdleMinutes` in `chronicle_auth.yaml`, or set `CHRONICLE_SESSION_IDLE_MINUTES`
when that YAML property is omitted. Values must be between 1 and 120 minutes; invalid values
fail startup. Revoked or idle sessions receive HTTP 401, including cookie bootstrap.

Researcher study, questionnaire, async-export and API-key creates accept an optional
`Idempotency-Key` header (1–128 ASCII letters, digits, `_` or `-`). Without it, creates retain
their legacy behavior and require no receipt encryption secret. Malformed keys return 400.
When supplying a key, keep the same key and exact
request body until that logical create is acknowledged. Scope is authenticated principal and
canonical route. A different body with the same key returns 409. Receipts and mutations commit
in one PostgreSQL transaction; retries return the original result with `Idempotency-Replayed: true`.
Credential results are AES-GCM encrypted and bound to the receipt scope. Keyed creates require
`CHRONICLE_INTERNAL_WEB_SECRET` of at least 32 characters. A missing encryption secret returns
503 before creation. After secret rotation, an unreadable completed result returns 409 and
never issues another credential.

Participant notes/tags and questionnaire PATCH requests require `If-Match`. Obtain the loaded
snapshot and its ETag together from GET on the exact resource path
(`/chronicle/v3/study/{studyId}/participant/{id}/annotations` or
`/chronicle/v3/survey/{studyId}/questionnaire/{id}`). HEAD exposes the current revision only;
never combine that newer revision with previously loaded or cached content.
The comparison and update share one row lock and transaction. Missing revisions return 428;
stale revisions return 412. On conflict, retain the user's edits, reload the current resource,
and offer a merge before another save. Keep a loaded revision with the editing snapshot;
fetching a new revision immediately before saving defeats the concurrency protection.

The Kotlin/Retrofit SDK can install `ResearcherCreateIntent(key)` on its OkHttp client for a
logical create. Retain that instance, key and original DTO until acknowledged; retry through
the same client. Persist the key alongside an outstanding request to survive a restart. The
interceptor binds the first URL and serialized body and rejects an edited retry locally.
Use a separate intent/client for a separate create. The SDK does not invent a new key when a
response is lost. Web forms retain their intent until acknowledgment; an explicit shared
`idempotencyKey` may also be supplied by callers of the mutation endpoints.
