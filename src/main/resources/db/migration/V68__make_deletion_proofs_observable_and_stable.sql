-- V68: make deletion counts observable only to the erasure worker and prevent
-- participant writes from racing a quarantine/erasure proof.

ALTER TABLE data_deletion_operations
    ADD COLUMN IF NOT EXISTS participant_block_token TEXT;

UPDATE data_deletion_operations
SET participant_block_token = md5(study_id::text || ':' || participant_id)
WHERE participant_id IS NOT NULL
  AND participant_block_token IS NULL;

CREATE OR REPLACE FUNCTION chronicle_set_deletion_block_token()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.participant_id IS NOT NULL
       AND (
           TG_OP = 'INSERT'
           OR NEW.participant_block_token IS NULL
           OR NEW.study_id IS DISTINCT FROM OLD.study_id
           OR NEW.participant_id IS DISTINCT FROM OLD.participant_id
       )
    THEN
        NEW.participant_block_token := md5(NEW.study_id::text || ':' || NEW.participant_id);
    ELSIF TG_OP = 'UPDATE' AND NEW.participant_id IS NULL THEN
        NEW.participant_block_token := OLD.participant_block_token;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION chronicle_set_deletion_block_token() FROM PUBLIC;

DROP TRIGGER IF EXISTS set_deletion_block_token ON data_deletion_operations;
CREATE TRIGGER set_deletion_block_token
BEFORE INSERT OR UPDATE OF study_id, participant_id, participant_block_token
ON data_deletion_operations
FOR EACH ROW
EXECUTE FUNCTION chronicle_set_deletion_block_token();

CREATE INDEX IF NOT EXISTS data_deletion_operations_write_block_idx
    ON data_deletion_operations (study_id, participant_block_token, status);

CREATE OR REPLACE FUNCTION chronicle_is_deletion_worker()
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SET search_path = pg_catalog
AS $$
    SELECT COALESCE(current_setting('app.is_admin', true) = 'true', false)
       AND COALESCE(
           current_setting('app.current_user_id', true) = 'chronicle-deletion-worker',
           false
       )
$$;

REVOKE ALL ON FUNCTION chronicle_is_deletion_worker() FROM PUBLIC;

CREATE OR REPLACE FUNCTION chronicle_participant_data_visible(
    check_study_id UUID,
    check_participant_id TEXT
) RETURNS BOOLEAN AS $$
BEGIN
    IF chronicle_is_deletion_worker() THEN
        RETURN true;
    END IF;
    RETURN NOT EXISTS (
        SELECT 1
        FROM public.data_deletion_operations operation
        WHERE operation.study_id = check_study_id
          AND operation.status IN ('QUARANTINED', 'HELD', 'READY', 'ERASING', 'VERIFYING', 'FAILED')
          AND (
              operation.mode = 'STUDY_ERASURE'
              OR operation.participant_id = check_participant_id
          )
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public;

REVOKE ALL ON FUNCTION chronicle_participant_data_visible(UUID, TEXT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION chronicle_participant_data_visible(
    check_study_id TEXT,
    check_participant_id TEXT
) RETURNS BOOLEAN AS $$
BEGIN
    IF check_study_id IS NULL OR check_study_id = '' THEN
        RETURN false;
    END IF;
    RETURN chronicle_participant_data_visible(check_study_id::UUID, check_participant_id);
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public;

REVOKE ALL ON FUNCTION chronicle_participant_data_visible(TEXT, TEXT) FROM PUBLIC;

/*
 * Every participant-data INSERT/UPDATE takes a shared per-study transaction
 * lock before checking the ledger. Queue activation and the READY->ERASING
 * transition take the matching exclusive lock in Kotlin. Therefore:
 *   - queue-time counts include every writer that won the race;
 *   - writers that lose the race observe the new blocking status and roll back;
 *   - the final zero-count cannot be invalidated behind the proof transaction.
 *
 * This is a statement-level trigger: a multi-row sensor batch pays for one
 * ledger lookup, not one lookup per sample.
 */
CREATE OR REPLACE FUNCTION chronicle_guard_participant_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    locked_study TEXT;
BEGIN
    IF chronicle_is_deletion_worker() THEN
        RETURN NULL;
    END IF;

    FOR locked_study IN
        SELECT DISTINCT rows.study_id::text
        FROM new_rows rows
        WHERE rows.study_id IS NOT NULL
        ORDER BY rows.study_id::text
    LOOP
        PERFORM pg_advisory_xact_lock_shared(
            hashtextextended('chronicle-deletion:' || locked_study, 0)
        );
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM (
            SELECT DISTINCT rows.study_id::text AS study_id, rows.participant_id
            FROM new_rows rows
            WHERE rows.study_id IS NOT NULL
        ) subject
        JOIN public.data_deletion_operations operation
          ON operation.study_id::text = subject.study_id
         AND (
             operation.mode = 'STUDY_ERASURE'
             OR operation.participant_id = subject.participant_id
             OR (
                 operation.participant_block_token IS NOT NULL
                 AND operation.participant_block_token =
                     md5(subject.study_id || ':' || subject.participant_id)
             )
         )
        WHERE (
            operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
            AND operation.status IN (
                'QUARANTINED', 'HELD', 'READY', 'ERASING',
                'VERIFYING', 'FAILED', 'COMPLETED'
            )
        ) OR (
            operation.mode = 'COLLECTED_DATA_PURGE'
            AND operation.status IN ('READY', 'ERASING', 'VERIFYING', 'FAILED')
        )
    ) THEN
        RAISE EXCEPTION 'Participant data mutation is blocked by an erasure operation'
            USING ERRCODE = '55000';
    END IF;

    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION chronicle_guard_participant_mutation() FROM PUBLIC;

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'chronicle_usage_events',
        'chronicle_usage_stats',
        'preprocessed_usage_events',
        'sensor_data',
        'android_sensor_data',
        'app_usage_survey',
        'questionnaire_submissions',
        'time_use_diary_submissions',
        'participant_stats',
        'upload_buffer',
        'battery_telemetry',
        'interaction_events',
        'app_audio_activity',
        'app_audio_content',
        'notification_activity',
        'sleep_events',
        'activity_recognition_events',
        'health_metrics',
        'connectivity_state_events',
        'app_network_usage',
        'device_settings',
        'encrypted_payloads',
        'data_quality_alerts',
        'time_use_diary_summarized',
        'study_event_stream',
        'android_device_sensor_availability',
        'participant_form_submission_receipts',
        'participant_form_sessions',
        'participant_form_access_codes',
        'study_participants'
    ] LOOP
        EXECUTE format(
            'DROP TRIGGER IF EXISTS %I ON %I',
            'deletion_mutation_guard_insert',
            table_name
        );
        EXECUTE format(
            'CREATE TRIGGER deletion_mutation_guard_insert ' ||
            'AFTER INSERT ON %I ' ||
            'REFERENCING NEW TABLE AS new_rows ' ||
            'FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation()',
            table_name
        );
        EXECUTE format(
            'DROP TRIGGER IF EXISTS %I ON %I',
            'deletion_mutation_guard_update',
            table_name
        );
        EXECUTE format(
            'CREATE TRIGGER deletion_mutation_guard_update ' ||
            'AFTER UPDATE ON %I ' ||
            'REFERENCING NEW TABLE AS new_rows ' ||
            'FOR EACH STATEMENT EXECUTE FUNCTION chronicle_guard_participant_mutation()',
            table_name
        );
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        -- V50 predates the V60 fallback role creation on fresh installs, so
        -- its conditional grants may have been skipped. Converge the exact
        -- erasure surface now that chronicle_app is guaranteed to exist.
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            data_deletion_operations,
            data_deletion_steps,
            retention_holds,
            participant_form_access_codes,
            participant_form_sessions,
            participant_form_submission_receipts,
            chronicle_usage_events,
            chronicle_usage_stats,
            preprocessed_usage_events,
            sensor_data,
            android_sensor_data,
            app_usage_survey,
            questionnaire_submissions,
            time_use_diary_submissions,
            participant_stats,
            upload_buffer,
            battery_telemetry,
            interaction_events,
            app_audio_activity,
            app_audio_content,
            notification_activity,
            sleep_events,
            activity_recognition_events,
            health_metrics,
            connectivity_state_events,
            app_network_usage,
            device_settings,
            encrypted_payloads,
            data_quality_alerts,
            time_use_diary_summarized,
            study_event_stream,
            android_device_sensor_availability,
            study_participants
        TO chronicle_app;
        GRANT SELECT, INSERT ON data_deletion_tombstones TO chronicle_app;
        GRANT EXECUTE ON FUNCTION chronicle_participant_data_visible(UUID, TEXT) TO chronicle_app;
        GRANT EXECUTE ON FUNCTION chronicle_participant_data_visible(TEXT, TEXT) TO chronicle_app;
    END IF;
END $$;
