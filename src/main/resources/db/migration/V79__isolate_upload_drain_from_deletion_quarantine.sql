-- Upload-buffer drains must use the same durable deletion decision as the
-- INSERT/UPDATE mutation guard.  A raw/background datasource can bypass table
-- RLS, so relying on the upload_buffer SELECT policy alone lets a permanently
-- blocked row poison a mixed-subject drain when the destination trigger rejects
-- it with SQLSTATE 55000.

CREATE OR REPLACE FUNCTION chronicle_participant_mutation_allowed(
    check_study_id UUID,
    check_participant_id TEXT
) RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT public.chronicle_is_deletion_worker()
        OR NOT EXISTS (
            SELECT 1
            FROM public.data_deletion_operations operation
            WHERE operation.study_id = check_study_id
              AND (
                  operation.mode = 'STUDY_ERASURE'
                  OR operation.participant_id = check_participant_id
                  OR (
                      operation.participant_block_token IS NOT NULL
                      AND operation.participant_block_token =
                          md5(check_study_id::text || ':' || check_participant_id)
                  )
              )
              AND (
                  (
                      operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                      AND operation.status IN (
                          'QUARANTINED', 'HELD', 'READY', 'ERASING',
                          'VERIFYING', 'FAILED', 'COMPLETED'
                      )
                  )
                  OR (
                      operation.mode = 'COLLECTED_DATA_PURGE'
                      AND operation.status IN ('READY', 'ERASING', 'VERIFYING', 'FAILED')
                  )
              )
        )
$$;

REVOKE ALL ON FUNCTION chronicle_participant_mutation_allowed(UUID, TEXT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION chronicle_participant_mutation_allowed(
    check_study_id TEXT,
    check_participant_id TEXT
) RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF public.chronicle_is_deletion_worker() THEN
        RETURN true;
    END IF;
    IF check_study_id IS NULL OR check_study_id = '' THEN
        RETURN false;
    END IF;
    RETURN public.chronicle_participant_mutation_allowed(
        check_study_id::UUID,
        check_participant_id
    );
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$;

REVOKE ALL ON FUNCTION chronicle_participant_mutation_allowed(TEXT, TEXT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION chronicle_guard_participant_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    locked_study TEXT;
BEGIN
    IF public.chronicle_is_deletion_worker() THEN
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
        WHERE NOT public.chronicle_participant_mutation_allowed(
            subject.study_id,
            subject.participant_id
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
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        GRANT EXECUTE ON FUNCTION chronicle_participant_mutation_allowed(UUID, TEXT)
            TO chronicle_app;
        GRANT EXECUTE ON FUNCTION chronicle_participant_mutation_allowed(TEXT, TEXT)
            TO chronicle_app;
    END IF;
END
$$;

INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
VALUES ('V79__isolate_upload_drain_from_deletion_quarantine', 'Complete', NOW())
ON CONFLICT (upgrade_class)
DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
