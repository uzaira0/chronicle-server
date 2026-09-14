# Migration safety

Never edit, rename, or delete a published migration; add a new version instead.
New versions must exceed every version in the last published release.
Keep changes additive within a release: introduce replacements first, migrate
consumers, and defer destructive cleanup to a follow-up release.
Destructive changes require an explicit decision recorded in the SQL file header:

```sql
-- chronicle:destructive-approved <reason>
```

Replace `<reason>` with the concrete rationale for the destructive change.
Place this line among the leading comments, before any SQL. Approval only waives
the destructive-statement check; published files remain immutable.

Run `scripts/check-migrations-safe.sh` from the monorepo root, or run
`bash scripts/local-ci.sh migration-safety`. The gate is included in `fast`.
It checks tracked and untracked SQL in the server working tree against the newest
`published-*` tag by creation date, falling back to the root commit.
Override with `--server-dir <repo>` and `--since <git ref>`.
Failures identify rewritten files, destructive statements with line numbers, or
back-filled versions. Approved destructive matches also print their reason.
