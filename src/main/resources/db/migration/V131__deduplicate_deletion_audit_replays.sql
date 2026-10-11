-- Audit data is serialized JSON in the legacy text column. Only new outbox
-- projections carry this key; unrelated historic rows remain untouched.
CREATE UNIQUE INDEX audit_deletion_event_id_unique
ON audit ((data::jsonb ->> 'deletionAuditEventId'))
WHERE data LIKE '%"deletionAuditEventId":%';
