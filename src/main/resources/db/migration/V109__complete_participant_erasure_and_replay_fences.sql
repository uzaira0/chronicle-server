-- chronicle:destructive-approved Explicit erasure includes device enrollment and indirect delivery payloads.
-- Consent and audit accountability remain immutable and outside this erasure surface.
ALTER TABLE webhook_deliveries ADD COLUMN study_id UUID;
UPDATE webhook_deliveries delivery SET study_id = registration.study_id
FROM webhook_registrations registration WHERE delivery.webhook_id = registration.webhook_id;
ALTER TABLE webhook_deliveries ALTER COLUMN study_id SET NOT NULL;
ALTER TABLE webhook_deliveries ADD COLUMN participant_id TEXT
    GENERATED ALWAYS AS (payload #>> '{data,participantId}') STORED;
CREATE INDEX webhook_deliveries_participant_scope ON webhook_deliveries (study_id, participant_id)
    WHERE participant_id IS NOT NULL AND participant_id <> '';

CREATE FUNCTION chronicle_bind_webhook_delivery_scope() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE scoped_study UUID;
BEGIN
    SELECT study_id INTO STRICT scoped_study FROM public.webhook_registrations
    WHERE webhook_id = NEW.webhook_id;
    IF NEW.study_id IS NOT NULL AND NEW.study_id <> scoped_study THEN
        RAISE EXCEPTION 'Webhook delivery study scope does not match registration' USING ERRCODE = '23514';
    END IF;
    NEW.study_id := scoped_study;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION chronicle_bind_webhook_delivery_scope() FROM PUBLIC;
CREATE TRIGGER bind_webhook_delivery_scope BEFORE INSERT OR UPDATE ON webhook_deliveries
FOR EACH ROW EXECUTE FUNCTION chronicle_bind_webhook_delivery_scope();

CREATE POLICY deletion_quarantine_webhook_deliveries ON webhook_deliveries
AS RESTRICTIVE FOR SELECT USING (chronicle_participant_data_visible(study_id, participant_id));
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON webhook_deliveries
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON webhook_deliveries
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

CREATE POLICY deletion_quarantine_notifications ON notifications
AS RESTRICTIVE FOR SELECT USING (chronicle_participant_data_visible(study_id, participant_id));
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON notifications
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON notifications
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

-- Enrollment metadata survives a collected-data purge, but withdrawal/study erasure hides it immediately.
CREATE POLICY deletion_quarantine_devices ON devices AS RESTRICTIVE FOR SELECT USING (
    public.chronicle_is_deletion_worker() OR NOT EXISTS (
        SELECT 1 FROM public.data_deletion_operations operation
        WHERE operation.study_id = devices.study_id
          AND operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
          AND operation.status NOT IN ('PREVIEW', 'CANCELLED')
          AND (operation.mode = 'STUDY_ERASURE' OR operation.participant_id = devices.participant_id
               OR operation.participant_block_token = md5(devices.study_id::text || ':' || devices.participant_id))
    )
);
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON devices
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON devices
REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation();

-- One watermark per participant, advanced atomically by the READY -> ERASING owner.
-- No record identities, row triggers, or sensor-table scan are needed for replay admission.
CREATE TABLE participant_purge_cutoffs (
    study_id UUID NOT NULL,
    participant_block_token TEXT NOT NULL,
    cutoff TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (study_id, participant_block_token)
);
ALTER TABLE participant_purge_cutoffs ENABLE ROW LEVEL SECURITY;
ALTER TABLE participant_purge_cutoffs FORCE ROW LEVEL SECURITY;
CREATE POLICY study_isolation_participant_purge_cutoffs ON participant_purge_cutoffs
FOR SELECT USING (chronicle_has_study_access(study_id));
CREATE POLICY deletion_worker_purge_cutoff_write ON participant_purge_cutoffs
FOR ALL USING (public.chronicle_is_deletion_worker())
WITH CHECK (public.chronicle_is_deletion_worker());

-- Supplemental work is scoped by a retained operation token, with at most 500 rows per
-- mutation. These small webhook tables and transactional DDL locks are accepted for V109.
-- Unscoped notification delivery logs are researcher history and remain intact.
DO $$
DECLARE previous_admin TEXT := current_setting('app.is_admin', true);
DECLARE previous_user TEXT := current_setting('app.current_user_id', true);
DECLARE operation RECORD;
DECLARE affected INTEGER;
BEGIN
    PERFORM set_config('app.is_admin', 'true', true);
    PERFORM set_config('app.current_user_id', 'chronicle-deletion-worker', true);

    -- Completed purge operations have already minimized participant_id, so use their token
    -- and original start time. A later terminal withdrawal/study erasure must not regain fences.
    INSERT INTO participant_purge_cutoffs (study_id, participant_block_token, cutoff)
    SELECT purge.study_id, purge.participant_block_token, MAX(purge.started_at)
    FROM data_deletion_operations purge
    WHERE purge.status = 'COMPLETED' AND purge.mode = 'COLLECTED_DATA_PURGE'
      AND purge.started_at IS NOT NULL AND purge.participant_block_token IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM data_deletion_operations erased
          WHERE erased.study_id = purge.study_id AND erased.status = 'COMPLETED'
            AND erased.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
            AND erased.completed_at >= purge.started_at
            AND (erased.mode = 'STUDY_ERASURE' OR erased.participant_block_token = purge.participant_block_token)
      )
    GROUP BY purge.study_id, purge.participant_block_token
    ON CONFLICT (study_id, participant_block_token) DO UPDATE
    SET cutoff = GREATEST(participant_purge_cutoffs.cutoff, EXCLUDED.cutoff);

    FOR operation IN SELECT operation_id, study_id, mode, participant_block_token
        FROM data_deletion_operations WHERE status = 'COMPLETED'
    LOOP
        IF operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE') THEN
            LOOP
                DELETE FROM devices WHERE ctid IN (
                    SELECT ctid FROM devices WHERE study_id = operation.study_id
                      AND (operation.mode = 'STUDY_ERASURE'
                           OR operation.participant_block_token = md5(study_id::text || ':' || participant_id))
                    LIMIT 500
                );
                GET DIAGNOSTICS affected = ROW_COUNT;
                EXIT WHEN affected = 0;
            END LOOP;
            LOOP
                DELETE FROM webhook_deliveries WHERE delivery_id IN (
                    SELECT delivery_id FROM webhook_deliveries WHERE study_id = operation.study_id
                      AND (operation.mode = 'STUDY_ERASURE'
                           OR operation.participant_block_token = md5(study_id::text || ':' || participant_id))
                    LIMIT 500
                );
                GET DIAGNOSTICS affected = ROW_COUNT;
                EXIT WHEN affected = 0;
            END LOOP;
            LOOP
                DELETE FROM notifications WHERE notification_id IN (
                    SELECT notification_id FROM notifications WHERE study_id = operation.study_id
                      AND participant_id IS NOT NULL AND participant_id <> ''
                      AND (operation.mode = 'STUDY_ERASURE'
                           OR operation.participant_block_token = md5(study_id::text || ':' || participant_id))
                    LIMIT 500
                );
                GET DIAGNOSTICS affected = ROW_COUNT;
                EXIT WHEN affected = 0;
            END LOOP;
            IF operation.mode = 'STUDY_ERASURE' THEN
                LOOP
                    DELETE FROM webhook_registrations WHERE webhook_id IN (
                        SELECT webhook_id FROM webhook_registrations WHERE study_id = operation.study_id LIMIT 500
                    );
                    GET DIAGNOSTICS affected = ROW_COUNT;
                    EXIT WHEN affected = 0;
                END LOOP;
            END IF;
        END IF;

        LOOP
            UPDATE export_jobs SET request = '{}'::jsonb
            WHERE export_id IN (
                SELECT job.export_id FROM export_jobs job
                JOIN export_job_revocations revocation ON revocation.export_id = job.export_id AND revocation.study_id = job.study_id
                WHERE revocation.operation_id = operation.operation_id AND job.study_id = operation.study_id
                  AND job.request <> '{}'::jsonb
                LIMIT 500
            );
            GET DIAGNOSTICS affected = ROW_COUNT;
            EXIT WHEN affected = 0;
        END LOOP;
    END LOOP;

    -- Old unscoped compliance jobs cannot prove the referenced subjects are still permitted.
    -- Remove only the unsent jobs, in bounded batches; keep their notification delivery logs.
    LOOP
        DELETE FROM jobs WHERE ctid IN (
            SELECT ctid FROM jobs WHERE COALESCE(definition ->> '@type', definition ->> '@class', '') IN
                ('Notification', 'com.openlattice.chronicle.services.notifications.Notification')
              AND definition ->> 'notificationType' = 'PASSIVE_DATA_COLLECTION_COMPLIANCE'
              AND cardinality(participant_ids) = 0
            LIMIT 500
        );
        GET DIAGNOSTICS affected = ROW_COUNT;
        EXIT WHEN affected = 0;
    END LOOP;
    PERFORM set_config('app.is_admin', COALESCE(previous_admin, ''), true);
    PERFORM set_config('app.current_user_id', COALESCE(previous_user, ''), true);
END $$;

DO $$
DECLARE app_role TEXT;
BEGIN
    FOREACH app_role IN ARRAY ARRAY['chronicle_app', 'chronicle_admin', 'chronicle'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON participant_purge_cutoffs TO %I', app_role);
            EXECUTE format('GRANT EXECUTE ON FUNCTION chronicle_is_deletion_worker() TO %I', app_role);
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON devices, notifications, webhook_registrations, webhook_deliveries TO chronicle_app;
        -- V70 narrowed export_jobs UPDATE to named columns; erasure scrubs revoked requests.
        GRANT UPDATE (request) ON export_jobs TO chronicle_app;
    END IF;
END $$;

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V109__complete_participant_erasure_and_replay_fences', 'Complete', NOW())
ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
