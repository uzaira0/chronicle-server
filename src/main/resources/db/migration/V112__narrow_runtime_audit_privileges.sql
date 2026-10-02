-- The request-path role only appends to the legacy audit store (PostgresAuditingManager:
-- INSERT ... ON CONFLICT DO NOTHING, which needs no SELECT) and never reads audit or
-- audit_buffer. It claims and publishes deletion-audit outbox events with UPDATE only; the
-- one DELETE (restore re-arm) runs at startup as the schema owner. Withdraw what it never uses.
-- chronicle:destructive-approved REVOKE of DELETE/TRUNCATE privileges only; no row or table is removed.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE SELECT ON TABLE public.audit, public.audit_buffer FROM chronicle_app;
        REVOKE DELETE, TRUNCATE ON TABLE public.data_deletion_audit_outbox FROM chronicle_app;
    END IF;
END $$;
