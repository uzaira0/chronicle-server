-- Fresh server baseline after the 2026-10-17 reset.
-- chronicle:destructive-approved Fresh installations only; replaces the retired V1-V135 chain.
-- Rhizome creates its registered tables before Flyway; complete those definitions first,
-- then create service-owned objects and apply their final security boundaries.
SET LOCAL check_function_bodies = false;
SET LOCAL search_path = public, pg_catalog;

-- Unicode validation requires character semantics rather than SQL_ASCII byte semantics.
DO $$ BEGIN
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'Chronicle requires UTF8 database encoding; found %. Initialize PostgreSQL with --encoding=UTF8.',
            current_setting('server_encoding');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        CREATE ROLE chronicle_app NOLOGIN;
    END IF;
END $$;

-- Functions

-- chronicle_advance_disclosure_generation()
CREATE FUNCTION public.chronicle_advance_disclosure_generation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    UPDATE public.studies SET disclosure_generation = gen_random_uuid()
    WHERE study_id = NEW.study_id;
    RETURN NEW;
END;
$$;

-- chronicle_assert_acl_permanent_owner(uuid[])
CREATE FUNCTION public.chronicle_assert_acl_permanent_owner(target_acl_key uuid[]) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Deleting a securable object intentionally deletes its whole ACL.
    IF NOT EXISTS (
        SELECT 1
        FROM securable_objects
        WHERE acl_key = target_acl_key
    ) THEN
        RETURN;
    END IF;

    -- A reserved object with no ACEs is inert. The first permission-bearing
    -- transaction must establish a permanent owner before it can commit.
    IF NOT EXISTS (
        SELECT 1
        FROM permissions
        WHERE acl_key = target_acl_key
          AND cardinality(permissions) > 0
    ) THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM permissions
        WHERE acl_key = target_acl_key
          AND 'OWNER' = ANY(permissions)
          AND expiration_date = 'infinity'::timestamptz
          AND chronicle_is_eligible_permanent_acl_owner(
              acl_key,
              principal_type,
              principal_id
          )
    ) THEN
        RAISE EXCEPTION
            'ACL % must retain an eligible non-expiring owner',
            target_acl_key
            USING ERRCODE = '23514',
                  CONSTRAINT = 'permissions_permanent_owner';
    END IF;
END;
$$;

-- chronicle_bind_webhook_delivery_scope()
CREATE FUNCTION public.chronicle_bind_webhook_delivery_scope() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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

-- chronicle_count_study_rows_for_erasure(text, uuid)
CREATE FUNCTION public.chronicle_count_study_rows_for_erasure(target_table text, target_study_id uuid) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $_$
DECLARE
    matched_rows BIGINT;
BEGIN
    IF NOT public.chronicle_is_deletion_worker() THEN
        RAISE EXCEPTION 'Study verification requires the deletion-worker identity'
            USING ERRCODE = '42501';
    END IF;

    IF target_table !~ '^[a-z][a-z0-9_]*$'
       OR target_table IN (
           'audit',
           'audit_buffer',
           'study_settings_audit',
           'participant_collection_acknowledgment',
           'study_lifecycle_events',
           'data_deletion_operations',
           'data_deletion_steps',
           'data_deletion_tombstones',
           'data_deletion_form_access_revocations',
           'data_deletion_audit_outbox'
       )
    THEN
        RAISE EXCEPTION 'Untrusted or retained study table'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns AS columns
        JOIN information_schema.tables AS tables
          ON tables.table_schema = columns.table_schema
         AND tables.table_name = columns.table_name
        WHERE columns.table_schema = 'public'
          AND columns.table_name = target_table
          AND columns.column_name = 'study_id'
          AND tables.table_type = 'BASE TABLE'
    ) THEN
        RAISE EXCEPTION 'Study verification target is not a public study-scoped base table'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.data_deletion_operations AS operation
        WHERE operation.study_id = target_study_id
          AND operation.mode = 'STUDY_ERASURE'
          AND operation.status IN ('ERASING', 'VERIFYING')
          AND operation.worker_lease_token IS NOT NULL
          AND operation.worker_lease_expires_at > now()
    ) THEN
        RAISE EXCEPTION 'Study verification has no active leased operation'
            USING ERRCODE = '55000';
    END IF;

    EXECUTE format(
        'SELECT count(*) FROM public.%I WHERE study_id = %L',
        target_table, target_study_id
    ) INTO matched_rows;
    RETURN matched_rows;
END
$_$;

-- chronicle_delete_study_rows(text, uuid)
CREATE FUNCTION public.chronicle_delete_study_rows(target_table text, target_study_id uuid) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $_$
DECLARE
    deleted_rows BIGINT;
BEGIN
    IF NOT public.chronicle_is_deletion_worker() THEN
        RAISE EXCEPTION 'Study erasure requires the deletion-worker identity'
            USING ERRCODE = '42501';
    END IF;

    IF target_table !~ '^[a-z][a-z0-9_]*$'
       OR target_table IN (
           'audit',
           'audit_buffer',
           'study_settings_audit',
           'participant_collection_acknowledgment',
           'study_lifecycle_events',
           'data_deletion_operations',
           'data_deletion_steps',
           'data_deletion_tombstones',
           'data_deletion_form_access_revocations',
           'data_deletion_audit_outbox'
       )
    THEN
        RAISE EXCEPTION 'Untrusted or retained study table'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns AS columns
        JOIN information_schema.tables AS tables
          ON tables.table_schema = columns.table_schema
         AND tables.table_name = columns.table_name
        WHERE columns.table_schema = 'public'
          AND columns.table_name = target_table
          AND columns.column_name = 'study_id'
          AND tables.table_type = 'BASE TABLE'
    ) THEN
        RAISE EXCEPTION 'Study erasure target is not a public study-scoped base table'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.data_deletion_operations AS operation
        WHERE operation.study_id = target_study_id
          AND operation.mode = 'STUDY_ERASURE'
          AND operation.status IN ('ERASING', 'VERIFYING')
          AND operation.worker_lease_token IS NOT NULL
          AND operation.worker_lease_expires_at > now()
    ) THEN
        RAISE EXCEPTION 'Study erasure has no active leased operation'
            USING ERRCODE = '55000';
    END IF;

    EXECUTE format(
        'DELETE FROM public.%I WHERE study_id = %L',
        target_table, target_study_id
    );
    GET DIAGNOSTICS deleted_rows = ROW_COUNT;
    RETURN deleted_rows;
END
$_$;

-- chronicle_discover_study_erasure_tables(uuid)
CREATE FUNCTION public.chronicle_discover_study_erasure_tables(target_study_id uuid) RETURNS TABLE(table_name text)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    IF NOT public.chronicle_is_deletion_worker() THEN
        RAISE EXCEPTION 'Study inventory discovery requires the deletion-worker identity'
            USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM public.data_deletion_operations AS operation
        WHERE operation.study_id = target_study_id
          AND operation.mode = 'STUDY_ERASURE'
          AND operation.status IN ('ERASING', 'VERIFYING')
          AND operation.worker_lease_token IS NOT NULL
          AND operation.worker_lease_expires_at > now()
    ) THEN
        RAISE EXCEPTION 'Study inventory discovery has no active leased operation'
            USING ERRCODE = '55000';
    END IF;

    RETURN QUERY
    SELECT columns.table_name::TEXT
    FROM information_schema.columns AS columns
    JOIN information_schema.tables AS tables
      ON tables.table_schema = columns.table_schema
     AND tables.table_name = columns.table_name
    WHERE columns.table_schema = 'public'
      AND columns.column_name = 'study_id'
      AND tables.table_type = 'BASE TABLE'
      AND columns.table_name NOT IN (
          'audit',
          'audit_buffer',
          'study_settings_audit',
          'participant_collection_acknowledgment',
          'study_lifecycle_events',
          'data_deletion_operations',
          'data_deletion_steps',
          'data_deletion_tombstones',
          'data_deletion_form_access_revocations',
          'data_deletion_audit_outbox'
      );
END
$$;

-- chronicle_enrollment_acknowledged(uuid)
CREATE FUNCTION public.chronicle_enrollment_acknowledged(p_access_code_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path TO 'pg_catalog', 'public'
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.participant_collection_acknowledgment ack
        WHERE ack.evidence_access_code_id = p_access_code_id AND ack.collection_trigger = 'ENROLLMENT'
          AND ack.evidence_api_key_id IS NOT NULL
    )
$$;

-- chronicle_guard_job_against_erasure()
CREATE FUNCTION public.chronicle_guard_job_against_erasure() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    IF NEW.study_id IS NULL OR chronicle_is_deletion_worker() THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock_shared(
        hashtextextended('chronicle-deletion:' || NEW.study_id::text, 0)
    );

    IF EXISTS (
        SELECT 1
        FROM public.data_deletion_operations operation
        WHERE operation.study_id = NEW.study_id
          AND (
              operation.mode = 'STUDY_ERASURE'
              OR operation.participant_id = ANY(NEW.participant_ids)
              OR (
                  operation.participant_block_token IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM unnest(NEW.participant_ids) participant_id
                      WHERE operation.participant_block_token =
                          chronicle_participant_block_token(NEW.study_id, participant_id)
                  )
              )
          )
          AND (
              (
                  operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                  AND operation.status IN (
                      'QUARANTINED', 'ERASING',
                      'VERIFYING', 'FAILED', 'COMPLETED'
                  )
              )
              OR (
                  operation.mode = 'COLLECTED_DATA_PURGE'
                  AND operation.status IN ('ERASING', 'VERIFYING', 'FAILED')
              )
          )
    ) THEN
        RAISE EXCEPTION 'Job creation or scope mutation is blocked by an erasure operation'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$$;

-- chronicle_guard_job_scope_columns()
CREATE FUNCTION public.chronicle_guard_job_scope_columns() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    IF NEW.definition IS NOT DISTINCT FROM OLD.definition THEN
        RAISE EXCEPTION 'Job scope columns are derived from definition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

-- chronicle_guard_participant_mutation()
CREATE FUNCTION public.chronicle_guard_participant_mutation() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
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

-- chronicle_has_candidate_access(uuid)
CREATE FUNCTION public.chronicle_has_candidate_access(check_candidate_id uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    AS $$
DECLARE
    is_admin_user BOOLEAN;
    authorized_studies_setting TEXT;
    authorized_studies UUID[];
    is_linked BOOLEAN;
BEGIN
    is_admin_user := COALESCE(
        NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
        false
    );
    IF is_admin_user THEN
        RETURN true;
    END IF;

    -- Orphan candidate (not yet linked to any study) is visible: needed so candidate
    -- registration can validate the id before the study_participants link is created.
    is_linked := EXISTS (
        SELECT 1 FROM study_participants sp WHERE sp.candidate_id = check_candidate_id
    );
    IF NOT is_linked THEN
        RETURN true;
    END IF;

    authorized_studies_setting := current_setting('app.authorized_studies', true);
    IF authorized_studies_setting IS NULL OR authorized_studies_setting = '' THEN
        RETURN false;
    END IF;

    BEGIN
        authorized_studies := string_to_array(authorized_studies_setting, ',')::UUID[];
    EXCEPTION WHEN OTHERS THEN
        RETURN false;
    END;

    RETURN EXISTS (
        SELECT 1 FROM study_participants sp
        WHERE sp.candidate_id = check_candidate_id
          AND sp.study_id = ANY(authorized_studies)
    );
END;
$$;

-- chronicle_has_candidate_write_access(uuid)
CREATE FUNCTION public.chronicle_has_candidate_write_access(check_candidate_id uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    AS $$
DECLARE
    is_admin_user BOOLEAN;
    authorized_studies_setting TEXT;
    authorized_studies UUID[];
BEGIN
    is_admin_user := COALESCE(
        NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
        false
    );
    IF is_admin_user THEN
        RETURN true;
    END IF;

    authorized_studies_setting := current_setting('app.authorized_studies', true);
    IF authorized_studies_setting IS NULL OR authorized_studies_setting = '' THEN
        RETURN false;
    END IF;

    BEGIN
        authorized_studies := string_to_array(authorized_studies_setting, ',')::UUID[];
    EXCEPTION WHEN OTHERS THEN
        RETURN false;
    END;

    -- No orphan branch: an unlinked candidate is NOT writable by a non-admin. Writability
    -- requires the candidate to be linked to a study the caller is authorized for.
    RETURN EXISTS (
        SELECT 1 FROM study_participants sp
        WHERE sp.candidate_id = check_candidate_id
          AND sp.study_id = ANY(authorized_studies)
    );
END;
$$;

-- chronicle_has_participant_erasure(uuid, text)
CREATE FUNCTION public.chronicle_has_participant_erasure(p_study_id uuid, p_participant_id text) RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path TO 'pg_catalog', 'public'
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.data_deletion_operations erasure
        WHERE erasure.study_id = p_study_id AND erasure.status NOT IN ('PREVIEW', 'CANCELLED')
          AND (erasure.mode = 'STUDY_ERASURE' OR
               (erasure.mode = 'WITHDRAW_AND_ERASE' AND erasure.participant_block_token =
                    public.chronicle_participant_block_token(p_study_id, p_participant_id)))
    )
$$;

-- chronicle_is_deletion_worker()
CREATE FUNCTION public.chronicle_is_deletion_worker() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path TO 'pg_catalog'
    AS $$
    SELECT COALESCE(current_setting('app.is_admin', true) = 'true', false)
       AND COALESCE(current_setting('app.is_deletion_worker', true) = 'true', false)
$$;

-- chronicle_is_eligible_permanent_acl_owner(uuid[], text, text)
CREATE FUNCTION public.chronicle_is_eligible_permanent_acl_owner(target_acl_key uuid[], target_principal_type text, target_principal_id text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    SELECT
        target_principal_type = 'USER'
        OR (
            target_principal_type = 'ROLE'
            AND target_principal_id = 'admin'
        )
        OR (
            target_principal_type = 'ROLE'
            AND target_principal_id IN ('AuthenticatedUser', 'AnonymousUser')
            AND cardinality(target_acl_key) = 2
            AND target_acl_key[1] = '00000000-0000-0000-0000-000000000002'::uuid
            AND EXISTS (
                SELECT 1
                FROM securable_objects AS bootstrap_object
                WHERE bootstrap_object.acl_key = target_acl_key
                  AND bootstrap_object.securable_object_type = 'Role'
                  AND bootstrap_object.name = target_principal_id
            )
        )
$$;

-- chronicle_lock_acl_owner_mutation()
CREATE FUNCTION public.chronicle_lock_acl_owner_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    old_acl_key UUID[];
    new_acl_key UUID[];
BEGIN
    IF TG_OP <> 'INSERT'
        AND 'OWNER' = ANY(OLD.permissions)
        AND chronicle_is_eligible_permanent_acl_owner(
            OLD.acl_key,
            OLD.principal_type,
            OLD.principal_id
        )
    THEN
        old_acl_key := OLD.acl_key;
    END IF;

    IF TG_OP <> 'DELETE'
        AND 'OWNER' = ANY(NEW.permissions)
        AND chronicle_is_eligible_permanent_acl_owner(
            NEW.acl_key,
            NEW.principal_type,
            NEW.principal_id
        )
    THEN
        new_acl_key := NEW.acl_key;
    END IF;

    /*
     * Mutate a real fencing row before the ACE write. Under READ COMMITTED this
     * serializes and refreshes the later constraint check; under REPEATABLE READ
     * or SERIALIZABLE a waiter whose snapshot predates the other mutation gets a
     * serialization failure instead of committing against a stale snapshot.
     */
    IF old_acl_key IS NOT NULL AND new_acl_key IS NOT NULL THEN
        IF array_to_string(old_acl_key, ',') <= array_to_string(new_acl_key, ',') THEN
            UPDATE securable_objects
            SET acl_owner_fence = acl_owner_fence + 1
            WHERE acl_key = old_acl_key;
            IF old_acl_key <> new_acl_key THEN
                UPDATE securable_objects
                SET acl_owner_fence = acl_owner_fence + 1
                WHERE acl_key = new_acl_key;
            END IF;
        ELSE
            UPDATE securable_objects
            SET acl_owner_fence = acl_owner_fence + 1
            WHERE acl_key = new_acl_key;
            UPDATE securable_objects
            SET acl_owner_fence = acl_owner_fence + 1
            WHERE acl_key = old_acl_key;
        END IF;
    ELSIF old_acl_key IS NOT NULL THEN
        UPDATE securable_objects
        SET acl_owner_fence = acl_owner_fence + 1
        WHERE acl_key = old_acl_key;
    ELSIF new_acl_key IS NOT NULL THEN
        UPDATE securable_objects
        SET acl_owner_fence = acl_owner_fence + 1
        WHERE acl_key = new_acl_key;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

-- chronicle_lock_study_table_for_erasure(text, uuid)
CREATE FUNCTION public.chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $_$
BEGIN
    IF NOT public.chronicle_is_deletion_worker() THEN
        RAISE EXCEPTION 'Study table locking requires the deletion-worker identity'
            USING ERRCODE = '42501';
    END IF;
    IF target_table !~ '^[a-z][a-z0-9_]*$'
       OR NOT EXISTS (
           SELECT 1
           FROM information_schema.columns AS columns
           JOIN information_schema.tables AS tables
             ON tables.table_schema = columns.table_schema
            AND tables.table_name = columns.table_name
           WHERE columns.table_schema = 'public'
             AND columns.table_name = target_table
             AND columns.column_name = 'study_id'
             AND tables.table_type = 'BASE TABLE'
       )
    THEN
        RAISE EXCEPTION 'Untrusted study table lock target'
            USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM public.data_deletion_operations AS operation
        WHERE operation.study_id = target_study_id
          AND operation.mode = 'STUDY_ERASURE'
          AND operation.status IN ('ERASING', 'VERIFYING')
          AND operation.worker_lease_token IS NOT NULL
          AND operation.worker_lease_expires_at > now()
    ) THEN
        RAISE EXCEPTION 'Study table locking has no active leased operation'
            USING ERRCODE = '55000';
    END IF;

    EXECUTE format(
        'LOCK TABLE public.%I IN ACCESS SHARE MODE',
        target_table
    );
END
$_$;

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE TABLE public.chronicle_participant_token_key (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    key_bytes bytea NOT NULL CHECK (octet_length(key_bytes) = 32)
);
INSERT INTO public.chronicle_participant_token_key (key_bytes) VALUES (public.gen_random_bytes(32));
REVOKE ALL ON public.chronicle_participant_token_key FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT ON public.chronicle_participant_token_key TO CURRENT_USER;

-- chronicle_participant_block_token(uuid, text)
CREATE FUNCTION public.chronicle_participant_block_token(p_study_id uuid, p_participant_id text) RETURNS text
    LANGUAGE plpgsql STABLE STRICT SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
DECLARE token_key bytea;
BEGIN
    SELECT key_bytes INTO STRICT token_key
    FROM public.chronicle_participant_token_key WHERE singleton;
    RETURN encode(public.hmac(convert_to(p_study_id::text || ':' || p_participant_id, 'UTF8'), token_key, 'sha256'), 'hex');
END;
$$;

-- chronicle_participant_data_visible(text, text)
CREATE FUNCTION public.chronicle_participant_data_visible(check_study_id text, check_participant_id text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    IF check_study_id IS NULL OR check_study_id = '' THEN
        RETURN false;
    END IF;
    RETURN chronicle_participant_data_visible(check_study_id::UUID, check_participant_id);
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$;

-- chronicle_participant_data_visible(uuid, text)
CREATE FUNCTION public.chronicle_participant_data_visible(check_study_id uuid, check_participant_id text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    IF chronicle_is_deletion_worker() THEN
        RETURN true;
    END IF;
    -- Reads the operations itself: the enumeration helpers only list the caller's studies, and this answer must
    -- hold for any caller (background work, pre-auth paths).
    RETURN NOT EXISTS (
        SELECT 1 FROM public.data_deletion_operations operation
        WHERE operation.study_id = check_study_id
          AND public.chronicle_erasure_hides_data(operation.status)
          AND (operation.mode = 'STUDY_ERASURE' OR operation.participant_id = check_participant_id)
    );
END;
$$;

-- chronicle_participant_mutation_allowed(text, text)
CREATE FUNCTION public.chronicle_participant_mutation_allowed(check_study_id text, check_participant_id text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
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

-- chronicle_participant_mutation_allowed(uuid, text)
CREATE FUNCTION public.chronicle_participant_mutation_allowed(check_study_id uuid, check_participant_id text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
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
                          chronicle_participant_block_token(check_study_id, check_participant_id)
                  )
              )
              AND (
                  (
                      operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                      AND operation.status IN (
                          'QUARANTINED', 'ERASING',
                          'VERIFYING', 'FAILED', 'COMPLETED'
                      )
                  )
                  OR (
                      operation.mode = 'COLLECTED_DATA_PURGE'
                      AND operation.status IN ('ERASING', 'VERIFYING', 'FAILED')
                  )
              )
        )
$$;

-- chronicle_protect_last_active_acl_owner()
CREATE FUNCTION public.chronicle_protect_last_active_acl_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Every permission mutation is checked. Restricting this trigger to rows
    -- that already looked like eligible owners allowed a first ineligible ACE
    -- (including a ROLE owner on an arbitrary ACL) to bypass the invariant.
    IF TG_OP <> 'INSERT' THEN
        PERFORM chronicle_assert_acl_permanent_owner(OLD.acl_key);
    END IF;

    IF TG_OP = 'INSERT'
        OR (TG_OP = 'UPDATE' AND NEW.acl_key IS DISTINCT FROM OLD.acl_key)
    THEN
        PERFORM chronicle_assert_acl_permanent_owner(NEW.acl_key);
    END IF;

    RETURN NULL;
END;
$$;

-- chronicle_protect_securable_object_owner()
CREATE FUNCTION public.chronicle_protect_securable_object_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM chronicle_assert_acl_permanent_owner(NEW.acl_key);
    RETURN NULL;
END;
$$;

-- chronicle_record_study_capture_cutoff()
CREATE FUNCTION public.chronicle_record_study_capture_cutoff() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.closed_capture_windows := COALESCE(NEW.closed_capture_windows, '{}'::tstzmultirange);
    ELSE
        NEW.closed_capture_windows := OLD.closed_capture_windows + NEW.closed_capture_windows;
    END IF;
    IF NEW.lifecycle_status = 'ACTIVE' THEN
        IF TG_OP = 'UPDATE' AND OLD.collection_closed_at IS NOT NULL THEN
            NEW.closed_capture_windows := NEW.closed_capture_windows
                + tstzmultirange(tstzrange(OLD.collection_closed_at, GREATEST(OLD.collection_closed_at, now())));
        END IF;
        NEW.collection_closed_at := NULL;
    ELSIF TG_OP = 'INSERT' OR OLD.lifecycle_status = 'ACTIVE' THEN
        NEW.collection_closed_at := LEAST(NEW.collection_closed_at, clock_timestamp());
    ELSE
        NEW.collection_closed_at := LEAST(OLD.collection_closed_at, NEW.collection_closed_at);
    END IF;
    RETURN NEW;
END $$;

-- chronicle_record_capture_cutoff()
CREATE FUNCTION public.chronicle_record_capture_cutoff() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    restored_generation bigint;
    restored_at timestamp with time zone;
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.hard_stop_generation := 0;
        NEW.hard_stopped_at := NULL;
        RETURN NEW;
    END IF;
    NEW.closed_capture_windows := OLD.closed_capture_windows + NEW.closed_capture_windows;
    -- Every status change, by any writer, advances the revision researchers' If-Match is checked against.
    NEW.status_revision := OLD.status_revision
        + CASE WHEN NEW.participation_status IS DISTINCT FROM OLD.participation_status THEN 1 ELSE 0 END;
    -- Every hard stop (Mark not enrolled or withdrawal, by any writer) is counted and stamped here, so a phone that
    -- was enrolled again before it polled still learns of it. Writers cannot set either column.
    -- Only the protected restore checkpoint can import an earlier database's stop. Application roles cannot write
    -- this schema; restore/reconciliation runs before they start. Keep the trigger as the sole column writer.
    IF to_regclass('chronicle_restore_continuity.restricted_participants') IS NOT NULL THEN
        SELECT hard_stop_generation, hard_stopped_at INTO restored_generation, restored_at
        FROM chronicle_restore_continuity.restricted_participants
        WHERE study_id = NEW.study_id AND participant_id = NEW.participant_id;
    END IF;
    IF restored_generation IS NOT NULL THEN
        NEW.hard_stop_generation := GREATEST(OLD.hard_stop_generation, restored_generation);
        NEW.hard_stopped_at := GREATEST(OLD.hard_stopped_at, restored_at);
    ELSIF NEW.participation_status = 'NOT_ENROLLED' AND OLD.participation_status IS DISTINCT FROM 'NOT_ENROLLED' THEN
        NEW.hard_stop_generation := OLD.hard_stop_generation + 1;
        NEW.hard_stopped_at := GREATEST(OLD.hard_stopped_at, now());
    ELSE
        NEW.hard_stop_generation := OLD.hard_stop_generation;
        NEW.hard_stopped_at := OLD.hard_stopped_at;
    END IF;
    IF NEW.participation_status = 'ENROLLED' THEN
        IF OLD.capture_cutoff IS NOT NULL THEN
            NEW.closed_capture_windows := NEW.closed_capture_windows
                + tstzmultirange(tstzrange(OLD.capture_cutoff, GREATEST(OLD.capture_cutoff, now())));
        END IF;
        NEW.capture_cutoff := NULL;
    ELSIF OLD.participation_status = 'ENROLLED'
          AND NEW.participation_status IN ('PAUSED', 'COLLECTION_COMPLETED', 'NOT_ENROLLED') THEN
        NEW.capture_cutoff := now();
    ELSE
        NEW.capture_cutoff := OLD.capture_cutoff;
    END IF;
    RETURN NEW;
END $$;

-- chronicle_record_erased_diagnostic_ids()
CREATE FUNCTION public.chronicle_record_erased_diagnostic_ids() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
    INSERT INTO public.upload_diagnostic_erasures (study_id, participant_block_token, event_id)
    SELECT DISTINCT study_id, chronicle_participant_block_token(study_id, participant_id), event_id::uuid
    FROM erased_rows
    ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;

-- chronicle_reject_restore_continuity_receipt_mutation()
CREATE FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN
    RAISE EXCEPTION 'Restore continuity reconciliation receipts are immutable'
        USING ERRCODE = '55000';
END
$$;

-- chronicle_set_deletion_block_token()
CREATE FUNCTION public.chronicle_set_deletion_block_token() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
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
        NEW.participant_block_token := chronicle_participant_block_token(NEW.study_id, NEW.participant_id);
    ELSIF TG_OP = 'UPDATE' AND NEW.participant_id IS NULL THEN
        NEW.participant_block_token := OLD.participant_block_token;
    END IF;
    RETURN NEW;
END;
$$;

-- chronicle_stop_withdrawn_participant_collection()
CREATE FUNCTION public.chronicle_stop_withdrawn_participant_collection() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.participation_status = 'ENROLLED' AND EXISTS (
        SELECT 1 FROM mobile_withdrawal_requests
        WHERE study_id = NEW.study_id AND participant_id = NEW.participant_id
    ) THEN
        RAISE EXCEPTION 'A withdrawn participant cannot resume collection' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- chronicle_sync_job_scope()
CREATE FUNCTION public.chronicle_sync_job_scope() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
DECLARE
    raw_study_id TEXT;
    participant_values JSONB;
BEGIN
    raw_study_id := NULLIF(btrim(NEW.definition ->> 'studyId'), '');
    IF raw_study_id IS NULL THEN
        NEW.study_id := NULL;
    ELSE
        BEGIN
            NEW.study_id := raw_study_id::UUID;
        EXCEPTION WHEN invalid_text_representation THEN
            RAISE EXCEPTION 'Job definition contains an invalid studyId'
                USING ERRCODE = '23514';
        END;
    END IF;

    participant_values := NEW.definition -> 'participantIds';
    IF participant_values IS NOT NULL THEN
        IF jsonb_typeof(participant_values) <> 'array'
           OR EXISTS (
               SELECT 1
               FROM jsonb_array_elements(participant_values) AS element(value)
               WHERE jsonb_typeof(element.value) <> 'string'
                  OR btrim(element.value #>> '{}') = ''
           )
        THEN
            RAISE EXCEPTION 'Job definition participantIds must be an array of non-empty strings'
                USING ERRCODE = '23514';
        END IF;
        NEW.participant_ids := ARRAY(
            SELECT element.value #>> '{}'
            FROM jsonb_array_elements(participant_values) WITH ORDINALITY AS element(value, ordinal)
            ORDER BY element.ordinal
        );
    ELSIF NEW.definition -> 'participantId' IS NOT NULL THEN
        IF jsonb_typeof(NEW.definition -> 'participantId') <> 'string'
           OR btrim(NEW.definition ->> 'participantId') = ''
        THEN
            RAISE EXCEPTION 'Job definition participantId must be a non-empty string'
                USING ERRCODE = '23514';
        END IF;
        NEW.participant_ids := ARRAY[NEW.definition ->> 'participantId'];
    ELSE
        NEW.participant_ids := ARRAY[]::TEXT[];
    END IF;
    RETURN NEW;
END
$$;

-- chronicle_validate_job_status_transition()
CREATE FUNCTION public.chronicle_validate_job_status_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF (OLD.status = 'PENDING' AND NEW.status IN ('RUNNING', 'PAUSED', 'CANCELED'))
        OR (OLD.status = 'PAUSED' AND NEW.status IN ('PENDING', 'RUNNING', 'CANCELED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('PAUSED', 'STOPPING', 'FINISHED', 'CANCELED'))
        OR (OLD.status = 'STOPPING' AND NEW.status IN ('FINISHED', 'CANCELED')) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Invalid Chronicle job status transition: % -> %', OLD.status, NEW.status
        USING ERRCODE = '23514';
END
$$;

-- forward_legacy_audit_buffer_insert()
CREATE FUNCTION public.forward_legacy_audit_buffer_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO public.audit (
        acl_key, id, principal_type, principal_id, audit_event_type,
        study_id, organization_id, description, data, event_timestamp,
        event_id, client_ip_ref, user_agent, request_path, request_method
    ) VALUES (
        NEW.acl_key, NEW.id, NEW.principal_type, NEW.principal_id, NEW.audit_event_type,
        NEW.study_id, NEW.organization_id, NEW.description, NEW.data, NEW.event_timestamp,
        NEW.event_id, NEW.client_ip_ref, NEW.user_agent, NEW.request_path, NEW.request_method
    ) ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;

-- prevent_audit_modification()
CREATE FUNCTION public.prevent_audit_modification() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are immutable. DELETE and UPDATE are not permitted';
    RETURN NULL;
END;
$$;

-- record_data_collection_settings_revision()
CREATE FUNCTION public.record_data_collection_settings_revision() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'pg_temp'
    AS $_$
DECLARE
    candidate JSONB;
    candidate_version INTEGER;
BEGIN
    IF TG_RELID IS DISTINCT FROM 'public.studies'::pg_catalog.regclass
       OR TG_OP NOT IN ('INSERT', 'UPDATE') THEN
        RAISE EXCEPTION
            'DataCollection revision writer may execute only from the public.studies settings trigger';
    END IF;

    candidate := NEW.settings -> 'DataCollection';
    IF candidate IS NULL OR jsonb_typeof(candidate) <> 'object' THEN
        RETURN NEW;
    END IF;
    IF COALESCE(candidate ->> 'settingsVersion', '') !~ '^[1-9][0-9]*$' THEN
        RAISE EXCEPTION 'DataCollection settingsVersion must be a positive server-issued revision';
    END IF;
    candidate_version := (candidate ->> 'settingsVersion')::INTEGER;

    INSERT INTO data_collection_settings_revisions (study_id, settings_version, setting, study_modules)
    VALUES (NEW.study_id, candidate_version, candidate, COALESCE(NEW.modules, '{}'::jsonb))
    ON CONFLICT (study_id, settings_version) DO NOTHING;

    IF NOT FOUND AND NOT EXISTS (
        SELECT 1
        FROM data_collection_settings_revisions
        WHERE study_id = NEW.study_id
          AND settings_version = candidate_version
          AND setting = candidate
    ) THEN
        RAISE EXCEPTION 'DataCollection revision % for study % already has different immutable evidence',
            candidate_version, NEW.study_id
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$_$;


-- Framework tables

-- activity_recognition_events
ALTER TABLE ONLY public.activity_recognition_events FORCE ROW LEVEL SECURITY;


-- android_device_sensor_availability
ALTER TABLE ONLY public.android_device_sensor_availability FORCE ROW LEVEL SECURITY;

-- android_sensor_data
ALTER TABLE ONLY public.android_sensor_data FORCE ROW LEVEL SECURITY;

-- api_keys_mobile_fields_consistent
ALTER TABLE public.api_keys ADD CONSTRAINT api_keys_mobile_fields_consistent CHECK (((participant_id IS NULL) = (random_device_id IS NULL)));
ALTER TABLE ONLY public.api_keys FORCE ROW LEVEL SECURITY;

-- app_audio_activity
ALTER TABLE ONLY public.app_audio_activity FORCE ROW LEVEL SECURITY;

-- app_audio_content
ALTER TABLE ONLY public.app_audio_content FORCE ROW LEVEL SECURITY;

-- app_network_usage
ALTER TABLE ONLY public.app_network_usage FORCE ROW LEVEL SECURITY;

-- app_usage_survey
ALTER TABLE ONLY public.app_usage_survey FORCE ROW LEVEL SECURITY;

-- audit
ALTER TABLE ONLY public.audit FORCE ROW LEVEL SECURITY;

-- Keep historical battery provenance unknown rather than attributing it to the current device/app.
ALTER TABLE public.battery_telemetry ADD COLUMN IF NOT EXISTS random_device_id uuid;
ALTER TABLE public.battery_telemetry ADD COLUMN IF NOT EXISTS device_type text;
ALTER TABLE public.battery_telemetry ADD COLUMN IF NOT EXISTS producer_version text;
ALTER TABLE public.battery_telemetry ADD CONSTRAINT battery_telemetry_producer_version_check CHECK (producer_version IS NULL OR (char_length(producer_version) <= 64 AND producer_version ~ ('[' || chr(92) || 's' || chr(92) || 'S]*[^' || chr(92) || 'u0009-' || chr(92) || 'u000D' || chr(92) || 'u0020' || chr(92) || 'u0085' || chr(92) || 'u00A0' || chr(92) || 'u1680' || chr(92) || 'u2000-' || chr(92) || 'u200A' || chr(92) || 'u2028' || chr(92) || 'u2029' || chr(92) || 'u202F' || chr(92) || 'u205F' || chr(92) || 'u3000][' || chr(92) || 's' || chr(92) || 'S]*')));

-- battery_telemetry
ALTER TABLE ONLY public.battery_telemetry FORCE ROW LEVEL SECURITY;

-- candidates
ALTER TABLE ONLY public.candidates FORCE ROW LEVEL SECURITY;

-- chronicle_usage_events
-- chronicle_usage_events_identity owns logical event identity. Events differing only in activity_class
-- are distinct; NULL equals NULL, so two identical classless events in one upload store once.
ALTER TABLE public.chronicle_usage_events
    ALTER COLUMN app_package_name SET NOT NULL,
    ALTER COLUMN interaction_type SET NOT NULL,
    ALTER COLUMN event_timestamp SET NOT NULL,
    ALTER COLUMN timezone SET NOT NULL,
    ALTER COLUMN username SET NOT NULL,
    ALTER COLUMN application_label SET NOT NULL,
    ALTER COLUMN uploaded_at SET NOT NULL;
ALTER TABLE public.chronicle_usage_events ADD CONSTRAINT chronicle_usage_events_identity UNIQUE NULLS NOT DISTINCT (study_id, participant_id, app_package_name, activity_class, interaction_type, event_type, event_timestamp, timezone, username, application_label, uploaded_at);
ALTER TABLE ONLY public.chronicle_usage_events FORCE ROW LEVEL SECURITY;

-- chronicle_usage_stats
ALTER TABLE ONLY public.chronicle_usage_stats FORCE ROW LEVEL SECURITY;

-- connectivity_state_events
ALTER TABLE ONLY public.connectivity_state_events FORCE ROW LEVEL SECURITY;

-- device_settings
ALTER TABLE ONLY public.device_settings FORCE ROW LEVEL SECURITY;

-- devices
ALTER TABLE ONLY public.devices FORCE ROW LEVEL SECURITY;

-- filtered_apps
ALTER TABLE ONLY public.filtered_apps FORCE ROW LEVEL SECURITY;

-- health_metrics
ALTER TABLE ONLY public.health_metrics FORCE ROW LEVEL SECURITY;

-- interaction_events
ALTER TABLE ONLY public.interaction_events FORCE ROW LEVEL SECURITY;

-- ios_screen_time_usage
-- One row per capture of one app/domain window. NULL labels are equal in the key, so a retried capture
-- conflicts and writes nothing (ScreenTimeUsageCaptures.CAPTURE_KEY).
-- Length-prefixed nullable labels preserve field boundaries without putting unbounded text in a btree.
ALTER TABLE public.ios_screen_time_usage ADD COLUMN capture_labels_hash bytea GENERATED ALWAYS AS (
    public.digest(
        coalesce(length(app_name)::text || ':' || app_name, '-1:') ||
        coalesce(length(bundle_identifier)::text || ':' || bundle_identifier, '-1:') ||
        coalesce(length(web_domain)::text || ':' || web_domain, '-1:') ||
        coalesce(length(category_name)::text || ':' || category_name, '-1:'),
        'sha256'
    )
) STORED;
ALTER TABLE public.ios_screen_time_usage ADD CONSTRAINT ios_screen_time_usage_capture_key UNIQUE NULLS NOT DISTINCT (study_id, participant_id, random_device_id, row_kind, capture_labels_hash, observation_start, observation_end, captured_at);
ALTER TABLE public.ios_screen_time_usage ADD CONSTRAINT ios_screen_time_usage_observation_range_chk CHECK ((observation_end > observation_start));
ALTER TABLE public.ios_screen_time_usage ADD CONSTRAINT ios_screen_time_usage_counts_chk CHECK (((duration_seconds >= 0) AND ((notification_count IS NULL) OR (notification_count >= 0)) AND ((pickup_count IS NULL) OR (pickup_count >= 0))));
ALTER TABLE ONLY public.ios_screen_time_usage FORCE ROW LEVEL SECURITY;

-- jobs_terminal_time_chk
ALTER TABLE public.jobs ALTER COLUMN status SET NOT NULL;
ALTER TABLE public.jobs ADD COLUMN lease_token uuid;
ALTER TABLE public.jobs ADD COLUMN lease_expires_at timestamp with time zone;
ALTER TABLE public.jobs ADD COLUMN study_id uuid;
ALTER TABLE public.jobs ADD COLUMN participant_ids text[] DEFAULT ARRAY[]::text[] NOT NULL;
ALTER TABLE public.jobs ADD CONSTRAINT jobs_lease_state_chk CHECK ((((status = 'RUNNING'::text) AND (lease_token IS NOT NULL) AND (lease_expires_at IS NOT NULL) AND isfinite(lease_expires_at) AND (lease_expires_at > updated_at)) OR ((status <> 'RUNNING'::text) AND (lease_token IS NULL) AND (lease_expires_at IS NULL))));
ALTER TABLE public.jobs ADD CONSTRAINT jobs_status_valid_chk CHECK ((status IN (
        'PAUSED',
        'PENDING',
        'FINISHED',
        'RUNNING',
        'STOPPING',
        'CANCELED'
    )));
ALTER TABLE public.jobs ADD CONSTRAINT jobs_terminal_time_chk CHECK ((((status IN (
        'FINISHED',
        'CANCELED'
    )) AND isfinite(completed_at)) OR ((status <> ALL (ARRAY['FINISHED'::text,
        'CANCELED'::text])) AND (NOT isfinite(completed_at)))));
ALTER TABLE ONLY public.jobs FORCE ROW LEVEL SECURITY;

-- notification_activity
ALTER TABLE ONLY public.notification_activity FORCE ROW LEVEL SECURITY;

-- notifications
ALTER TABLE ONLY public.notifications FORCE ROW LEVEL SECURITY;

-- organization_studies
ALTER TABLE ONLY public.organization_studies FORCE ROW LEVEL SECURITY;

-- participant_collection_ack_unavailable_modules_array_check
ALTER TABLE public.participant_collection_acknowledgment ADD CONSTRAINT participant_collection_ack_disclosure_pair_check CHECK ((((disclosure_version IS NULL) AND (manifest_digest IS NULL)) OR ((disclosure_version IS NOT NULL) AND (manifest_digest IS NOT NULL) AND (length(btrim(disclosure_version)) > 0) AND (manifest_digest ~ '^[0-9a-f]{64}$'::text))));
ALTER TABLE public.participant_collection_acknowledgment ADD CONSTRAINT participant_collection_ack_evidence_pair_check CHECK (((evidence_access_code_id IS NULL) = (evidence_api_key_id IS NULL)));
ALTER TABLE public.participant_collection_acknowledgment ADD CONSTRAINT participant_collection_ack_decision_seq_check CHECK ((decision_seq > 0));
ALTER TABLE public.participant_collection_acknowledgment ADD CONSTRAINT participant_collection_ack_unavailable_modules_array_check CHECK ((jsonb_typeof(unavailable_modules) = 'array'::text));
ALTER TABLE ONLY public.participant_collection_acknowledgment FORCE ROW LEVEL SECURITY;

-- participant_stats
ALTER TABLE ONLY public.participant_stats FORCE ROW LEVEL SECURITY;

-- questionnaire_submissions
ALTER TABLE ONLY public.questionnaire_submissions FORCE ROW LEVEL SECURITY;

-- questionnaires
ALTER TABLE ONLY public.questionnaires FORCE ROW LEVEL SECURITY;

-- securable_objects
ALTER TABLE public.securable_objects ADD COLUMN acl_owner_fence bigint DEFAULT 0 NOT NULL;

-- sleep_events
ALTER TABLE ONLY public.sleep_events FORCE ROW LEVEL SECURITY;

-- Bean Validation counts UTF-16 code units, including two units for supplementary characters.
-- @NotBlank uses Java Character.isWhitespace; PostgreSQL character classes depend on database locale.
ALTER TABLE public.studies ALTER COLUMN title SET NOT NULL;
ALTER TABLE public.studies ADD CONSTRAINT studies_title_format CHECK (title ~ ('[^' || chr(9) || '-' || chr(13) || chr(28) || '-' || chr(32) || chr(5760) || chr(8192) || '-' || chr(8198) || chr(8200) || '-' || chr(8202) || chr(8232) || chr(8233) || chr(8287) || chr(12288) || ']') AND length(regexp_replace(title, ('[' || chr(65536) || '-' || chr(1114111) || ']'), 'xx', 'g')) <= 2000);
ALTER TABLE public.organizations ALTER COLUMN title SET NOT NULL;
ALTER TABLE public.organizations ADD CONSTRAINT organizations_title_format CHECK (title ~ ('[^' || chr(9) || '-' || chr(13) || chr(28) || '-' || chr(32) || chr(5760) || chr(8192) || '-' || chr(8198) || chr(8200) || '-' || chr(8202) || chr(8232) || chr(8233) || chr(8287) || chr(12288) || ']') AND length(regexp_replace(title, ('[' || chr(65536) || '-' || chr(1114111) || ']'), 'xx', 'g')) <= 255);

-- studies
ALTER TABLE public.studies ADD COLUMN settings_revision bigint DEFAULT 0 NOT NULL;
ALTER TABLE public.studies ADD COLUMN disclosure_generation uuid DEFAULT gen_random_uuid() NOT NULL;
ALTER TABLE public.studies ADD COLUMN collection_closed_at timestamp with time zone;
-- Every past archive interval: captures made inside one stay refused after the study reopens.
ALTER TABLE public.studies ADD COLUMN closed_capture_windows tstzmultirange DEFAULT '{}'::tstzmultirange NOT NULL;
ALTER TABLE public.studies ADD CONSTRAINT studies_collection_cutoff_finite CHECK (collection_closed_at IS NULL OR isfinite(collection_closed_at));
ALTER TABLE public.studies ADD CONSTRAINT studies_lifecycle_status_check CHECK ((lifecycle_status IN (
        'ACTIVE',
        'ARCHIVED',
        'SCHEDULED_FOR_DELETION'
    )));

-- study_deletion_schedule_previous_status_check
ALTER TABLE public.study_deletion_schedule ADD CONSTRAINT study_deletion_schedule_previous_status_check CHECK (((previous_status IS NULL) OR (previous_status IN (
        'ACTIVE',
        'ARCHIVED'
    ))));
ALTER TABLE ONLY public.study_deletion_schedule FORCE ROW LEVEL SECURITY;

-- study_lifecycle_events
ALTER TABLE ONLY public.study_lifecycle_events FORCE ROW LEVEL SECURITY;

-- study_limits
ALTER TABLE ONLY public.study_limits FORCE ROW LEVEL SECURITY;

-- study_participants_participation_status_check
ALTER TABLE public.study_participants ADD COLUMN capture_cutoff timestamp with time zone;
-- Every past pause or completion interval: captures made inside one stay refused after a resume.
ALTER TABLE public.study_participants ADD COLUMN closed_capture_windows tstzmultirange DEFAULT '{}'::tstzmultirange NOT NULL;
-- Advanced by record_capture_cutoff on every status change and by the processing block/unblock writer;
-- a researcher's status or processing-block change must name the revision it saw.
ALTER TABLE public.study_participants ADD COLUMN status_revision bigint DEFAULT 0 NOT NULL;
-- Owned by record_capture_cutoff: the count of hard stops and when the latest began. The status poll publishes both.
ALTER TABLE public.study_participants ADD COLUMN hard_stop_generation bigint DEFAULT 0 NOT NULL;
ALTER TABLE public.study_participants ADD COLUMN hard_stopped_at timestamp with time zone;
ALTER TABLE public.study_participants ADD COLUMN processing_blocked_at timestamp with time zone;
ALTER TABLE public.study_participants ADD COLUMN processing_block_reason text;
ALTER TABLE public.study_participants ADD COLUMN processing_blocked_by text;
ALTER TABLE public.study_participants ADD CONSTRAINT participant_processing_block_complete CHECK (
    (processing_blocked_at IS NULL AND processing_block_reason IS NULL AND processing_blocked_by IS NULL)
    OR (processing_blocked_at IS NOT NULL AND isfinite(processing_blocked_at)
        AND processing_block_reason IS NOT NULL AND btrim(processing_block_reason) <> ''
        AND processing_blocked_by IS NOT NULL AND btrim(processing_blocked_by) <> '')
);
ALTER TABLE public.study_participants ADD CONSTRAINT study_participants_participant_id_format CHECK (participant_id ~ '^(?![.]{1,2}$)[A-Za-z0-9_.-]{1,255}$');
ALTER TABLE public.study_participants ADD CONSTRAINT study_participants_participation_status_check CHECK ((participation_status IN (
        'ENROLLED',
        'NOT_ENROLLED',
        'PAUSED',
        'COLLECTION_COMPLETED',
        'UNKNOWN'
    )));
ALTER TABLE ONLY public.study_participants FORCE ROW LEVEL SECURITY;

-- study_settings_audit
ALTER TABLE ONLY public.study_settings_audit FORCE ROW LEVEL SECURITY;

-- time_use_diary_submissions
ALTER TABLE ONLY public.time_use_diary_submissions FORCE ROW LEVEL SECURITY;

-- time_use_diary_summarized
ALTER TABLE ONLY public.time_use_diary_summarized FORCE ROW LEVEL SECURITY;

ALTER TABLE public.upload_buffer ADD CONSTRAINT upload_buffer_upload_type_check CHECK (upload_type IN ('Android', 'AndroidSensor', 'AndroidSensorRejected'));
-- upload_buffer
ALTER TABLE ONLY public.upload_buffer FORCE ROW LEVEL SECURITY;

-- Service tables

-- data_collection_settings_revisions
CREATE TABLE public.data_collection_settings_revisions (
    study_modules jsonb DEFAULT '{}'::jsonb NOT NULL,
    study_id uuid NOT NULL,
    settings_version integer NOT NULL,
    setting jsonb NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_collection_settings_revisions_check CHECK (((jsonb_typeof(setting) = 'object'::text) AND (setting ? 'settingsVersion'::text) AND (((setting ->> 'settingsVersion'::text))::integer = settings_version))),
    CONSTRAINT data_collection_settings_revisions_settings_version_check CHECK ((settings_version > 0))
);

ALTER TABLE ONLY public.data_collection_settings_revisions FORCE ROW LEVEL SECURITY;

-- data_deletion_audit_outbox
CREATE TABLE public.data_deletion_audit_outbox (
    event_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    study_id uuid NOT NULL,
    event_type text NOT NULL,
    actor text NOT NULL,
    description text NOT NULL,
    event_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    event_timestamp timestamp with time zone DEFAULT now() NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    publish_attempt_count integer DEFAULT 0 NOT NULL,
    lease_token uuid,
    lease_expires_at timestamp with time zone,
    published_at timestamp with time zone,
    last_error_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_deletion_audit_outbox_actor_check CHECK ((btrim(actor) <> ''::text)),
    CONSTRAINT data_deletion_audit_outbox_check CHECK ((((lease_token IS NULL) AND (lease_expires_at IS NULL)) OR ((lease_token IS NOT NULL) AND (lease_expires_at IS NOT NULL) AND (published_at IS NULL)))),
    CONSTRAINT data_deletion_audit_outbox_check1 CHECK (((published_at IS NULL) OR ((lease_token IS NULL) AND (lease_expires_at IS NULL)))),
    CONSTRAINT data_deletion_audit_outbox_description_check CHECK ((btrim(description) <> ''::text)),
    CONSTRAINT data_deletion_audit_outbox_event_data_check CHECK ((jsonb_typeof(event_data) = 'object'::text)),
    CONSTRAINT data_deletion_audit_outbox_event_type_check CHECK ((event_type IN (
        'CANCEL_DATA_DELETION',
        'START_DATA_DELETION',
        'UPDATE_PARTICIPATION_STATUS'
    ))),
    CONSTRAINT data_deletion_audit_outbox_publish_attempt_count_check CHECK ((publish_attempt_count >= 0))
);

ALTER TABLE ONLY public.data_deletion_audit_outbox FORCE ROW LEVEL SECURITY;

-- data_deletion_form_access_revocations
CREATE TABLE public.data_deletion_form_access_revocations (
    operation_id uuid NOT NULL,
    study_id uuid NOT NULL,
    resource_kind text NOT NULL,
    resource_id uuid NOT NULL,
    original_revoked_at timestamp with time zone,
    revoked_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_deletion_form_access_revocations_resource_kind_check CHECK ((resource_kind IN ('ACCESS_CODE', 'SESSION')))
);

ALTER TABLE ONLY public.data_deletion_form_access_revocations FORCE ROW LEVEL SECURITY;

-- data_deletion_operations
CREATE TABLE public.data_deletion_operations (
    operation_id uuid NOT NULL,
    study_id uuid NOT NULL,
    participant_ref text,
    participant_id text,
    mode text NOT NULL,
    status text NOT NULL,
    requested_by text NOT NULL,
    idempotency_key uuid NOT NULL,
    registry_version integer NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    proof_hash text,
    failure_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    operation_attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    participant_block_token text,
    worker_lease_token uuid,
    worker_lease_expires_at timestamp with time zone,
    cancelled_by text,
    cancelled_at timestamp with time zone,
    CONSTRAINT data_deletion_operations_cancellation_actor_check CHECK ((((status = 'CANCELLED'::text) AND (cancelled_by IS NOT NULL) AND (btrim(cancelled_by) <> ''::text) AND (cancelled_at IS NOT NULL)) OR ((status <> 'CANCELLED'::text) AND (cancelled_by IS NULL) AND (cancelled_at IS NULL)))),
    CONSTRAINT data_deletion_operations_mode_check CHECK ((mode IN ('COLLECTED_DATA_PURGE', 'WITHDRAW_AND_ERASE', 'STUDY_ERASURE'))),
    CONSTRAINT data_deletion_operations_status_check CHECK ((status IN (
        'PREVIEW',
        'QUARANTINED',
        'ERASING',
        'VERIFYING',
        'COMPLETED',
        'FAILED',
        'CANCELLED'
    ))),
    CONSTRAINT data_deletion_worker_lease_state_chk CHECK ((((status IN (
        'ERASING',
        'VERIFYING'
    )) AND (worker_lease_token IS NOT NULL) AND (worker_lease_expires_at IS NOT NULL) AND isfinite(worker_lease_expires_at) AND (worker_lease_expires_at > updated_at)) OR ((status <> ALL (ARRAY['ERASING'::text,
        'VERIFYING'::text])) AND (worker_lease_token IS NULL) AND (worker_lease_expires_at IS NULL))))
);

ALTER TABLE ONLY public.data_deletion_operations FORCE ROW LEVEL SECURITY;

-- data_deletion_steps
CREATE TABLE public.data_deletion_steps (
    operation_id uuid NOT NULL,
    study_id uuid NOT NULL,
    asset_id text NOT NULL,
    ordinal integer NOT NULL,
    status text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    expected_rows bigint,
    deleted_rows bigint,
    residual_rows bigint,
    error_code text,
    last_attempt_at timestamp with time zone,
    verified_at timestamp with time zone,
    CONSTRAINT data_deletion_steps_status_check CHECK ((status IN ('PENDING', 'RUNNING', 'VERIFIED', 'FAILED')))
);

ALTER TABLE ONLY public.data_deletion_steps FORCE ROW LEVEL SECURITY;

-- data_deletion_tombstones
CREATE TABLE public.data_deletion_tombstones (
    operation_id uuid NOT NULL,
    study_ref text NOT NULL,
    participant_ref text,
    mode text NOT NULL,
    registry_version integer NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    proof_hash text NOT NULL
);

-- cancelled_enrollment_attempts
CREATE TABLE public.cancelled_enrollment_attempts (
    attempt_id uuid NOT NULL,
    cancelled_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.cancelled_enrollment_attempts FORCE ROW LEVEL SECURITY;

-- erased_device_key_tombstones
CREATE TABLE public.erased_device_key_tombstones (
    key_hash text NOT NULL,
    kind text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT erased_device_key_tombstones_kind_check CHECK ((kind IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')))
);

ALTER TABLE ONLY public.erased_device_key_tombstones FORCE ROW LEVEL SECURITY;

-- participant_form_access_codes
CREATE TABLE public.participant_form_access_codes (
    access_code_id uuid NOT NULL,
    token_hash bytea NOT NULL,
    study_id uuid NOT NULL,
    participant_id text NOT NULL,
    form_kind text NOT NULL,
    resource_id uuid,
    logical_date date,
    issuer_type text NOT NULL,
    issued_by text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    exchanged_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    enrollment_attempt_id uuid,
    enrollment_source_device_hash text,
    enrollment_random_device_id uuid,
    enrollment_manifest_digest text,
    enrollment_request_hash text,
    enrollment_proposed_key_hash text,
    enrollment_replay_expires_at timestamp with time zone,
    enrollment_settings_version integer,
    enrollment_disclosure_version text,
    enrollment_enabled_modules jsonb,
    enrollment_required_modules jsonb,
    CONSTRAINT participant_form_access_codes_enrollment_evidence_complete CHECK ((((enrollment_settings_version IS NULL) AND (enrollment_disclosure_version IS NULL) AND (enrollment_enabled_modules IS NULL) AND (enrollment_required_modules IS NULL)) OR ((enrollment_attempt_id IS NOT NULL) AND (enrollment_settings_version IS NOT NULL) AND (enrollment_disclosure_version IS NOT NULL) AND (enrollment_enabled_modules IS NOT NULL) AND (enrollment_required_modules IS NOT NULL) AND (enrollment_settings_version > 0) AND (length(btrim(enrollment_disclosure_version)) > 0) AND (jsonb_typeof(enrollment_enabled_modules) = 'array'::text) AND (jsonb_typeof(enrollment_required_modules) = 'array'::text) AND (enrollment_enabled_modules @> enrollment_required_modules)))),
    CONSTRAINT participant_form_access_codes_enrollment_receipt_complete CHECK ((((enrollment_attempt_id IS NULL) AND (enrollment_source_device_hash IS NULL) AND (enrollment_random_device_id IS NULL) AND (enrollment_manifest_digest IS NULL) AND (enrollment_request_hash IS NULL) AND (enrollment_proposed_key_hash IS NULL) AND (enrollment_replay_expires_at IS NULL)) OR ((form_kind = 'ENROLLMENT'::text) AND (exchanged_at IS NOT NULL) AND (enrollment_attempt_id IS NOT NULL) AND (enrollment_random_device_id IS NOT NULL) AND (enrollment_replay_expires_at IS NOT NULL) AND (enrollment_replay_expires_at > exchanged_at) AND (enrollment_source_device_hash ~ '^[0-9a-f]{64}$'::text) AND (enrollment_manifest_digest ~ '^[0-9a-f]{64}$'::text) AND (enrollment_request_hash ~ '^[0-9a-f]{64}$'::text) AND (enrollment_proposed_key_hash ~ '^[0-9a-f]{64}$'::text)))),
    CONSTRAINT participant_form_access_codes_form_kind_check CHECK ((form_kind IN (
        'APP_USAGE',
        'QUESTIONNAIRE',
        'TIME_USE_DIARY',
        'PORTAL',
        'ENROLLMENT'
    ))),
    CONSTRAINT participant_form_access_codes_issuer_type_check CHECK ((issuer_type IN ('DEVICE', 'RESEARCHER')))
);

ALTER TABLE ONLY public.participant_form_access_codes FORCE ROW LEVEL SECURITY;

-- jwt_session_state
CREATE TABLE public.jwt_session_state (
    state_group text NOT NULL,
    state_key text NOT NULL,
    state_value bigint NOT NULL,
    expires_at timestamp with time zone
);

-- mobile_withdrawal_requests
CREATE TABLE public.mobile_withdrawal_requests (
    request_id uuid NOT NULL,
    api_key_id uuid NOT NULL,
    study_id uuid NOT NULL,
    participant_id text NOT NULL,
    random_device_id uuid NOT NULL,
    already_withdrawn boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.mobile_withdrawal_requests FORCE ROW LEVEL SECURITY;

-- participant_form_sessions
CREATE TABLE public.participant_form_sessions (
    session_id uuid NOT NULL,
    session_hash bytea NOT NULL,
    access_code_id uuid NOT NULL,
    study_id uuid NOT NULL,
    participant_id text NOT NULL,
    form_kind text NOT NULL,
    resource_id uuid,
    logical_date date,
    csrf_hash bytea NOT NULL,
    idle_expires_at timestamp with time zone NOT NULL,
    absolute_expires_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.participant_form_sessions FORCE ROW LEVEL SECURITY;

CREATE UNIQUE INDEX participant_researcher_live_code ON public.participant_form_access_codes
    (study_id, participant_id, form_kind, resource_id, logical_date) NULLS NOT DISTINCT
    WHERE issuer_type = 'RESEARCHER' AND revoked_at IS NULL AND exchanged_at IS NULL;

-- participant_form_submission_receipts
CREATE TABLE public.participant_form_submission_receipts (
    receipt_id uuid NOT NULL,
    access_code_id uuid NOT NULL,
    study_id uuid NOT NULL,
    participant_id text NOT NULL,
    form_kind text NOT NULL,
    resource_key text NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash bytea NOT NULL,
    status text NOT NULL,
    submission_id uuid,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT participant_form_submission_receipts_status_check CHECK ((status IN ('PROCESSING', 'COMPLETED')))
);

ALTER TABLE ONLY public.participant_form_submission_receipts FORCE ROW LEVEL SECURITY;

-- participant_purge_cutoffs
CREATE TABLE public.participant_purge_cutoffs (
    study_id uuid NOT NULL,
    participant_block_token text NOT NULL,
    cutoff timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.participant_purge_cutoffs FORCE ROW LEVEL SECURITY;

-- researcher_create_receipts
CREATE TABLE public.researcher_create_receipts (
    study_id uuid,
    principal_id text NOT NULL,
    owner_kind text DEFAULT 'LEGACY'::text NOT NULL,
    route text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash bytea NOT NULL,
    response_status integer,
    response_type text,
    sealed_response text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT researcher_create_receipts_idempotency_key_check CHECK (((length(idempotency_key) >= 1) AND (length(idempotency_key) <= 128))),
    CONSTRAINT researcher_create_receipts_request_hash_check CHECK ((octet_length(request_hash) = 32)),
    CONSTRAINT researcher_create_receipts_owner_kind_check CHECK ((owner_kind IN ('LEGACY', 'USER', 'API_KEY')))
);

ALTER TABLE ONLY public.researcher_create_receipts FORCE ROW LEVEL SECURITY;





-- restore_continuity_reconciliations
CREATE TABLE public.restore_continuity_reconciliations (
    checkpoint_id uuid NOT NULL,
    contract_version integer NOT NULL,
    source_schema_version text CONSTRAINT restore_continuity_reconciliatio_source_schema_version_not_null NOT NULL,
    checkpoint_sha256 text NOT NULL,
    withdrawal_receipt_count bigint CONSTRAINT restore_continuity_reconcilia_withdrawal_receipt_count_not_null NOT NULL,
    revoked_api_key_count bigint CONSTRAINT restore_continuity_reconciliatio_revoked_api_key_count_not_null NOT NULL,
    withdrawn_participant_count bigint CONSTRAINT restore_continuity_reconcil_withdrawn_participant_coun_not_null NOT NULL,
    deletion_operation_count bigint CONSTRAINT restore_continuity_reconcilia_deletion_operation_count_not_null NOT NULL,
    source_tombstone_count bigint CONSTRAINT restore_continuity_reconciliati_source_tombstone_count_not_null NOT NULL,
    already_protected_deletion_count bigint CONSTRAINT restore_continuity_reconcil_already_protected_deletion_not_null NOT NULL,
    replayed_completed_deletion_count bigint CONSTRAINT restore_continuity_reconcil_replayed_completed_deletio_not_null NOT NULL,
    reconciled_at timestamp with time zone DEFAULT now() NOT NULL,
    collection_revision_count bigint DEFAULT 0 CONSTRAINT restore_continuity_reconcili_collection_revision_count_not_null NOT NULL,
    published_collection_settings_count bigint DEFAULT 0 CONSTRAINT restore_continuity_reconcil_published_collection_setti_not_null NOT NULL,
    enrollment_invitation_count bigint DEFAULT 0 CONSTRAINT restore_continuity_reconcil_enrollment_invitation_coun_not_null NOT NULL,
    restricted_participant_count bigint DEFAULT 0 CONSTRAINT restore_continuity_reconcil_restricted_participant_cou_not_null NOT NULL,
    archived_study_count bigint DEFAULT 0 CONSTRAINT restore_continuity_reconciliation_archived_study_count_not_null NOT NULL,
    skipped_authority_counts jsonb DEFAULT '{}'::jsonb CONSTRAINT restore_continuity_reconcilia_skipped_authority_counts_not_null NOT NULL,
    CONSTRAINT restore_continuity_reconcili_already_protected_deletion_c_check CHECK ((already_protected_deletion_count >= 0)),
    CONSTRAINT restore_continuity_reconcili_published_collection_setting_check CHECK ((published_collection_settings_count >= 0)),
    CONSTRAINT restore_continuity_reconcili_replayed_completed_deletion__check CHECK ((replayed_completed_deletion_count >= 0)),
    CONSTRAINT restore_continuity_reconcili_restricted_participant_count_check CHECK ((restricted_participant_count >= 0)),
    CONSTRAINT restore_continuity_reconcilia_enrollment_invitation_count_check CHECK ((enrollment_invitation_count >= 0)),
    CONSTRAINT restore_continuity_reconcilia_withdrawn_participant_count_check CHECK ((withdrawn_participant_count >= 0)),
    CONSTRAINT restore_continuity_reconciliati_collection_revision_count_check CHECK ((collection_revision_count >= 0)),
    CONSTRAINT restore_continuity_reconciliatio_deletion_operation_count_check CHECK ((deletion_operation_count >= 0)),
    CONSTRAINT restore_continuity_reconciliatio_skipped_authority_counts_check CHECK ((jsonb_typeof(skipped_authority_counts) = 'object'::text)),
    CONSTRAINT restore_continuity_reconciliatio_withdrawal_receipt_count_check CHECK ((withdrawal_receipt_count >= 0)),
    CONSTRAINT restore_continuity_reconciliations_archived_study_count_check CHECK ((archived_study_count >= 0)),
    CONSTRAINT restore_continuity_reconciliations_check CHECK (((already_protected_deletion_count + replayed_completed_deletion_count) = source_tombstone_count)),
    CONSTRAINT restore_continuity_reconciliations_checkpoint_sha256_check CHECK ((checkpoint_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT restore_continuity_reconciliations_contract_version_check CHECK ((contract_version = 8)),
    CONSTRAINT restore_continuity_reconciliations_revoked_api_key_count_check CHECK ((revoked_api_key_count >= 0)),
    CONSTRAINT restore_continuity_reconciliations_source_schema_version_check CHECK ((btrim(source_schema_version) <> ''::text)),
    CONSTRAINT restore_continuity_reconciliations_source_tombstone_count_check CHECK ((source_tombstone_count >= 0))
);

-- upload_diagnostic_erasures
CREATE TABLE public.upload_diagnostic_erasures (
    study_id uuid NOT NULL,
    participant_block_token text NOT NULL,
    event_id uuid NOT NULL
);

ALTER TABLE ONLY public.upload_diagnostic_erasures FORCE ROW LEVEL SECURITY;

-- upload_diagnostics
CREATE TABLE public.upload_diagnostics (
    study_id uuid NOT NULL,
    participant_id text NOT NULL,
    random_device_id uuid NOT NULL,
    event_id text NOT NULL,
    diagnostic_day date NOT NULL,
    module_family text NOT NULL,
    issue_code text NOT NULL,
    occurrence_count integer NOT NULL,
    first_occurred_at timestamp with time zone NOT NULL,
    last_occurred_at timestamp with time zone NOT NULL,
    http_status integer,
    error_type text,
    uploaded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT upload_diagnostics_check CHECK ((last_occurred_at >= first_occurred_at)),
    CONSTRAINT upload_diagnostics_error_type_check CHECK ((length(error_type) <= 128)),
    CONSTRAINT upload_diagnostics_http_status_check CHECK (((http_status >= 100) AND (http_status <= 599))),
    CONSTRAINT upload_diagnostics_issue_code_check CHECK ((issue_code IN (
        'DESTINATION_MISSING',
        'DESTINATION_IDENTITY_MISMATCH',
        'DESTINATION_SOURCE_DEVICE_MISSING',
        'DESTINATION_SETUP_INCOMPLETE',
        'DESTINATION_DISABLED',
        'DESTINATION_NONCANONICAL',
        'DESTINATION_CREDENTIAL_INCOMPLETE',
        'HTTP_SERVER_ERROR',
        'HTTP_CLIENT_ERROR',
        'TIMEOUT',
        'DNS_FAILURE',
        'TLS_FAILURE',
        'CONNECTION_FAILURE',
        'UPLOAD_FAILURE',
        'APP_CRASH',
        'APP_CRASH_NATIVE',
        'APP_ANR',
        'SAMPLE_QUARANTINED',
        'LOCAL_BUFFER_OVERFLOW',
        'LOCAL_REQUEUE_OVERFLOW',
        'LOCAL_WRITE_FAILED',
        'LOCAL_SHUTDOWN_DROPPED',
        'COLLECTION_GATE_DROPPED',
        'MODULE_POLICY_ERASED',
        'DIRECT_BOOT_CAPACITY_DROPPED',
        'DIRECT_BOOT_CORRUPT_RECORD',
        'COLLECTION_PAUSED_STORAGE',
        'COLLECTION_ACCESS_MISSING',
        'UNRECOGNIZED_ISSUE'
    ))),
    CONSTRAINT upload_diagnostics_module_family_check CHECK ((module_family IN (
        'USAGE_LIFECYCLE',
        'BATTERY',
        'DEVICE_TELEMETRY',
        'SENSOR',
        'APP_RUNTIME',
        'INTERACTION',
        'AUDIO_ACTIVITY',
        'AUDIO_CONTENT',
        'NOTIFICATION',
        'SLEEP',
        'ACTIVITY_RECOGNITION',
        'HEALTH',
        'CONNECTIVITY',
        'APP_NETWORK',
        'DEVICE_SETTINGS',
        'LOCAL_STORE'
    ))),
    CONSTRAINT upload_diagnostics_occurrence_count_check CHECK ((occurrence_count > 0))
);

ALTER TABLE ONLY public.upload_diagnostics FORCE ROW LEVEL SECURITY;

-- webhook_deliveries
CREATE TABLE public.webhook_deliveries (
    delivery_id uuid DEFAULT gen_random_uuid() NOT NULL,
    webhook_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    status integer DEFAULT 0 NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_attempt_at timestamp with time zone,
    response_body text,
    delivery_state text NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_expires_at timestamp with time zone,
    lease_token uuid,
    outcome_code text,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    study_id uuid NOT NULL,
    participant_id text,
    CONSTRAINT webhook_deliveries_completion_check CHECK ((((delivery_state IN (
        'SUCCEEDED',
        'FAILED'
    )) AND (completed_at IS NOT NULL)) OR ((delivery_state IN (
        'PENDING',
        'IN_FLIGHT'
    )) AND (completed_at IS NULL)))),
    CONSTRAINT webhook_deliveries_lease_check CHECK ((((delivery_state = 'IN_FLIGHT'::text) AND (lease_token IS NOT NULL) AND (lease_expires_at IS NOT NULL)) OR ((delivery_state <> 'IN_FLIGHT'::text) AND (lease_token IS NULL) AND (lease_expires_at IS NULL)))),
    CONSTRAINT webhook_deliveries_pending_event_type_check CHECK (((delivery_state IN (
        'SUCCEEDED',
        'FAILED'
    )) OR (event_type IN (
        'PARTICIPANT_ENROLLED',
        'DATA_SUBMITTED',
        'STUDY_STATUS_CHANGED'
    )))),
    CONSTRAINT webhook_deliveries_state_check CHECK ((delivery_state IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED')))
);

ALTER TABLE ONLY public.webhook_deliveries FORCE ROW LEVEL SECURITY;

-- webhook_registrations
CREATE TABLE public.webhook_registrations (
    webhook_id uuid DEFAULT gen_random_uuid() NOT NULL,
    study_id uuid NOT NULL,
    url text NOT NULL,
    secret_hash text DEFAULT ''::text NOT NULL,
    event_types text[] DEFAULT '{}'::text[] NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.webhook_registrations FORCE ROW LEVEL SECURITY;

-- Views

-- exchanged_enrollment_access_codes
CREATE VIEW public.exchanged_enrollment_access_codes WITH (security_invoker='true') AS
 SELECT access_code_id,
    token_hash,
    study_id,
    participant_id,
    form_kind,
    resource_id,
    logical_date,
    issuer_type,
    issued_by,
    expires_at,
    exchanged_at,
    revoked_at,
    created_at,
    enrollment_attempt_id,
    enrollment_source_device_hash,
    enrollment_random_device_id,
    enrollment_manifest_digest,
    enrollment_request_hash,
    enrollment_proposed_key_hash,
    enrollment_replay_expires_at,
    enrollment_settings_version,
    enrollment_disclosure_version,
    enrollment_enabled_modules,
    enrollment_required_modules
   FROM public.participant_form_access_codes code
  WHERE ((form_kind = 'ENROLLMENT'::text) AND (exchanged_at IS NOT NULL));

-- pending_enrollment_access_codes
CREATE VIEW public.pending_enrollment_access_codes WITH (security_invoker='true') AS
 SELECT access_code_id,
    token_hash,
    study_id,
    participant_id,
    form_kind,
    resource_id,
    logical_date,
    issuer_type,
    issued_by,
    expires_at,
    exchanged_at,
    revoked_at,
    created_at,
    enrollment_attempt_id,
    enrollment_source_device_hash,
    enrollment_random_device_id,
    enrollment_manifest_digest,
    enrollment_request_hash,
    enrollment_proposed_key_hash,
    enrollment_replay_expires_at,
    enrollment_settings_version,
    enrollment_disclosure_version,
    enrollment_enabled_modules,
    enrollment_required_modules
   FROM public.participant_form_access_codes code
  WHERE ((form_kind = 'ENROLLMENT'::text) AND (enrollment_attempt_id IS NOT NULL) AND (enrollment_replay_expires_at > now()) AND (revoked_at IS NULL) AND (NOT public.chronicle_enrollment_acknowledged(access_code_id)));

-- screen_time_usage_deltas
CREATE VIEW public.screen_time_usage_deltas WITH (security_invoker='true') AS
 WITH raw_snapshots AS (
         SELECT ios_screen_time_usage.study_id,
            ios_screen_time_usage.participant_id,
            ios_screen_time_usage.random_device_id AS device_key,
            ios_screen_time_usage.record_id,
            ios_screen_time_usage.captured_at,
            ios_screen_time_usage.observation_start AS bucket_start_utc,
            ios_screen_time_usage.observation_end AS bucket_end_utc,
            ios_screen_time_usage.timezone AS source_timezone,
            ios_screen_time_usage.row_kind,
            ios_screen_time_usage.capture_labels_hash,
                CASE ios_screen_time_usage.row_kind
                    WHEN 'application'::text THEN COALESCE(NULLIF(ios_screen_time_usage.bundle_identifier, ''::text), NULLIF(ios_screen_time_usage.app_name, ''::text))
                    WHEN 'webDomain'::text THEN ios_screen_time_usage.web_domain
                    ELSE NULL::text
                END AS entity_key,
            ios_screen_time_usage.app_name AS app_label,
            ios_screen_time_usage.bundle_identifier,
            ios_screen_time_usage.web_domain,
            ios_screen_time_usage.category_name AS source_category,
            (ios_screen_time_usage.duration_seconds)::double precision AS cumulative_usage_seconds,
            ios_screen_time_usage.notification_count AS cumulative_notification_count,
            ios_screen_time_usage.pickup_count AS cumulative_pickup_count,
            LEAST(ios_screen_time_usage.observation_end, GREATEST(ios_screen_time_usage.observation_start, ios_screen_time_usage.captured_at)) AS effective_capture_at
           FROM public.ios_screen_time_usage
          WHERE ((EXTRACT(epoch FROM (ios_screen_time_usage.observation_end - ios_screen_time_usage.observation_start)) >= (3540)::numeric) AND (EXTRACT(epoch FROM (ios_screen_time_usage.observation_end - ios_screen_time_usage.observation_start)) <= (3660)::numeric))
        ), ranked_snapshots AS (
         SELECT raw_snapshots.study_id,
            raw_snapshots.participant_id,
            raw_snapshots.device_key,
            raw_snapshots.record_id,
            raw_snapshots.captured_at,
            raw_snapshots.bucket_start_utc,
            raw_snapshots.bucket_end_utc,
            raw_snapshots.source_timezone,
            raw_snapshots.row_kind,
            raw_snapshots.capture_labels_hash,
            raw_snapshots.entity_key,
            raw_snapshots.app_label,
            raw_snapshots.bundle_identifier,
            raw_snapshots.web_domain,
            raw_snapshots.source_category,
            raw_snapshots.cumulative_usage_seconds,
            raw_snapshots.cumulative_notification_count,
            raw_snapshots.cumulative_pickup_count,
            raw_snapshots.effective_capture_at,
            row_number() OVER (PARTITION BY raw_snapshots.study_id, raw_snapshots.participant_id, raw_snapshots.device_key, raw_snapshots.bucket_start_utc, raw_snapshots.bucket_end_utc, raw_snapshots.row_kind, raw_snapshots.capture_labels_hash, raw_snapshots.effective_capture_at ORDER BY raw_snapshots.captured_at DESC, raw_snapshots.record_id DESC) AS correction_rank
           FROM raw_snapshots
          WHERE ((raw_snapshots.entity_key IS NOT NULL) AND (raw_snapshots.cumulative_usage_seconds IS NOT NULL) AND (raw_snapshots.cumulative_usage_seconds >= (0)::double precision))
        ), ordered_snapshots AS (
         SELECT ranked_snapshots.study_id,
            ranked_snapshots.participant_id,
            ranked_snapshots.device_key,
            ranked_snapshots.record_id,
            ranked_snapshots.captured_at,
            ranked_snapshots.bucket_start_utc,
            ranked_snapshots.bucket_end_utc,
            ranked_snapshots.source_timezone,
            ranked_snapshots.row_kind,
            ranked_snapshots.entity_key,
            ranked_snapshots.app_label,
            ranked_snapshots.bundle_identifier,
            ranked_snapshots.web_domain,
            ranked_snapshots.source_category,
            ranked_snapshots.cumulative_usage_seconds,
            ranked_snapshots.cumulative_notification_count,
            ranked_snapshots.cumulative_pickup_count,
            ranked_snapshots.effective_capture_at,
            ranked_snapshots.correction_rank,
            lag(ranked_snapshots.effective_capture_at) OVER snapshot_order AS previous_effective_capture_at,
            lag(ranked_snapshots.cumulative_usage_seconds) OVER snapshot_order AS previous_usage_seconds,
            lag(ranked_snapshots.cumulative_notification_count) OVER snapshot_order AS previous_notification_count,
            lag(ranked_snapshots.cumulative_pickup_count) OVER snapshot_order AS previous_pickup_count
           FROM ranked_snapshots
          WHERE (ranked_snapshots.correction_rank = 1)
          WINDOW snapshot_order AS (PARTITION BY ranked_snapshots.study_id, ranked_snapshots.participant_id, ranked_snapshots.device_key, ranked_snapshots.bucket_start_utc, ranked_snapshots.bucket_end_utc, ranked_snapshots.row_kind, ranked_snapshots.capture_labels_hash ORDER BY ranked_snapshots.effective_capture_at, ranked_snapshots.record_id)
        )
 SELECT study_id,
    participant_id,
    device_key,
    row_kind,
    entity_key,
    app_label,
    bundle_identifier,
    web_domain,
    source_category,
    source_timezone,
    record_id,
    captured_at,
    bucket_start_utc,
    bucket_end_utc,
    COALESCE(previous_effective_capture_at, bucket_start_utc) AS interval_start_utc,
    effective_capture_at AS interval_end_utc,
    (EXTRACT(epoch FROM (effective_capture_at - COALESCE(previous_effective_capture_at, bucket_start_utc))))::double precision AS interval_seconds,
    cumulative_usage_seconds,
        CASE
            WHEN (previous_usage_seconds IS NULL) THEN cumulative_usage_seconds
            WHEN (cumulative_usage_seconds >= previous_usage_seconds) THEN (cumulative_usage_seconds - previous_usage_seconds)
            ELSE NULL::double precision
        END AS usage_delta_seconds,
    cumulative_notification_count,
        CASE
            WHEN (cumulative_notification_count IS NULL) THEN NULL::integer
            WHEN (previous_notification_count IS NULL) THEN cumulative_notification_count
            WHEN (cumulative_notification_count >= previous_notification_count) THEN (cumulative_notification_count - previous_notification_count)
            ELSE NULL::integer
        END AS notification_delta_count,
    cumulative_pickup_count,
        CASE
            WHEN (cumulative_pickup_count IS NULL) THEN NULL::integer
            WHEN (previous_pickup_count IS NULL) THEN cumulative_pickup_count
            WHEN (cumulative_pickup_count >= previous_pickup_count) THEN (cumulative_pickup_count - previous_pickup_count)
            ELSE NULL::integer
        END AS pickup_delta_count,
        CASE
            WHEN ((previous_usage_seconds IS NOT NULL) AND (cumulative_usage_seconds < previous_usage_seconds)) THEN 'counter_decreased'::text
            WHEN ((previous_notification_count IS NOT NULL) AND (cumulative_notification_count < previous_notification_count)) THEN 'counter_decreased'::text
            WHEN ((previous_pickup_count IS NOT NULL) AND (cumulative_pickup_count < previous_pickup_count)) THEN 'counter_decreased'::text
            ELSE 'ok'::text
        END AS delta_status
   FROM ordered_snapshots
  WHERE (effective_capture_at > COALESCE(previous_effective_capture_at, bucket_start_utc));

-- study_participants_with_withdrawals
CREATE VIEW public.study_participants_with_withdrawals WITH (security_invoker='true') AS
 WITH withdrawals AS (
         SELECT mobile_withdrawal_requests.study_id,
            mobile_withdrawal_requests.participant_id,
            min(mobile_withdrawal_requests.created_at) AS withdrawn_at
           FROM public.mobile_withdrawal_requests
          GROUP BY mobile_withdrawal_requests.study_id, mobile_withdrawal_requests.participant_id
        )
 SELECT study_id,
    participant_id,
    COALESCE(p.candidate_id, '00000000-0000-0000-0000-000000000000'::uuid) AS candidate_id,
        CASE
            WHEN (w.withdrawn_at IS NOT NULL) THEN 'NOT_ENROLLED'::text
            ELSE p.participation_status
        END AS participation_status,
    p.participant_notes,
    p.participant_tags,
    p.processing_blocked_at,
    p.processing_block_reason,
    p.processing_blocked_by,
    w.withdrawn_at,
    COALESCE(p.status_revision, 0) AS status_revision
   FROM (public.study_participants p
     FULL JOIN withdrawals w USING (study_id, participant_id));

-- Constraints and indexes

-- data_collection_settings_revisions data_collection_settings_revisions_pkey
ALTER TABLE ONLY public.data_collection_settings_revisions
    ADD CONSTRAINT data_collection_settings_revisions_pkey PRIMARY KEY (study_id, settings_version);

-- data_deletion_audit_outbox data_deletion_audit_outbox_pkey
ALTER TABLE ONLY public.data_deletion_audit_outbox
    ADD CONSTRAINT data_deletion_audit_outbox_pkey PRIMARY KEY (event_id);

-- data_deletion_form_access_revocations data_deletion_form_access_revocations_pkey
ALTER TABLE ONLY public.data_deletion_form_access_revocations
    ADD CONSTRAINT data_deletion_form_access_revocations_pkey PRIMARY KEY (operation_id, resource_kind, resource_id);

-- data_deletion_operations data_deletion_operations_idempotency_key_key
ALTER TABLE ONLY public.data_deletion_operations
    ADD CONSTRAINT data_deletion_operations_idempotency_key_key UNIQUE (idempotency_key);

-- data_deletion_operations data_deletion_operations_pkey
ALTER TABLE ONLY public.data_deletion_operations
    ADD CONSTRAINT data_deletion_operations_pkey PRIMARY KEY (operation_id);

-- data_deletion_steps data_deletion_steps_pkey
ALTER TABLE ONLY public.data_deletion_steps
    ADD CONSTRAINT data_deletion_steps_pkey PRIMARY KEY (operation_id, asset_id);

-- data_deletion_tombstones data_deletion_tombstones_pkey
ALTER TABLE ONLY public.data_deletion_tombstones
    ADD CONSTRAINT data_deletion_tombstones_pkey PRIMARY KEY (operation_id);

-- cancelled_enrollment_attempts cancelled_enrollment_attempts_pkey
ALTER TABLE ONLY public.cancelled_enrollment_attempts
    ADD CONSTRAINT cancelled_enrollment_attempts_pkey PRIMARY KEY (attempt_id);

-- erased_device_key_tombstones erased_device_key_tombstones_pkey
ALTER TABLE ONLY public.erased_device_key_tombstones
    ADD CONSTRAINT erased_device_key_tombstones_pkey PRIMARY KEY (key_hash);

-- jwt_session_state jwt_session_state_pkey
ALTER TABLE ONLY public.jwt_session_state
    ADD CONSTRAINT jwt_session_state_pkey PRIMARY KEY (state_group, state_key);

-- mobile_withdrawal_requests mobile_withdrawal_requests_api_key_id_key
ALTER TABLE ONLY public.mobile_withdrawal_requests
    ADD CONSTRAINT mobile_withdrawal_requests_api_key_id_key UNIQUE (api_key_id);

-- mobile_withdrawal_requests mobile_withdrawal_requests_pkey
ALTER TABLE ONLY public.mobile_withdrawal_requests
    ADD CONSTRAINT mobile_withdrawal_requests_pkey PRIMARY KEY (request_id);

-- participant_form_access_codes participant_form_access_codes_pkey
ALTER TABLE ONLY public.participant_form_access_codes
    ADD CONSTRAINT participant_form_access_codes_pkey PRIMARY KEY (access_code_id);

-- participant_form_access_codes participant_form_access_codes_token_hash_key
ALTER TABLE ONLY public.participant_form_access_codes
    ADD CONSTRAINT participant_form_access_codes_token_hash_key UNIQUE (token_hash);

-- participant_form_sessions participant_form_sessions_pkey
ALTER TABLE ONLY public.participant_form_sessions
    ADD CONSTRAINT participant_form_sessions_pkey PRIMARY KEY (session_id);

-- participant_form_sessions participant_form_sessions_session_hash_key
ALTER TABLE ONLY public.participant_form_sessions
    ADD CONSTRAINT participant_form_sessions_session_hash_key UNIQUE (session_hash);

-- participant_form_submission_receipts participant_form_submission_r_access_code_id_form_kind_reso_key
ALTER TABLE ONLY public.participant_form_submission_receipts
    ADD CONSTRAINT participant_form_submission_resource_key UNIQUE (study_id, participant_id, form_kind, resource_key);

-- participant_form_submission_receipts participant_form_submission_receipts_pkey
ALTER TABLE ONLY public.participant_form_submission_receipts
    ADD CONSTRAINT participant_form_submission_receipts_pkey PRIMARY KEY (receipt_id);

-- participant_purge_cutoffs participant_purge_cutoffs_pkey
ALTER TABLE ONLY public.participant_purge_cutoffs
    ADD CONSTRAINT participant_purge_cutoffs_pkey PRIMARY KEY (study_id, participant_block_token);

-- researcher_create_receipts researcher_create_receipts_pkey
ALTER TABLE ONLY public.researcher_create_receipts
    ADD CONSTRAINT researcher_create_receipts_pkey PRIMARY KEY (principal_id, route, idempotency_key);



-- restore_continuity_reconciliations restore_continuity_reconciliations_pkey
ALTER TABLE ONLY public.restore_continuity_reconciliations
    ADD CONSTRAINT restore_continuity_reconciliations_pkey PRIMARY KEY (checkpoint_id);

-- upload_diagnostic_erasures upload_diagnostic_erasures_pkey
ALTER TABLE ONLY public.upload_diagnostic_erasures
    ADD CONSTRAINT upload_diagnostic_erasures_pkey PRIMARY KEY (study_id, participant_block_token, event_id);

-- upload_diagnostics upload_diagnostics_pkey
ALTER TABLE ONLY public.upload_diagnostics
    ADD CONSTRAINT upload_diagnostics_pkey PRIMARY KEY (study_id, participant_id, random_device_id, event_id);

-- webhook_deliveries webhook_deliveries_pkey
ALTER TABLE ONLY public.webhook_deliveries
    ADD CONSTRAINT webhook_deliveries_pkey PRIMARY KEY (delivery_id);

-- webhook_registrations webhook_registrations_pkey
ALTER TABLE ONLY public.webhook_registrations
    ADD CONSTRAINT webhook_registrations_pkey PRIMARY KEY (webhook_id);

-- android_sensor_data_random_device_id_idx
CREATE INDEX android_sensor_data_random_device_id_idx ON public.android_sensor_data USING btree (random_device_id);

-- android_sensor_data_study_id_idx
CREATE INDEX android_sensor_data_study_id_idx ON public.android_sensor_data USING btree (study_id);

-- audit_deletion_event_id_unique
CREATE UNIQUE INDEX audit_deletion_event_id_unique ON public.audit USING btree ((((data)::jsonb ->> 'deletionAuditEventId'::text))) WHERE ((data)::text ~~ '%"deletionAuditEventId":%'::text);

-- chronicle_usage_events_activity_class_idx
CREATE INDEX chronicle_usage_events_activity_class_idx ON public.chronicle_usage_events USING btree (study_id, participant_id, activity_class);

-- chronicle_usage_events_study_participant_ts_idx: survey reads select a capture-time window per participant.
CREATE INDEX chronicle_usage_events_study_participant_ts_idx ON public.chronicle_usage_events USING btree (study_id, participant_id, event_timestamp);

-- Compliance receipt queries filter one study by upload time.
CREATE INDEX chronicle_usage_events_study_uploaded_at_idx ON public.chronicle_usage_events USING btree (study_id, uploaded_at);

-- chronicle_usage_events_client_event_idx: a retried upload stores each phone-assigned event once.
CREATE UNIQUE INDEX chronicle_usage_events_client_event_idx ON public.chronicle_usage_events USING btree (study_id, participant_id, client_event_id);

-- data_deletion_audit_outbox_operation_event_idx
CREATE UNIQUE INDEX data_deletion_audit_outbox_operation_event_idx ON public.data_deletion_audit_outbox USING btree (operation_id, event_type);

-- data_deletion_audit_outbox_pending_idx
CREATE INDEX data_deletion_audit_outbox_pending_idx ON public.data_deletion_audit_outbox USING btree (available_at, event_timestamp) WHERE (published_at IS NULL);

-- data_deletion_expired_worker_lease_idx
CREATE INDEX data_deletion_expired_worker_lease_idx ON public.data_deletion_operations USING btree (worker_lease_expires_at, created_at) WHERE (status = ANY (ARRAY['ERASING'::text, 'VERIFYING'::text]));

-- data_deletion_form_access_revocations_resource_idx
CREATE INDEX data_deletion_form_access_revocations_resource_idx ON public.data_deletion_form_access_revocations USING btree (resource_kind, resource_id);

-- data_deletion_form_access_revocations_study_idx
CREATE INDEX data_deletion_form_access_revocations_study_idx ON public.data_deletion_form_access_revocations USING btree (study_id, operation_id);

-- data_deletion_operations_due_idx
CREATE INDEX data_deletion_operations_due_idx ON public.data_deletion_operations USING btree (status, next_attempt_at);

-- data_deletion_operations_subject_idx
CREATE INDEX data_deletion_operations_subject_idx ON public.data_deletion_operations USING btree (study_id, participant_ref);

-- data_deletion_operations_write_block_idx
CREATE INDEX data_deletion_operations_write_block_idx ON public.data_deletion_operations USING btree (study_id, participant_block_token, status);

-- deletion_operations_operation_study_unique
CREATE UNIQUE INDEX deletion_operations_operation_study_unique ON public.data_deletion_operations USING btree (operation_id, study_id);

-- erased_device_key_tombstones_expiry_idx
CREATE INDEX erased_device_key_tombstones_expiry_idx ON public.erased_device_key_tombstones USING btree (expires_at);

-- idx_api_keys_active_mobile
CREATE UNIQUE INDEX idx_api_keys_active_mobile ON public.api_keys USING btree (study_id, participant_id, random_device_id) WHERE ((participant_id IS NOT NULL) AND (revoked = false));

-- idx_api_keys_hash
CREATE UNIQUE INDEX idx_api_keys_hash ON public.api_keys USING btree (key_hash) WHERE (NOT revoked);

-- idx_api_keys_prefix
CREATE INDEX idx_api_keys_prefix ON public.api_keys USING btree (key_prefix);

-- idx_api_keys_study_id
CREATE INDEX idx_api_keys_study_id ON public.api_keys USING btree (study_id);

-- idx_deletion_schedule_delete_after
CREATE INDEX idx_deletion_schedule_delete_after ON public.study_deletion_schedule USING btree (delete_after);

-- idx_lifecycle_events_created_at
CREATE INDEX idx_lifecycle_events_created_at ON public.study_lifecycle_events USING btree (created_at);

-- idx_lifecycle_events_study_id
CREATE INDEX idx_lifecycle_events_study_id ON public.study_lifecycle_events USING btree (study_id);

-- idx_notifications_study_id
CREATE INDEX idx_notifications_study_id ON public.notifications USING btree (study_id);

-- idx_participant_stats_study_id
CREATE INDEX idx_participant_stats_study_id ON public.participant_stats USING btree (study_id);

-- idx_questionnaire_submissions_study_id
CREATE INDEX idx_questionnaire_submissions_study_id ON public.questionnaire_submissions USING btree (study_id);

-- idx_questionnaires_study_id
CREATE INDEX idx_questionnaires_study_id ON public.questionnaires USING btree (study_id);


-- idx_studies_lifecycle_status
CREATE INDEX idx_studies_lifecycle_status ON public.studies USING btree (lifecycle_status);

-- idx_study_participants_study_id
CREATE INDEX idx_study_participants_study_id ON public.study_participants USING btree (study_id);

-- idx_tud_submissions_study_id
CREATE INDEX idx_tud_submissions_study_id ON public.time_use_diary_submissions USING btree (study_id);

-- idx_tud_summarized_study_id
CREATE INDEX idx_tud_summarized_study_id ON public.time_use_diary_summarized USING btree (study_id);

-- idx_upload_diagnostics_study_participant_day
CREATE INDEX idx_upload_diagnostics_study_participant_day ON public.upload_diagnostics USING btree (study_id, participant_id, diagnostic_day);

-- idx_upload_diagnostics_study_participant_last
CREATE INDEX idx_upload_diagnostics_study_participant_last ON public.upload_diagnostics USING btree (study_id, participant_id, last_occurred_at DESC);

-- idx_webhook_deliveries_created_at
CREATE INDEX idx_webhook_deliveries_created_at ON public.webhook_deliveries USING btree (created_at);

-- idx_webhook_deliveries_expired_lease
CREATE INDEX idx_webhook_deliveries_expired_lease ON public.webhook_deliveries USING btree (lease_expires_at) WHERE (delivery_state = 'IN_FLIGHT'::text);

-- idx_webhook_deliveries_pending
CREATE INDEX idx_webhook_deliveries_pending ON public.webhook_deliveries USING btree (available_at, created_at) WHERE (delivery_state = 'PENDING'::text);

-- idx_webhook_deliveries_webhook_id
CREATE INDEX idx_webhook_deliveries_webhook_id ON public.webhook_deliveries USING btree (webhook_id);

-- idx_webhook_registrations_study_id
CREATE INDEX idx_webhook_registrations_study_id ON public.webhook_registrations USING btree (study_id);

-- jobs_expired_lease_idx
CREATE INDEX jobs_expired_lease_idx ON public.jobs USING btree (lease_expires_at) WHERE (status = 'RUNNING'::text);

-- jobs_participant_scope_idx
CREATE INDEX jobs_participant_scope_idx ON public.jobs USING gin (participant_ids);

-- jobs_pending_dispatch_idx
CREATE INDEX jobs_pending_dispatch_idx ON public.jobs USING btree (created_at) WHERE (status = 'PENDING'::text);

-- jobs_study_scope_idx
CREATE INDEX jobs_study_scope_idx ON public.jobs USING btree (study_id);

-- jwt_session_state_expiry_idx
CREATE INDEX jwt_session_state_expiry_idx ON public.jwt_session_state USING btree (expires_at) WHERE (expires_at IS NOT NULL);

-- mobile_withdrawal_requests_subject_idx
CREATE INDEX mobile_withdrawal_requests_subject_idx ON public.mobile_withdrawal_requests USING btree (study_id, participant_id, random_device_id);

-- participant_collection_ack_one_decision_per_seq
CREATE UNIQUE INDEX participant_collection_ack_one_decision_per_seq ON public.participant_collection_acknowledgment USING btree (evidence_api_key_id, decision_seq) WHERE (evidence_api_key_id IS NOT NULL);

-- participant_collection_ack_one_enrollment_per_receipt
CREATE UNIQUE INDEX participant_collection_ack_one_enrollment_per_receipt ON public.participant_collection_acknowledgment USING btree (evidence_access_code_id, evidence_api_key_id) WHERE ((collection_trigger = 'ENROLLMENT'::text) AND (evidence_access_code_id IS NOT NULL) AND (evidence_api_key_id IS NOT NULL));

-- participant_form_access_codes_enrollment_attempt_id_key
CREATE UNIQUE INDEX participant_form_access_codes_enrollment_attempt_id_key ON public.participant_form_access_codes USING btree (enrollment_attempt_id) WHERE (enrollment_attempt_id IS NOT NULL);

-- participant_form_access_codes_one_live_reviewer_enrollment
CREATE UNIQUE INDEX participant_form_access_codes_one_live_reviewer_enrollment ON public.participant_form_access_codes USING btree (study_id, participant_id, form_kind, issuer_type, issued_by) WHERE ((form_kind = 'ENROLLMENT'::text) AND (issuer_type = 'RESEARCHER'::text) AND (issued_by = 'play-reviewer-bootstrap'::text) AND (exchanged_at IS NULL) AND (revoked_at IS NULL));

-- participant_form_access_codes_subject_idx
CREATE INDEX participant_form_access_codes_subject_idx ON public.participant_form_access_codes USING btree (study_id, participant_id, expires_at);

-- participant_form_sessions_subject_idx
CREATE INDEX participant_form_sessions_subject_idx ON public.participant_form_sessions USING btree (study_id, participant_id, absolute_expires_at);

-- ios_screen_time_usage_observation_idx
CREATE INDEX ios_screen_time_usage_observation_idx ON public.ios_screen_time_usage USING btree (study_id, participant_id, observation_start, captured_at);

-- study_deletion_schedule_operation_idx
CREATE UNIQUE INDEX study_deletion_schedule_operation_idx ON public.study_deletion_schedule USING btree (operation_id) WHERE (operation_id IS NOT NULL);

-- upload_buffer_study_id_idx
CREATE INDEX upload_buffer_study_id_idx ON public.upload_buffer USING btree (study_id);

-- upload_buffer_type_uploaded_at_idx
CREATE INDEX upload_buffer_type_uploaded_at_idx ON public.upload_buffer USING btree (upload_type, uploaded_at);

-- webhook_deliveries_participant_scope
CREATE INDEX webhook_deliveries_participant_scope ON public.webhook_deliveries USING btree (study_id, participant_id) WHERE ((participant_id IS NOT NULL) AND (participant_id <> ''::text));

-- webhook_deliveries_terminal_completed_at_idx
CREATE INDEX webhook_deliveries_terminal_completed_at_idx ON public.webhook_deliveries USING btree (completed_at) WHERE (delivery_state = ANY (ARRAY['SUCCEEDED'::text, 'FAILED'::text]));

-- data_collection_settings_revisions data_collection_settings_revisions_study_id_fkey
ALTER TABLE ONLY public.data_collection_settings_revisions
    ADD CONSTRAINT data_collection_settings_revisions_study_id_fkey FOREIGN KEY (study_id) REFERENCES public.studies(study_id) ON DELETE CASCADE;

-- data_deletion_audit_outbox data_deletion_audit_outbox_operation_id_study_id_fkey
ALTER TABLE ONLY public.data_deletion_audit_outbox
    ADD CONSTRAINT data_deletion_audit_outbox_operation_id_study_id_fkey FOREIGN KEY (operation_id, study_id) REFERENCES public.data_deletion_operations(operation_id, study_id);

-- data_deletion_form_access_revocations data_deletion_form_access_revocation_operation_id_study_id_fkey
ALTER TABLE ONLY public.data_deletion_form_access_revocations
    ADD CONSTRAINT data_deletion_form_access_revocation_operation_id_study_id_fkey FOREIGN KEY (operation_id, study_id) REFERENCES public.data_deletion_operations(operation_id, study_id) ON DELETE CASCADE;

-- data_deletion_steps data_deletion_steps_operation_id_fkey
ALTER TABLE ONLY public.data_deletion_steps
    ADD CONSTRAINT data_deletion_steps_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES public.data_deletion_operations(operation_id) ON DELETE CASCADE;

-- participant_form_access_codes participant_form_access_codes_study_id_participant_id_fkey
ALTER TABLE ONLY public.participant_form_access_codes
    ADD CONSTRAINT participant_form_access_codes_study_id_participant_id_fkey FOREIGN KEY (study_id, participant_id) REFERENCES public.study_participants(study_id, participant_id) ON DELETE CASCADE;

-- participant_form_sessions participant_form_sessions_access_code_id_fkey
ALTER TABLE ONLY public.participant_form_sessions
    ADD CONSTRAINT participant_form_sessions_access_code_id_fkey FOREIGN KEY (access_code_id) REFERENCES public.participant_form_access_codes(access_code_id) ON DELETE CASCADE;

-- participant_form_submission_receipts participant_form_submission_receipts_access_code_id_fkey
ALTER TABLE ONLY public.participant_form_submission_receipts
    ADD CONSTRAINT participant_form_submission_receipts_access_code_id_fkey FOREIGN KEY (access_code_id) REFERENCES public.participant_form_access_codes(access_code_id) ON DELETE CASCADE;

-- Enrollment cannot outlive its study; candidate deletion must not erase linked enrollments.
ALTER TABLE ONLY public.study_participants ADD CONSTRAINT study_participants_study_id_fkey FOREIGN KEY (study_id) REFERENCES public.studies(study_id) ON DELETE CASCADE;
ALTER TABLE ONLY public.study_participants ADD CONSTRAINT study_participants_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(candidate_id) ON DELETE NO ACTION;

-- study_deletion_schedule study_deletion_schedule_operation_fk
ALTER TABLE ONLY public.study_deletion_schedule
    ADD CONSTRAINT study_deletion_schedule_operation_fk FOREIGN KEY (operation_id, study_id) REFERENCES public.data_deletion_operations(operation_id, study_id);

-- webhook_deliveries webhook_deliveries_webhook_id_fkey
ALTER TABLE ONLY public.webhook_deliveries
    ADD CONSTRAINT webhook_deliveries_webhook_id_fkey FOREIGN KEY (webhook_id) REFERENCES public.webhook_registrations(webhook_id) ON DELETE CASCADE;

-- webhook_registrations webhook_registrations_study_id_fkey
ALTER TABLE ONLY public.webhook_registrations
    ADD CONSTRAINT webhook_registrations_study_id_fkey FOREIGN KEY (study_id) REFERENCES public.studies(study_id) ON DELETE CASCADE;

-- Triggers

-- webhook_deliveries bind_webhook_delivery_scope
CREATE TRIGGER bind_webhook_delivery_scope BEFORE INSERT OR UPDATE ON public.webhook_deliveries FOR EACH ROW EXECUTE FUNCTION public.chronicle_bind_webhook_delivery_scope();

-- jobs chronicle_00_sync_job_scope
CREATE TRIGGER chronicle_00_sync_job_scope BEFORE INSERT OR UPDATE OF definition ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.chronicle_sync_job_scope();

-- jobs chronicle_20_guard_job_against_erasure
CREATE TRIGGER chronicle_20_guard_job_against_erasure BEFORE INSERT OR UPDATE OF definition ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.chronicle_guard_job_against_erasure();

-- jobs chronicle_guard_job_scope_columns
CREATE TRIGGER chronicle_guard_job_scope_columns BEFORE UPDATE OF study_id, participant_ids ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.chronicle_guard_job_scope_columns();

-- jobs chronicle_jobs_status_transition
CREATE TRIGGER chronicle_jobs_status_transition BEFORE UPDATE OF status ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.chronicle_validate_job_status_transition();

-- activity_recognition_events deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.activity_recognition_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();


-- android_device_sensor_availability deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.android_device_sensor_availability REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- android_sensor_data deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.android_sensor_data REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_audio_activity deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.app_audio_activity REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_audio_content deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.app_audio_content REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_network_usage deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.app_network_usage REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_usage_survey deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.app_usage_survey REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- battery_telemetry deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.battery_telemetry REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- chronicle_usage_events deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.chronicle_usage_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- chronicle_usage_stats deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.chronicle_usage_stats REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- connectivity_state_events deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.connectivity_state_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- device_settings deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.device_settings REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- devices deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.devices REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- health_metrics deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.health_metrics REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- interaction_events deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.interaction_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- notification_activity deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.notification_activity REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- notifications deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.notifications REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_form_access_codes deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.participant_form_access_codes REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_form_sessions deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.participant_form_sessions REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_form_submission_receipts deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.participant_form_submission_receipts REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_stats deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.participant_stats REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- questionnaire_submissions deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.questionnaire_submissions REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- ios_screen_time_usage deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.ios_screen_time_usage REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- sleep_events deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.sleep_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- study_participants deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.study_participants REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- time_use_diary_submissions deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.time_use_diary_submissions REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- time_use_diary_summarized deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.time_use_diary_summarized REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- upload_buffer deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.upload_buffer REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- upload_diagnostics deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.upload_diagnostics REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- webhook_deliveries deletion_mutation_guard_insert
CREATE TRIGGER deletion_mutation_guard_insert AFTER INSERT ON public.webhook_deliveries REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- activity_recognition_events deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.activity_recognition_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();


-- android_device_sensor_availability deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.android_device_sensor_availability REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- android_sensor_data deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.android_sensor_data REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_audio_activity deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.app_audio_activity REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_audio_content deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.app_audio_content REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_network_usage deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.app_network_usage REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- app_usage_survey deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.app_usage_survey REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- battery_telemetry deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.battery_telemetry REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- chronicle_usage_events deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.chronicle_usage_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- chronicle_usage_stats deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.chronicle_usage_stats REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- connectivity_state_events deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.connectivity_state_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- device_settings deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.device_settings REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- devices deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.devices REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- health_metrics deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.health_metrics REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- interaction_events deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.interaction_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- notification_activity deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.notification_activity REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- notifications deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.notifications REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_form_access_codes deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.participant_form_access_codes REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_form_sessions deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.participant_form_sessions REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_form_submission_receipts deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.participant_form_submission_receipts REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- participant_stats deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.participant_stats REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- questionnaire_submissions deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.questionnaire_submissions REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- ios_screen_time_usage deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.ios_screen_time_usage REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- sleep_events deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.sleep_events REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- study_participants deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.study_participants REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- time_use_diary_submissions deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.time_use_diary_submissions REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- time_use_diary_summarized deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.time_use_diary_summarized REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- upload_buffer deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.upload_buffer REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- upload_diagnostics deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.upload_diagnostics REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- webhook_deliveries deletion_mutation_guard_update
CREATE TRIGGER deletion_mutation_guard_update AFTER UPDATE ON public.webhook_deliveries REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_guard_participant_mutation();

-- audit_buffer forward_legacy_audit_buffer_insert_trigger
CREATE TRIGGER forward_legacy_audit_buffer_insert_trigger BEFORE INSERT ON public.audit_buffer FOR EACH ROW EXECUTE FUNCTION public.forward_legacy_audit_buffer_insert();

-- permissions permissions_owner_mutation_lock
CREATE TRIGGER permissions_owner_mutation_lock BEFORE INSERT OR DELETE OR UPDATE ON public.permissions FOR EACH ROW EXECUTE FUNCTION public.chronicle_lock_acl_owner_mutation();

-- permissions permissions_permanent_owner
CREATE CONSTRAINT TRIGGER permissions_permanent_owner AFTER INSERT OR DELETE OR UPDATE ON public.permissions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.chronicle_protect_last_active_acl_owner();

-- audit_buffer prevent_audit_buffer_modification_trigger
CREATE TRIGGER prevent_audit_buffer_modification_trigger BEFORE DELETE OR UPDATE ON public.audit_buffer FOR EACH ROW EXECUTE FUNCTION public.prevent_audit_modification();

-- audit prevent_audit_modification_trigger
CREATE TRIGGER prevent_audit_modification_trigger BEFORE DELETE OR UPDATE ON public.audit FOR EACH ROW EXECUTE FUNCTION public.prevent_audit_modification();

-- study_participants record_capture_cutoff
CREATE TRIGGER record_study_capture_cutoff BEFORE INSERT OR UPDATE OF lifecycle_status, closed_capture_windows ON public.studies FOR EACH ROW EXECUTE FUNCTION public.chronicle_record_study_capture_cutoff();
CREATE TRIGGER record_capture_cutoff BEFORE INSERT OR UPDATE OF participation_status, closed_capture_windows, hard_stop_generation, hard_stopped_at ON public.study_participants FOR EACH ROW EXECUTE FUNCTION public.chronicle_record_capture_cutoff();

-- studies record_data_collection_settings_revision
CREATE TRIGGER record_data_collection_settings_revision AFTER INSERT OR UPDATE OF settings, modules ON public.studies FOR EACH ROW EXECUTE FUNCTION public.record_data_collection_settings_revision();

-- upload_diagnostics record_erased_diagnostic_ids
CREATE TRIGGER record_erased_diagnostic_ids AFTER DELETE ON public.upload_diagnostics REFERENCING OLD TABLE AS erased_rows FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_record_erased_diagnostic_ids();

-- restore_continuity_reconciliations restore_continuity_receipts_no_truncate
CREATE TRIGGER restore_continuity_receipts_no_truncate BEFORE TRUNCATE ON public.restore_continuity_reconciliations FOR EACH STATEMENT EXECUTE FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation();

-- restore_continuity_reconciliations restore_continuity_receipts_no_update_delete
CREATE TRIGGER restore_continuity_receipts_no_update_delete BEFORE DELETE OR UPDATE ON public.restore_continuity_reconciliations FOR EACH ROW EXECUTE FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation();

-- securable_objects securable_object_permanent_owner
CREATE CONSTRAINT TRIGGER securable_object_permanent_owner AFTER INSERT OR UPDATE ON public.securable_objects DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.chronicle_protect_securable_object_owner();

-- data_deletion_operations set_deletion_block_token
CREATE TRIGGER set_deletion_block_token BEFORE INSERT OR UPDATE OF study_id, participant_id, participant_block_token ON public.data_deletion_operations FOR EACH ROW EXECUTE FUNCTION public.chronicle_set_deletion_block_token();

-- Cancellation advances in the same transaction as erasure intake and withdrawal.
CREATE TRIGGER advance_disclosure_for_erasure AFTER INSERT OR UPDATE OF status ON public.data_deletion_operations
    FOR EACH ROW WHEN (NEW.status = 'QUARANTINED') EXECUTE FUNCTION public.chronicle_advance_disclosure_generation();
CREATE TRIGGER advance_disclosure_for_withdrawal AFTER UPDATE OF participation_status ON public.study_participants
    FOR EACH ROW WHEN (NEW.participation_status = 'NOT_ENROLLED' AND OLD.participation_status IS DISTINCT FROM NEW.participation_status)
    EXECUTE FUNCTION public.chronicle_advance_disclosure_generation();

-- study_participants stop_withdrawn_participant_collection
CREATE TRIGGER stop_withdrawn_participant_collection BEFORE INSERT OR UPDATE OF participation_status ON public.study_participants FOR EACH ROW EXECUTE FUNCTION public.chronicle_stop_withdrawn_participant_collection();

-- Row security

CREATE FUNCTION public.chronicle_participant_processing_allowed(check_study_id uuid, check_participant_id text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public' AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM public.study_participants
        WHERE study_id = check_study_id AND participant_id = check_participant_id
          AND processing_blocked_at IS NOT NULL
    )
$$;
REVOKE ALL ON FUNCTION public.chronicle_participant_processing_allowed(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.chronicle_participant_processing_allowed(uuid, text) TO chronicle_app, chronicle_admin;

-- Policies read these once per statement as `(SELECT f())` InitPlans instead of calling a helper per row.
CREATE FUNCTION public.chronicle_rls_is_admin() RETURNS boolean
LANGUAGE sql STABLE SET search_path TO 'pg_catalog' AS $$
    SELECT COALESCE(NULLIF(current_setting('app.is_admin', true), '')::boolean, false)
$$;

CREATE FUNCTION public.chronicle_authorized_study_ids() RETURNS SETOF uuid
LANGUAGE plpgsql STABLE SET search_path TO 'pg_catalog' AS $$
BEGIN
    RETURN QUERY SELECT unnest(string_to_array(NULLIF(current_setting('app.authorized_studies', true), ''), ',')::uuid[]);
EXCEPTION WHEN OTHERS THEN
    RETURN;
END;
$$;

CREATE FUNCTION public.chronicle_authorized_organization_ids() RETURNS SETOF uuid
LANGUAGE plpgsql STABLE SET search_path TO 'pg_catalog' AS $$
BEGIN
    RETURN QUERY SELECT unnest(string_to_array(NULLIF(current_setting('app.authorized_organizations', true), ''), ',')::uuid[]);
EXCEPTION WHEN OTHERS THEN
    RETURN;
END;
$$;

ALTER TABLE public.studies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.studies FORCE ROW LEVEL SECURITY;
CREATE POLICY study_isolation_studies ON public.studies
    USING ((SELECT public.chronicle_rls_is_admin()) OR study_id IN (SELECT public.chronicle_authorized_study_ids()))
    WITH CHECK ((SELECT public.chronicle_rls_is_admin()) OR study_id IN (SELECT public.chronicle_authorized_study_ids()));

ALTER TABLE public.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation_organizations ON public.organizations
    USING ((SELECT public.chronicle_rls_is_admin()) OR organization_id IN (SELECT public.chronicle_authorized_organization_ids()))
    WITH CHECK ((SELECT public.chronicle_rls_is_admin()) OR organization_id IN (SELECT public.chronicle_authorized_organization_ids()));

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.users FORCE ROW LEVEL SECURITY;
-- Persisted profiles are owned by the system-scoped UserMapstore; request paths use configured directory services.
-- An anonymous context marker must never match a real user identifier.
CREATE POLICY user_isolation_users ON public.users
    USING ((SELECT public.chronicle_rls_is_admin()))
    WITH CHECK ((SELECT public.chronicle_rls_is_admin()));

-- Authentication runs before request authorization; only its explicit system context can access shared state.
ALTER TABLE public.jwt_session_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jwt_session_state FORCE ROW LEVEL SECURITY;
CREATE POLICY system_jwt_session_state ON public.jwt_session_state
    USING ((SELECT public.chronicle_rls_is_admin())) WITH CHECK ((SELECT public.chronicle_rls_is_admin()));

ALTER TABLE public.data_deletion_tombstones ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.data_deletion_tombstones FORCE ROW LEVEL SECURITY;
CREATE POLICY system_data_deletion_tombstones ON public.data_deletion_tombstones
    USING ((SELECT public.chronicle_rls_is_admin())) WITH CHECK ((SELECT public.chronicle_rls_is_admin()));

-- The legacy buffer forwards appends to audit; readers and mutations remain forbidden.
ALTER TABLE public.audit_buffer ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_buffer FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_buffer_insert ON public.audit_buffer FOR INSERT WITH CHECK (true);
CREATE POLICY audit_buffer_select ON public.audit_buffer FOR SELECT USING (false);
CREATE POLICY audit_buffer_update ON public.audit_buffer FOR UPDATE USING (false) WITH CHECK (false);
CREATE POLICY audit_buffer_delete ON public.audit_buffer FOR DELETE USING (false);

-- The deletion statuses during which an erasure hides its subject's data from runtime readers.
CREATE FUNCTION public.chronicle_erasure_hides_data(status text) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path TO 'pg_catalog' AS $$
    SELECT status IN ('QUARANTINED', 'ERASING', 'VERIFYING', 'FAILED')
$$;

-- The three enumerations below run as their owner, so each limits itself to the caller's studies; admin (which
-- includes background work and the deletion worker) sees every study.
CREATE FUNCTION public.chronicle_caller_sees_study(check_study_id uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path TO 'pg_catalog', 'public' AS $$
    SELECT public.chronicle_rls_is_admin() OR check_study_id IN (SELECT public.chronicle_authorized_study_ids())
$$;

CREATE FUNCTION public.chronicle_erasing_study_ids() RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'pg_catalog', 'public' AS $$
    SELECT operation.study_id::text
    FROM public.data_deletion_operations operation
    WHERE operation.mode = 'STUDY_ERASURE'
      AND public.chronicle_erasure_hides_data(operation.status)
      AND public.chronicle_caller_sees_study(operation.study_id)
$$;

CREATE FUNCTION public.chronicle_erasing_participants() RETURNS TABLE(study_id text, participant_id text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'pg_catalog', 'public' AS $$
    SELECT operation.study_id::text, operation.participant_id
    FROM public.data_deletion_operations operation
    WHERE operation.participant_id IS NOT NULL
      AND public.chronicle_erasure_hides_data(operation.status)
      AND public.chronicle_caller_sees_study(operation.study_id)
$$;

CREATE FUNCTION public.chronicle_processing_blocked_participants() RETURNS TABLE(study_id text, participant_id text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'pg_catalog', 'public' AS $$
    SELECT participant.study_id::text, participant.participant_id
    FROM public.study_participants participant
    WHERE participant.processing_blocked_at IS NOT NULL
      AND public.chronicle_caller_sees_study(participant.study_id)
$$;

-- Responses hidden by a processing block or an erasure quarantine still keep their questionnaire.
CREATE FUNCTION public.chronicle_questionnaire_has_submissions(check_study_id uuid, check_questionnaire_id uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'pg_catalog', 'public' AS $$
    SELECT public.chronicle_caller_sees_study(check_study_id) AND EXISTS (
        SELECT 1 FROM public.questionnaire_submissions submission
        WHERE submission.study_id = check_study_id AND submission.questionnaire_id = check_questionnaire_id
    )
$$;
REVOKE ALL ON FUNCTION public.chronicle_questionnaire_has_submissions(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.chronicle_questionnaire_has_submissions(uuid, uuid) TO chronicle_app, chronicle_admin;

-- The session-setting readers stay executable by PUBLIC: table owners and migration roles evaluate the same policies.
REVOKE ALL ON FUNCTION public.chronicle_erasing_study_ids(), public.chronicle_erasing_participants(),
    public.chronicle_processing_blocked_participants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.chronicle_erasing_study_ids(), public.chronicle_erasing_participants(),
    public.chronicle_processing_blocked_participants() TO chronicle_app, chronicle_admin, chronicle;

CREATE TRIGGER advance_disclosure_for_processing_block AFTER UPDATE OF processing_blocked_at ON public.study_participants
    FOR EACH ROW WHEN (OLD.processing_blocked_at IS DISTINCT FROM NEW.processing_blocked_at)
    EXECUTE FUNCTION public.chronicle_advance_disclosure_generation();


-- Processing blocks preserve rows while withholding research data from runtime readers.
CREATE POLICY processing_block_activity_recognition_events ON public.activity_recognition_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_android_device_sensor_availability ON public.android_device_sensor_availability AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_android_sensor_data ON public.android_sensor_data AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_app_audio_activity ON public.app_audio_activity AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_app_audio_content ON public.app_audio_content AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_app_network_usage ON public.app_network_usage AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_app_usage_survey ON public.app_usage_survey AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_battery_telemetry ON public.battery_telemetry AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_chronicle_usage_events ON public.chronicle_usage_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_chronicle_usage_stats ON public.chronicle_usage_stats AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_connectivity_state_events ON public.connectivity_state_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_device_settings ON public.device_settings AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_health_metrics ON public.health_metrics AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_interaction_events ON public.interaction_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_notification_activity ON public.notification_activity AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_participant_stats ON public.participant_stats AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_questionnaire_submissions ON public.questionnaire_submissions AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_ios_screen_time_usage ON public.ios_screen_time_usage AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_sleep_events ON public.sleep_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_time_use_diary_submissions ON public.time_use_diary_submissions AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_time_use_diary_summarized ON public.time_use_diary_summarized AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_upload_buffer ON public.upload_buffer AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));
CREATE POLICY processing_block_upload_diagnostics ON public.upload_diagnostics AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT COALESCE(((study_id)::text, participant_id) IN (SELECT blocked.study_id, blocked.participant_id FROM public.chronicle_processing_blocked_participants() blocked), false)));

-- activity_recognition_events
ALTER TABLE public.activity_recognition_events ENABLE ROW LEVEL SECURITY;


-- android_device_sensor_availability
ALTER TABLE public.android_device_sensor_availability ENABLE ROW LEVEL SECURITY;

-- android_sensor_data
ALTER TABLE public.android_sensor_data ENABLE ROW LEVEL SECURITY;

-- api_keys
ALTER TABLE public.api_keys ENABLE ROW LEVEL SECURITY;

-- api_keys api_keys_all_policy
CREATE POLICY api_keys_all_policy ON public.api_keys USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- api_keys api_keys_select_policy
CREATE POLICY api_keys_select_policy ON public.api_keys FOR SELECT USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- app_audio_activity
ALTER TABLE public.app_audio_activity ENABLE ROW LEVEL SECURITY;

-- app_audio_content
ALTER TABLE public.app_audio_content ENABLE ROW LEVEL SECURITY;

-- app_network_usage
ALTER TABLE public.app_network_usage ENABLE ROW LEVEL SECURITY;

-- app_usage_survey
ALTER TABLE public.app_usage_survey ENABLE ROW LEVEL SECURITY;

-- audit
ALTER TABLE public.audit ENABLE ROW LEVEL SECURITY;

-- audit audit_insert
CREATE POLICY audit_insert ON public.audit FOR INSERT WITH CHECK (true);

-- audit audit_no_delete
CREATE POLICY audit_no_delete ON public.audit FOR DELETE USING (false);

-- audit audit_no_update
CREATE POLICY audit_no_update ON public.audit FOR UPDATE USING (false);

-- battery_telemetry
ALTER TABLE public.battery_telemetry ENABLE ROW LEVEL SECURITY;

-- candidates
ALTER TABLE public.candidates ENABLE ROW LEVEL SECURITY;

-- chronicle_usage_events
ALTER TABLE public.chronicle_usage_events ENABLE ROW LEVEL SECURITY;

-- chronicle_usage_stats
ALTER TABLE public.chronicle_usage_stats ENABLE ROW LEVEL SECURITY;

-- connectivity_state_events
ALTER TABLE public.connectivity_state_events ENABLE ROW LEVEL SECURITY;

-- data_collection_settings_revisions
ALTER TABLE public.data_collection_settings_revisions ENABLE ROW LEVEL SECURITY;

-- data_deletion_audit_outbox
ALTER TABLE public.data_deletion_audit_outbox ENABLE ROW LEVEL SECURITY;

-- data_deletion_form_access_revocations
ALTER TABLE public.data_deletion_form_access_revocations ENABLE ROW LEVEL SECURITY;

-- data_deletion_operations
ALTER TABLE public.data_deletion_operations ENABLE ROW LEVEL SECURITY;

-- data_deletion_steps
ALTER TABLE public.data_deletion_steps ENABLE ROW LEVEL SECURITY;

-- activity_recognition_events deletion_quarantine_activity_recognition_events
CREATE POLICY deletion_quarantine_activity_recognition_events ON public.activity_recognition_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));


-- android_device_sensor_availability deletion_quarantine_android_device_sensor_availability
CREATE POLICY deletion_quarantine_android_device_sensor_availability ON public.android_device_sensor_availability AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- android_sensor_data deletion_quarantine_android_sensor_data
CREATE POLICY deletion_quarantine_android_sensor_data ON public.android_sensor_data AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- app_audio_activity deletion_quarantine_app_audio_activity
CREATE POLICY deletion_quarantine_app_audio_activity ON public.app_audio_activity AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- app_audio_content deletion_quarantine_app_audio_content
CREATE POLICY deletion_quarantine_app_audio_content ON public.app_audio_content AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- app_network_usage deletion_quarantine_app_network_usage
CREATE POLICY deletion_quarantine_app_network_usage ON public.app_network_usage AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- app_usage_survey deletion_quarantine_app_usage_survey
CREATE POLICY deletion_quarantine_app_usage_survey ON public.app_usage_survey AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- battery_telemetry deletion_quarantine_battery_telemetry
CREATE POLICY deletion_quarantine_battery_telemetry ON public.battery_telemetry AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- chronicle_usage_events deletion_quarantine_chronicle_usage_events
CREATE POLICY deletion_quarantine_chronicle_usage_events ON public.chronicle_usage_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- chronicle_usage_stats deletion_quarantine_chronicle_usage_stats
CREATE POLICY deletion_quarantine_chronicle_usage_stats ON public.chronicle_usage_stats AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- connectivity_state_events deletion_quarantine_connectivity_state_events
CREATE POLICY deletion_quarantine_connectivity_state_events ON public.connectivity_state_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- device_settings deletion_quarantine_device_settings
CREATE POLICY deletion_quarantine_device_settings ON public.device_settings AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- devices deletion_quarantine_devices
CREATE POLICY deletion_quarantine_devices ON public.devices AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR (NOT (EXISTS ( SELECT 1
   FROM public.data_deletion_operations operation
  WHERE ((operation.study_id = devices.study_id) AND (operation.mode = ANY (ARRAY['WITHDRAW_AND_ERASE'::text, 'STUDY_ERASURE'::text])) AND (operation.status <> ALL (ARRAY['PREVIEW'::text, 'CANCELLED'::text])) AND ((operation.mode = 'STUDY_ERASURE'::text) OR (operation.participant_id = devices.participant_id) OR (operation.participant_block_token = chronicle_participant_block_token(devices.study_id, devices.participant_id)))))))));

-- health_metrics deletion_quarantine_health_metrics
CREATE POLICY deletion_quarantine_health_metrics ON public.health_metrics AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- interaction_events deletion_quarantine_interaction_events
CREATE POLICY deletion_quarantine_interaction_events ON public.interaction_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- jobs deletion_quarantine_jobs
CREATE POLICY deletion_quarantine_jobs ON public.jobs AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR EXISTS ( SELECT 1
   FROM unnest(jobs.participant_ids) participant(participant_id)
  WHERE ((jobs.study_id)::text, participant.participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing)))));

-- notification_activity deletion_quarantine_notification_activity
CREATE POLICY deletion_quarantine_notification_activity ON public.notification_activity AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- notifications deletion_quarantine_notifications
CREATE POLICY deletion_quarantine_notifications ON public.notifications AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- participant_form_access_codes deletion_quarantine_participant_form_access_codes
CREATE POLICY deletion_quarantine_participant_form_access_codes ON public.participant_form_access_codes AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- participant_form_sessions deletion_quarantine_participant_form_sessions
CREATE POLICY deletion_quarantine_participant_form_sessions ON public.participant_form_sessions AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- participant_form_submission_receipts deletion_quarantine_participant_form_submission_receipts
CREATE POLICY deletion_quarantine_participant_form_submission_receipts ON public.participant_form_submission_receipts AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- participant_stats deletion_quarantine_participant_stats
CREATE POLICY deletion_quarantine_participant_stats ON public.participant_stats AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- questionnaire_submissions deletion_quarantine_questionnaire_submissions
CREATE POLICY deletion_quarantine_questionnaire_submissions ON public.questionnaire_submissions AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- ios_screen_time_usage deletion_quarantine_ios_screen_time_usage
CREATE POLICY deletion_quarantine_ios_screen_time_usage ON public.ios_screen_time_usage AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- sleep_events deletion_quarantine_sleep_events
CREATE POLICY deletion_quarantine_sleep_events ON public.sleep_events AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- study_participants deletion_quarantine_study_participants
CREATE POLICY deletion_quarantine_study_participants ON public.study_participants AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- time_use_diary_submissions deletion_quarantine_time_use_diary_submissions
CREATE POLICY deletion_quarantine_time_use_diary_submissions ON public.time_use_diary_submissions AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- time_use_diary_summarized deletion_quarantine_time_use_diary_summarized
CREATE POLICY deletion_quarantine_time_use_diary_summarized ON public.time_use_diary_summarized AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- upload_buffer deletion_quarantine_upload_buffer
CREATE POLICY deletion_quarantine_upload_buffer ON public.upload_buffer AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- upload_diagnostics deletion_quarantine_upload_diagnostics
CREATE POLICY deletion_quarantine_upload_diagnostics ON public.upload_diagnostics AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- webhook_deliveries deletion_quarantine_webhook_deliveries
CREATE POLICY deletion_quarantine_webhook_deliveries ON public.webhook_deliveries AS RESTRICTIVE FOR SELECT USING (((SELECT public.chronicle_is_deletion_worker()) OR NOT (COALESCE((study_id)::text IN (SELECT public.chronicle_erasing_study_ids()), false) OR COALESCE(((study_id)::text, participant_id) IN (SELECT erasing.study_id, erasing.participant_id FROM public.chronicle_erasing_participants() erasing), false))));

-- participant_purge_cutoffs deletion_worker_purge_cutoff_write
CREATE POLICY deletion_worker_purge_cutoff_write ON public.participant_purge_cutoffs USING ((SELECT public.chronicle_is_deletion_worker())) WITH CHECK ((SELECT public.chronicle_is_deletion_worker()));

-- upload_diagnostic_erasures deletion_worker_upload_diagnostic_erasure_delete
CREATE POLICY deletion_worker_upload_diagnostic_erasure_delete ON public.upload_diagnostic_erasures FOR DELETE USING ((SELECT public.chronicle_is_deletion_worker()));

-- device_settings
ALTER TABLE public.device_settings ENABLE ROW LEVEL SECURITY;

-- devices
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

-- cancelled_enrollment_attempts
ALTER TABLE public.cancelled_enrollment_attempts ENABLE ROW LEVEL SECURITY;

-- cancelled_enrollment_attempts cancelled_enrollment_attempts_read
CREATE POLICY cancelled_enrollment_attempts_read ON public.cancelled_enrollment_attempts FOR SELECT USING ((current_setting('app.is_admin'::text, true) = 'true'::text));

-- cancelled_enrollment_attempts cancelled_enrollment_attempts_write
CREATE POLICY cancelled_enrollment_attempts_write ON public.cancelled_enrollment_attempts FOR INSERT WITH CHECK ((current_setting('app.is_admin'::text, true) = 'true'::text));

-- erased_device_key_tombstones
ALTER TABLE public.erased_device_key_tombstones ENABLE ROW LEVEL SECURITY;

-- erased_device_key_tombstones erased_device_key_tombstones_expire
CREATE POLICY erased_device_key_tombstones_expire ON public.erased_device_key_tombstones FOR DELETE USING (((current_setting('app.is_admin'::text, true) = 'true'::text) AND (expires_at <= now())));

-- erased_device_key_tombstones erased_device_key_tombstones_read
CREATE POLICY erased_device_key_tombstones_read ON public.erased_device_key_tombstones FOR SELECT USING ((current_setting('app.is_admin'::text, true) = 'true'::text));

-- erased_device_key_tombstones erased_device_key_tombstones_restore
CREATE POLICY erased_device_key_tombstones_restore ON public.erased_device_key_tombstones FOR UPDATE USING ((SELECT public.chronicle_is_deletion_worker())) WITH CHECK ((SELECT public.chronicle_is_deletion_worker()));

-- erased_device_key_tombstones erased_device_key_tombstones_write
CREATE POLICY erased_device_key_tombstones_write ON public.erased_device_key_tombstones FOR INSERT WITH CHECK ((SELECT public.chronicle_is_deletion_worker()));

-- data_collection_settings_revisions erasure_worker_delete
CREATE POLICY erasure_worker_delete ON public.data_collection_settings_revisions FOR DELETE TO CURRENT_USER USING ((SELECT public.chronicle_is_deletion_worker()));

-- mobile_withdrawal_requests erasure_worker_delete
CREATE POLICY erasure_worker_delete ON public.mobile_withdrawal_requests FOR DELETE TO CURRENT_USER USING ((SELECT public.chronicle_is_deletion_worker()));

-- data_collection_settings_revisions erasure_worker_select
CREATE POLICY erasure_worker_select ON public.data_collection_settings_revisions FOR SELECT TO CURRENT_USER USING ((SELECT public.chronicle_is_deletion_worker()));

-- mobile_withdrawal_requests erasure_worker_select
CREATE POLICY erasure_worker_select ON public.mobile_withdrawal_requests FOR SELECT TO CURRENT_USER USING ((SELECT public.chronicle_is_deletion_worker()));

-- filtered_apps
ALTER TABLE public.filtered_apps ENABLE ROW LEVEL SECURITY;

-- health_metrics
ALTER TABLE public.health_metrics ENABLE ROW LEVEL SECURITY;

-- interaction_events
ALTER TABLE public.interaction_events ENABLE ROW LEVEL SECURITY;

-- jobs
ALTER TABLE public.jobs ENABLE ROW LEVEL SECURITY;

-- mobile_withdrawal_requests
ALTER TABLE public.mobile_withdrawal_requests ENABLE ROW LEVEL SECURITY;

-- notification_activity
ALTER TABLE public.notification_activity ENABLE ROW LEVEL SECURITY;

-- notifications
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;

-- organization_studies
ALTER TABLE public.organization_studies ENABLE ROW LEVEL SECURITY;

-- participant_collection_acknowledgment
ALTER TABLE public.participant_collection_acknowledgment ENABLE ROW LEVEL SECURITY;

-- participant_collection_acknowledgment participant_collection_acknowledgment_insert
CREATE POLICY participant_collection_acknowledgment_insert ON public.participant_collection_acknowledgment FOR INSERT WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_collection_acknowledgment participant_collection_acknowledgment_no_delete
CREATE POLICY participant_collection_acknowledgment_no_delete ON public.participant_collection_acknowledgment FOR DELETE USING (false);

-- participant_collection_acknowledgment participant_collection_acknowledgment_no_update
CREATE POLICY participant_collection_acknowledgment_no_update ON public.participant_collection_acknowledgment FOR UPDATE USING (false);

-- participant_collection_acknowledgment participant_collection_acknowledgment_select
CREATE POLICY participant_collection_acknowledgment_select ON public.participant_collection_acknowledgment FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_form_access_codes
ALTER TABLE public.participant_form_access_codes ENABLE ROW LEVEL SECURITY;

-- participant_form_sessions
ALTER TABLE public.participant_form_sessions ENABLE ROW LEVEL SECURITY;

-- participant_form_submission_receipts
ALTER TABLE public.participant_form_submission_receipts ENABLE ROW LEVEL SECURITY;

-- participant_purge_cutoffs
ALTER TABLE public.participant_purge_cutoffs ENABLE ROW LEVEL SECURITY;

-- participant_stats
ALTER TABLE public.participant_stats ENABLE ROW LEVEL SECURITY;

-- questionnaire_submissions
ALTER TABLE public.questionnaire_submissions ENABLE ROW LEVEL SECURITY;

-- questionnaires
ALTER TABLE public.questionnaires ENABLE ROW LEVEL SECURITY;

-- researcher_create_receipts
ALTER TABLE public.researcher_create_receipts ENABLE ROW LEVEL SECURITY;

-- researcher_create_receipts researcher_create_receipts_owner
CREATE POLICY researcher_create_receipts_owner ON public.researcher_create_receipts USING ((((principal_id = NULLIF(current_setting('app.current_user_id'::text, true), '')) AND (owner_kind = NULLIF(current_setting('app.receipt_owner_kind'::text, true), '')) AND (owner_kind IN ('USER', 'API_KEY'))) OR (SELECT public.chronicle_is_deletion_worker()))) WITH CHECK (((principal_id = NULLIF(current_setting('app.current_user_id'::text, true), '')) AND (owner_kind = NULLIF(current_setting('app.receipt_owner_kind'::text, true), '')) AND (owner_kind IN ('USER', 'API_KEY'))));





-- ios_screen_time_usage
ALTER TABLE public.ios_screen_time_usage ENABLE ROW LEVEL SECURITY;

-- sleep_events
ALTER TABLE public.sleep_events ENABLE ROW LEVEL SECURITY;

-- study_deletion_schedule
-- Current scheduling writes its erasure link and prior state atomically.
ALTER TABLE public.study_deletion_schedule ALTER COLUMN operation_id SET NOT NULL;
ALTER TABLE public.study_deletion_schedule ALTER COLUMN previous_status SET NOT NULL;
ALTER TABLE public.study_deletion_schedule ENABLE ROW LEVEL SECURITY;

-- study_deletion_schedule study_deletion_schedule_all_policy
CREATE POLICY study_deletion_schedule_all_policy ON public.study_deletion_schedule USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- study_deletion_schedule study_deletion_schedule_select_policy
CREATE POLICY study_deletion_schedule_select_policy ON public.study_deletion_schedule FOR SELECT USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- activity_recognition_events study_isolation_activity_recognition_events
CREATE POLICY study_isolation_activity_recognition_events ON public.activity_recognition_events USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));


-- android_device_sensor_availability study_isolation_android_device_sensor_availability
CREATE POLICY study_isolation_android_device_sensor_availability ON public.android_device_sensor_availability USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- android_sensor_data study_isolation_android_sensor_data
CREATE POLICY study_isolation_android_sensor_data ON public.android_sensor_data USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- app_audio_activity study_isolation_app_audio_activity
CREATE POLICY study_isolation_app_audio_activity ON public.app_audio_activity USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- app_audio_content study_isolation_app_audio_content
CREATE POLICY study_isolation_app_audio_content ON public.app_audio_content USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- app_network_usage study_isolation_app_network_usage
CREATE POLICY study_isolation_app_network_usage ON public.app_network_usage USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- app_usage_survey study_isolation_app_usage_survey
CREATE POLICY study_isolation_app_usage_survey ON public.app_usage_survey USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- battery_telemetry study_isolation_battery_telemetry
CREATE POLICY study_isolation_battery_telemetry ON public.battery_telemetry USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- candidates study_isolation_candidates_delete
CREATE POLICY study_isolation_candidates_delete ON public.candidates FOR DELETE USING (public.chronicle_has_candidate_write_access(candidate_id));

-- candidates study_isolation_candidates_insert
CREATE POLICY study_isolation_candidates_insert ON public.candidates FOR INSERT WITH CHECK (true);

-- candidates study_isolation_candidates_select
CREATE POLICY study_isolation_candidates_select ON public.candidates FOR SELECT USING (public.chronicle_has_candidate_access(candidate_id));

-- candidates study_isolation_candidates_update
CREATE POLICY study_isolation_candidates_update ON public.candidates FOR UPDATE USING (public.chronicle_has_candidate_write_access(candidate_id)) WITH CHECK (public.chronicle_has_candidate_write_access(candidate_id));

-- connectivity_state_events study_isolation_connectivity_state_events
CREATE POLICY study_isolation_connectivity_state_events ON public.connectivity_state_events USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- data_collection_settings_revisions study_isolation_data_collection_settings_revisions
CREATE POLICY study_isolation_data_collection_settings_revisions ON public.data_collection_settings_revisions FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- data_deletion_audit_outbox study_isolation_data_deletion_audit_outbox
CREATE POLICY study_isolation_data_deletion_audit_outbox ON public.data_deletion_audit_outbox USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- data_deletion_form_access_revocations study_isolation_data_deletion_form_access_revocations
CREATE POLICY study_isolation_data_deletion_form_access_revocations ON public.data_deletion_form_access_revocations USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- data_deletion_operations study_isolation_data_deletion_operations
CREATE POLICY study_isolation_data_deletion_operations ON public.data_deletion_operations USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- data_deletion_steps study_isolation_data_deletion_steps
CREATE POLICY study_isolation_data_deletion_steps ON public.data_deletion_steps USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- device_settings study_isolation_device_settings
CREATE POLICY study_isolation_device_settings ON public.device_settings USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- devices study_isolation_devices
CREATE POLICY study_isolation_devices ON public.devices USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- filtered_apps study_isolation_filtered_apps_delete
CREATE POLICY study_isolation_filtered_apps_delete ON public.filtered_apps FOR DELETE USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- filtered_apps study_isolation_filtered_apps_insert
CREATE POLICY study_isolation_filtered_apps_insert ON public.filtered_apps FOR INSERT WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- filtered_apps study_isolation_filtered_apps_select
CREATE POLICY study_isolation_filtered_apps_select ON public.filtered_apps FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- filtered_apps study_isolation_filtered_apps_update
CREATE POLICY study_isolation_filtered_apps_update ON public.filtered_apps FOR UPDATE USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- health_metrics study_isolation_health_metrics
CREATE POLICY study_isolation_health_metrics ON public.health_metrics USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- interaction_events study_isolation_interaction_events
CREATE POLICY study_isolation_interaction_events ON public.interaction_events USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- jobs study_isolation_jobs
CREATE POLICY study_isolation_jobs ON public.jobs USING (((study_id IS NULL) OR ((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))))) WITH CHECK (((study_id IS NULL) OR ((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))));

-- mobile_withdrawal_requests study_isolation_mobile_withdrawal_requests_insert
CREATE POLICY study_isolation_mobile_withdrawal_requests_insert ON public.mobile_withdrawal_requests FOR INSERT WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- mobile_withdrawal_requests study_isolation_mobile_withdrawal_requests_select
CREATE POLICY study_isolation_mobile_withdrawal_requests_select ON public.mobile_withdrawal_requests FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- notification_activity study_isolation_notification_activity
CREATE POLICY study_isolation_notification_activity ON public.notification_activity USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- notifications study_isolation_notifications
CREATE POLICY study_isolation_notifications ON public.notifications USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- organization_studies study_isolation_org_studies_delete
CREATE POLICY study_isolation_org_studies_delete ON public.organization_studies FOR DELETE USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- organization_studies study_isolation_org_studies_insert
CREATE POLICY study_isolation_org_studies_insert ON public.organization_studies FOR INSERT WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- organization_studies study_isolation_org_studies_select
CREATE POLICY study_isolation_org_studies_select ON public.organization_studies FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- organization_studies study_isolation_org_studies_update
CREATE POLICY study_isolation_org_studies_update ON public.organization_studies FOR UPDATE USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_form_access_codes study_isolation_participant_form_access_codes
CREATE POLICY study_isolation_participant_form_access_codes ON public.participant_form_access_codes USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_form_sessions study_isolation_participant_form_sessions
CREATE POLICY study_isolation_participant_form_sessions ON public.participant_form_sessions USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_form_submission_receipts study_isolation_participant_form_submission_receipts
CREATE POLICY study_isolation_participant_form_submission_receipts ON public.participant_form_submission_receipts USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_purge_cutoffs study_isolation_participant_purge_cutoffs
CREATE POLICY study_isolation_participant_purge_cutoffs ON public.participant_purge_cutoffs FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- participant_stats study_isolation_participant_stats
CREATE POLICY study_isolation_participant_stats ON public.participant_stats USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- questionnaire_submissions study_isolation_questionnaire_submissions
CREATE POLICY study_isolation_questionnaire_submissions ON public.questionnaire_submissions USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- questionnaires study_isolation_questionnaires
CREATE POLICY study_isolation_questionnaires ON public.questionnaires USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- ios_screen_time_usage study_isolation_ios_screen_time_usage
CREATE POLICY study_isolation_ios_screen_time_usage ON public.ios_screen_time_usage USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- sleep_events study_isolation_sleep_events
CREATE POLICY study_isolation_sleep_events ON public.sleep_events USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- study_limits study_isolation_study_limits_delete
CREATE POLICY study_isolation_study_limits_delete ON public.study_limits FOR DELETE USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- study_limits study_isolation_study_limits_insert
CREATE POLICY study_isolation_study_limits_insert ON public.study_limits FOR INSERT WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- study_limits study_isolation_study_limits_select
CREATE POLICY study_isolation_study_limits_select ON public.study_limits FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- study_limits study_isolation_study_limits_update
CREATE POLICY study_isolation_study_limits_update ON public.study_limits FOR UPDATE USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- study_participants study_isolation_study_participants
CREATE POLICY study_isolation_study_participants ON public.study_participants USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- time_use_diary_submissions study_isolation_tud_submissions
CREATE POLICY study_isolation_tud_submissions ON public.time_use_diary_submissions USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- time_use_diary_summarized study_isolation_tud_summarized
CREATE POLICY study_isolation_tud_summarized ON public.time_use_diary_summarized USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- upload_buffer study_isolation_upload_buffer
CREATE POLICY study_isolation_upload_buffer ON public.upload_buffer USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- upload_diagnostic_erasures study_isolation_upload_diagnostic_erasures
CREATE POLICY study_isolation_upload_diagnostic_erasures ON public.upload_diagnostic_erasures FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- upload_diagnostics study_isolation_upload_diagnostics
CREATE POLICY study_isolation_upload_diagnostics ON public.upload_diagnostics USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids())))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- chronicle_usage_events study_isolation_usage_events
CREATE POLICY study_isolation_usage_events ON public.chronicle_usage_events USING (((SELECT public.chronicle_rls_is_admin()) OR ((study_id)::text IN (SELECT authorized.study_id::text FROM public.chronicle_authorized_study_ids() authorized(study_id))))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR ((study_id)::text IN (SELECT authorized.study_id::text FROM public.chronicle_authorized_study_ids() authorized(study_id)))));

-- chronicle_usage_stats study_isolation_usage_stats
CREATE POLICY study_isolation_usage_stats ON public.chronicle_usage_stats USING (((SELECT public.chronicle_rls_is_admin()) OR ((study_id)::text IN (SELECT authorized.study_id::text FROM public.chronicle_authorized_study_ids() authorized(study_id))))) WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR ((study_id)::text IN (SELECT authorized.study_id::text FROM public.chronicle_authorized_study_ids() authorized(study_id)))));

-- study_lifecycle_events
ALTER TABLE public.study_lifecycle_events ENABLE ROW LEVEL SECURITY;

-- study_lifecycle_events study_lifecycle_events_insert_policy
CREATE POLICY study_lifecycle_events_insert_policy ON public.study_lifecycle_events FOR INSERT WITH CHECK (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- study_lifecycle_events study_lifecycle_events_select_policy
CREATE POLICY study_lifecycle_events_select_policy ON public.study_lifecycle_events FOR SELECT USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- study_limits
ALTER TABLE public.study_limits ENABLE ROW LEVEL SECURITY;

-- study_participants
ALTER TABLE public.study_participants ENABLE ROW LEVEL SECURITY;

-- study_settings_audit
ALTER TABLE public.study_settings_audit ENABLE ROW LEVEL SECURITY;

-- study_settings_audit study_settings_audit_insert
CREATE POLICY study_settings_audit_insert ON public.study_settings_audit FOR INSERT WITH CHECK (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- study_settings_audit study_settings_audit_no_delete
CREATE POLICY study_settings_audit_no_delete ON public.study_settings_audit FOR DELETE USING (false);

-- study_settings_audit study_settings_audit_no_update
CREATE POLICY study_settings_audit_no_update ON public.study_settings_audit FOR UPDATE USING (false);

-- study_settings_audit study_settings_audit_select
CREATE POLICY study_settings_audit_select ON public.study_settings_audit FOR SELECT USING (((SELECT public.chronicle_rls_is_admin()) OR (study_id IN (SELECT public.chronicle_authorized_study_ids()))));

-- time_use_diary_submissions
ALTER TABLE public.time_use_diary_submissions ENABLE ROW LEVEL SECURITY;

-- time_use_diary_summarized
ALTER TABLE public.time_use_diary_summarized ENABLE ROW LEVEL SECURITY;

-- data_collection_settings_revisions trigger_only_data_collection_settings_revision_insert
CREATE POLICY trigger_only_data_collection_settings_revision_insert ON public.data_collection_settings_revisions FOR INSERT WITH CHECK ((pg_trigger_depth() > 0));

-- upload_diagnostic_erasures trigger_only_upload_diagnostic_erasure_insert
CREATE POLICY trigger_only_upload_diagnostic_erasure_insert ON public.upload_diagnostic_erasures FOR INSERT WITH CHECK ((pg_trigger_depth() > 0));

-- upload_buffer
ALTER TABLE public.upload_buffer ENABLE ROW LEVEL SECURITY;

-- upload_diagnostic_erasures
ALTER TABLE public.upload_diagnostic_erasures ENABLE ROW LEVEL SECURITY;

-- upload_diagnostics
ALTER TABLE public.upload_diagnostics ENABLE ROW LEVEL SECURITY;

-- webhook_deliveries
ALTER TABLE public.webhook_deliveries ENABLE ROW LEVEL SECURITY;

-- webhook_deliveries webhook_deliveries_policy
CREATE POLICY webhook_deliveries_policy ON public.webhook_deliveries USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR (webhook_id IN ( SELECT webhook_registrations.webhook_id
   FROM public.webhook_registrations
  WHERE ((webhook_registrations.study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))))));

-- webhook_registrations
ALTER TABLE public.webhook_registrations ENABLE ROW LEVEL SECURITY;

-- webhook_registrations webhook_registrations_policy
CREATE POLICY webhook_registrations_policy ON public.webhook_registrations USING (((current_setting('app.is_admin'::text, true) = 'true'::text) OR ((study_id)::text = ANY (string_to_array(current_setting('app.authorized_studies'::text, true), ','::text)))));

-- Privileges

-- FUNCTION chronicle_assert_acl_permanent_owner(target_acl_key uuid[])
REVOKE ALL ON FUNCTION public.chronicle_assert_acl_permanent_owner(target_acl_key uuid[]) FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_assert_acl_permanent_owner(target_acl_key uuid[]) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_assert_acl_permanent_owner(target_acl_key uuid[]) TO chronicle_admin;

-- FUNCTION chronicle_bind_webhook_delivery_scope()
REVOKE ALL ON FUNCTION public.chronicle_bind_webhook_delivery_scope() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_bind_webhook_delivery_scope() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_bind_webhook_delivery_scope() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_bind_webhook_delivery_scope() TO chronicle_admin;

-- FUNCTION chronicle_count_study_rows_for_erasure(target_table text, target_study_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_count_study_rows_for_erasure(target_table text, target_study_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_count_study_rows_for_erasure(target_table text, target_study_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_count_study_rows_for_erasure(target_table text, target_study_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_count_study_rows_for_erasure(target_table text, target_study_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_delete_study_rows(target_table text, target_study_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_delete_study_rows(target_table text, target_study_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_delete_study_rows(target_table text, target_study_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_delete_study_rows(target_table text, target_study_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_delete_study_rows(target_table text, target_study_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_discover_study_erasure_tables(target_study_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_discover_study_erasure_tables(target_study_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_discover_study_erasure_tables(target_study_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_discover_study_erasure_tables(target_study_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_discover_study_erasure_tables(target_study_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_enrollment_acknowledged(p_access_code_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_enrollment_acknowledged(p_access_code_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_enrollment_acknowledged(p_access_code_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_enrollment_acknowledged(p_access_code_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_guard_job_against_erasure()
REVOKE ALL ON FUNCTION public.chronicle_guard_job_against_erasure() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_guard_job_against_erasure() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_guard_job_against_erasure() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_guard_job_against_erasure() TO chronicle_admin;

-- FUNCTION chronicle_guard_job_scope_columns()
REVOKE ALL ON FUNCTION public.chronicle_guard_job_scope_columns() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_guard_job_scope_columns() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_guard_job_scope_columns() TO chronicle_admin;

-- FUNCTION chronicle_guard_participant_mutation()
REVOKE ALL ON FUNCTION public.chronicle_guard_participant_mutation() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_guard_participant_mutation() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_guard_participant_mutation() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_guard_participant_mutation() TO chronicle_admin;

-- FUNCTION chronicle_has_candidate_access(check_candidate_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_has_candidate_access(check_candidate_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_has_candidate_access(check_candidate_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_has_candidate_access(check_candidate_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_has_candidate_write_access(check_candidate_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_has_candidate_write_access(check_candidate_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_has_candidate_write_access(check_candidate_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_has_candidate_write_access(check_candidate_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_has_participant_erasure(p_study_id uuid, p_participant_id text)
REVOKE ALL ON FUNCTION public.chronicle_has_participant_erasure(p_study_id uuid, p_participant_id text) FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_has_participant_erasure(p_study_id uuid, p_participant_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_has_participant_erasure(p_study_id uuid, p_participant_id text) TO chronicle_admin;

-- FUNCTION chronicle_is_deletion_worker()
REVOKE ALL ON FUNCTION public.chronicle_is_deletion_worker() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_is_deletion_worker() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_is_deletion_worker() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_is_deletion_worker() TO chronicle_admin;
GRANT ALL ON FUNCTION public.chronicle_is_deletion_worker() TO chronicle;

-- FUNCTION chronicle_is_eligible_permanent_acl_owner(target_acl_key uuid[], target_principal_type text, target_principal_id text)
REVOKE ALL ON FUNCTION public.chronicle_is_eligible_permanent_acl_owner(target_acl_key uuid[], target_principal_type text, target_principal_id text) FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_is_eligible_permanent_acl_owner(target_acl_key uuid[], target_principal_type text, target_principal_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_is_eligible_permanent_acl_owner(target_acl_key uuid[], target_principal_type text, target_principal_id text) TO chronicle_admin;

-- FUNCTION chronicle_lock_acl_owner_mutation()
REVOKE ALL ON FUNCTION public.chronicle_lock_acl_owner_mutation() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_lock_acl_owner_mutation() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_lock_acl_owner_mutation() TO chronicle_admin;

-- FUNCTION chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid)
REVOKE ALL ON FUNCTION public.chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid) TO chronicle_admin;

-- FUNCTION chronicle_participant_block_token(p_study_id uuid, p_participant_id text)
REVOKE ALL ON FUNCTION public.chronicle_participant_block_token(p_study_id uuid, p_participant_id text) FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_participant_block_token(p_study_id uuid, p_participant_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_participant_block_token(p_study_id uuid, p_participant_id text) TO chronicle_admin;
GRANT EXECUTE ON FUNCTION public.chronicle_participant_block_token(uuid, text) TO chronicle;

-- FUNCTION chronicle_participant_data_visible(check_study_id text, check_participant_id text)
REVOKE ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id text, check_participant_id text) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id text, check_participant_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id text, check_participant_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id text, check_participant_id text) TO chronicle_admin;

-- FUNCTION chronicle_participant_data_visible(check_study_id uuid, check_participant_id text)
REVOKE ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id uuid, check_participant_id text) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id uuid, check_participant_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id uuid, check_participant_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_participant_data_visible(check_study_id uuid, check_participant_id text) TO chronicle_admin;

-- FUNCTION chronicle_participant_mutation_allowed(check_study_id text, check_participant_id text)
REVOKE ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id text, check_participant_id text) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id text, check_participant_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id text, check_participant_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id text, check_participant_id text) TO chronicle_admin;

-- FUNCTION chronicle_participant_mutation_allowed(check_study_id uuid, check_participant_id text)
REVOKE ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id uuid, check_participant_id text) FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id uuid, check_participant_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id uuid, check_participant_id text) TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_participant_mutation_allowed(check_study_id uuid, check_participant_id text) TO chronicle_admin;

-- FUNCTION chronicle_protect_last_active_acl_owner()
REVOKE ALL ON FUNCTION public.chronicle_protect_last_active_acl_owner() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_protect_last_active_acl_owner() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_protect_last_active_acl_owner() TO chronicle_admin;

-- FUNCTION chronicle_protect_securable_object_owner()
REVOKE ALL ON FUNCTION public.chronicle_protect_securable_object_owner() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_protect_securable_object_owner() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_protect_securable_object_owner() TO chronicle_admin;

-- FUNCTION chronicle_record_capture_cutoff()
REVOKE ALL ON FUNCTION public.chronicle_record_capture_cutoff() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_record_capture_cutoff() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_record_capture_cutoff() TO chronicle_admin;

-- FUNCTION chronicle_record_erased_diagnostic_ids()
REVOKE ALL ON FUNCTION public.chronicle_record_erased_diagnostic_ids() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_record_erased_diagnostic_ids() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_record_erased_diagnostic_ids() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_record_erased_diagnostic_ids() TO chronicle_admin;

-- FUNCTION chronicle_reject_restore_continuity_receipt_mutation()
REVOKE ALL ON FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_reject_restore_continuity_receipt_mutation() TO chronicle_admin;

-- FUNCTION chronicle_advance_disclosure_generation()
REVOKE ALL ON FUNCTION public.chronicle_advance_disclosure_generation() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_advance_disclosure_generation() TO chronicle_app, chronicle_admin;

-- FUNCTION chronicle_set_deletion_block_token()
REVOKE ALL ON FUNCTION public.chronicle_set_deletion_block_token() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.chronicle_set_deletion_block_token() FROM PUBLIC;
GRANT ALL ON FUNCTION public.chronicle_set_deletion_block_token() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_set_deletion_block_token() TO chronicle_admin;

-- FUNCTION chronicle_stop_withdrawn_participant_collection()
REVOKE ALL ON FUNCTION public.chronicle_stop_withdrawn_participant_collection() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_stop_withdrawn_participant_collection() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_stop_withdrawn_participant_collection() TO chronicle_admin;

-- FUNCTION chronicle_sync_job_scope()
REVOKE ALL ON FUNCTION public.chronicle_sync_job_scope() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_sync_job_scope() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_sync_job_scope() TO chronicle_admin;

-- FUNCTION chronicle_validate_job_status_transition()
REVOKE ALL ON FUNCTION public.chronicle_validate_job_status_transition() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.chronicle_validate_job_status_transition() TO chronicle_app;
GRANT ALL ON FUNCTION public.chronicle_validate_job_status_transition() TO chronicle_admin;

-- FUNCTION forward_legacy_audit_buffer_insert()
REVOKE ALL ON FUNCTION public.forward_legacy_audit_buffer_insert() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.forward_legacy_audit_buffer_insert() TO chronicle_app;
GRANT ALL ON FUNCTION public.forward_legacy_audit_buffer_insert() TO chronicle_admin;

-- FUNCTION prevent_audit_modification()
REVOKE ALL ON FUNCTION public.prevent_audit_modification() FROM chronicle_app, chronicle_admin, chronicle;
GRANT ALL ON FUNCTION public.prevent_audit_modification() TO chronicle_app;
GRANT ALL ON FUNCTION public.prevent_audit_modification() TO chronicle_admin;

-- FUNCTION record_data_collection_settings_revision()
REVOKE ALL ON FUNCTION public.record_data_collection_settings_revision() FROM chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON FUNCTION public.record_data_collection_settings_revision() FROM PUBLIC;

-- Runtime requests never read or modify the migration runner's global ledger.
REVOKE ALL ON TABLE public.flyway_schema_history FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;

-- TABLE audit
REVOKE ALL ON TABLE public.audit FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT INSERT ON TABLE public.audit TO chronicle_app;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.audit TO chronicle_admin;

-- TABLE audit_buffer
REVOKE ALL ON TABLE public.audit_buffer FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT INSERT ON TABLE public.audit_buffer TO chronicle_app;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.audit_buffer TO chronicle_admin;

-- TABLE data_collection_settings_revisions
REVOKE ALL ON TABLE public.data_collection_settings_revisions FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT ON TABLE public.data_collection_settings_revisions TO chronicle_app;
GRANT SELECT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.data_collection_settings_revisions TO chronicle_admin;
GRANT SELECT ON TABLE public.data_collection_settings_revisions TO chronicle;

-- TABLE data_deletion_audit_outbox
REVOKE ALL ON TABLE public.data_deletion_audit_outbox FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,UPDATE ON TABLE public.data_deletion_audit_outbox TO chronicle_app;
GRANT ALL ON TABLE public.data_deletion_audit_outbox TO chronicle_admin;

-- COLUMN data_deletion_audit_outbox.available_at
GRANT UPDATE(available_at) ON TABLE public.data_deletion_audit_outbox TO chronicle_app;

-- COLUMN data_deletion_audit_outbox.publish_attempt_count
GRANT UPDATE(publish_attempt_count) ON TABLE public.data_deletion_audit_outbox TO chronicle_app;

-- COLUMN data_deletion_audit_outbox.lease_token
GRANT UPDATE(lease_token) ON TABLE public.data_deletion_audit_outbox TO chronicle_app;

-- COLUMN data_deletion_audit_outbox.lease_expires_at
GRANT UPDATE(lease_expires_at) ON TABLE public.data_deletion_audit_outbox TO chronicle_app;

-- COLUMN data_deletion_audit_outbox.published_at
GRANT UPDATE(published_at) ON TABLE public.data_deletion_audit_outbox TO chronicle_app;

-- COLUMN data_deletion_audit_outbox.last_error_code
GRANT UPDATE(last_error_code) ON TABLE public.data_deletion_audit_outbox TO chronicle_app;

-- TABLE data_deletion_form_access_revocations
REVOKE ALL ON TABLE public.data_deletion_form_access_revocations FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.data_deletion_form_access_revocations TO chronicle_app;
GRANT ALL ON TABLE public.data_deletion_form_access_revocations TO chronicle_admin;

-- TABLE data_deletion_operations
REVOKE ALL ON TABLE public.data_deletion_operations FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.data_deletion_operations TO chronicle_app;
GRANT ALL ON TABLE public.data_deletion_operations TO chronicle_admin;

-- TABLE data_deletion_steps
REVOKE ALL ON TABLE public.data_deletion_steps FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.data_deletion_steps TO chronicle_app;
GRANT ALL ON TABLE public.data_deletion_steps TO chronicle_admin;

-- TABLE data_deletion_tombstones
REVOKE ALL ON TABLE public.data_deletion_tombstones FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.data_deletion_tombstones TO chronicle_app;
GRANT ALL ON TABLE public.data_deletion_tombstones TO chronicle_admin;

-- TABLE cancelled_enrollment_attempts
REVOKE ALL ON TABLE public.cancelled_enrollment_attempts FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT ON TABLE public.cancelled_enrollment_attempts TO chronicle_app;
GRANT ALL ON TABLE public.cancelled_enrollment_attempts TO chronicle_admin;
GRANT SELECT,INSERT ON TABLE public.cancelled_enrollment_attempts TO chronicle;

-- TABLE erased_device_key_tombstones
REVOKE ALL ON TABLE public.erased_device_key_tombstones FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.erased_device_key_tombstones TO chronicle_app;
GRANT ALL ON TABLE public.erased_device_key_tombstones TO chronicle_admin;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.erased_device_key_tombstones TO chronicle;

-- TABLE participant_form_access_codes
REVOKE ALL ON TABLE public.participant_form_access_codes FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.participant_form_access_codes TO chronicle_app;
GRANT ALL ON TABLE public.participant_form_access_codes TO chronicle_admin;

-- TABLE exchanged_enrollment_access_codes
REVOKE ALL ON TABLE public.exchanged_enrollment_access_codes FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.exchanged_enrollment_access_codes TO chronicle_app;
GRANT ALL ON TABLE public.exchanged_enrollment_access_codes TO chronicle_admin;

-- TABLE jwt_session_state
REVOKE ALL ON TABLE public.jwt_session_state FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.jwt_session_state TO chronicle_app;
GRANT ALL ON TABLE public.jwt_session_state TO chronicle_admin;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.jwt_session_state TO chronicle;

-- TABLE mobile_withdrawal_requests
REVOKE ALL ON TABLE public.mobile_withdrawal_requests FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT ON TABLE public.mobile_withdrawal_requests TO chronicle_app;
GRANT SELECT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.mobile_withdrawal_requests TO chronicle_admin;
GRANT SELECT,INSERT ON TABLE public.mobile_withdrawal_requests TO chronicle;

-- TABLE participant_collection_acknowledgment
REVOKE ALL ON TABLE public.participant_collection_acknowledgment FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT ON TABLE public.participant_collection_acknowledgment TO chronicle_app;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.participant_collection_acknowledgment TO chronicle_admin;
GRANT SELECT,INSERT ON TABLE public.participant_collection_acknowledgment TO chronicle;

-- TABLE participant_form_sessions
REVOKE ALL ON TABLE public.participant_form_sessions FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.participant_form_sessions TO chronicle_app;
GRANT ALL ON TABLE public.participant_form_sessions TO chronicle_admin;

-- TABLE participant_form_submission_receipts
REVOKE ALL ON TABLE public.participant_form_submission_receipts FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.participant_form_submission_receipts TO chronicle_app;
GRANT ALL ON TABLE public.participant_form_submission_receipts TO chronicle_admin;

-- TABLE participant_purge_cutoffs
REVOKE ALL ON TABLE public.participant_purge_cutoffs FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.participant_purge_cutoffs TO chronicle_app;
GRANT ALL ON TABLE public.participant_purge_cutoffs TO chronicle_admin;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.participant_purge_cutoffs TO chronicle;

-- TABLE pending_enrollment_access_codes
REVOKE ALL ON TABLE public.pending_enrollment_access_codes FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.pending_enrollment_access_codes TO chronicle_app;
GRANT ALL ON TABLE public.pending_enrollment_access_codes TO chronicle_admin;

-- TABLE researcher_create_receipts
REVOKE ALL ON TABLE public.researcher_create_receipts FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.researcher_create_receipts TO chronicle_app;
GRANT ALL ON TABLE public.researcher_create_receipts TO chronicle_admin;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.researcher_create_receipts TO chronicle;



-- TABLE restore_continuity_reconciliations
REVOKE ALL ON TABLE public.restore_continuity_reconciliations FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.restore_continuity_reconciliations TO chronicle_admin;


-- TABLE screen_time_usage_deltas
REVOKE ALL ON TABLE public.screen_time_usage_deltas FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.screen_time_usage_deltas TO chronicle_app;
GRANT ALL ON TABLE public.screen_time_usage_deltas TO chronicle_admin;
GRANT SELECT ON TABLE public.screen_time_usage_deltas TO chronicle;

-- TABLE study_participants_with_withdrawals
REVOKE ALL ON TABLE public.study_participants_with_withdrawals FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.study_participants_with_withdrawals TO chronicle_app;
GRANT ALL ON TABLE public.study_participants_with_withdrawals TO chronicle_admin;
GRANT SELECT ON TABLE public.study_participants_with_withdrawals TO chronicle;

-- TABLE study_settings_audit
REVOKE ALL ON TABLE public.study_settings_audit FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
REVOKE ALL ON TABLE public.study_settings_audit FROM CURRENT_USER;
GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.study_settings_audit TO CURRENT_USER;
GRANT SELECT,INSERT ON TABLE public.study_settings_audit TO chronicle_app;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,MAINTAIN ON TABLE public.study_settings_audit TO chronicle_admin;
GRANT SELECT,INSERT ON TABLE public.study_settings_audit TO chronicle;

-- TABLE upload_diagnostic_erasures
REVOKE ALL ON TABLE public.upload_diagnostic_erasures FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.upload_diagnostic_erasures TO chronicle_app;
GRANT ALL ON TABLE public.upload_diagnostic_erasures TO chronicle_admin;
GRANT SELECT ON TABLE public.upload_diagnostic_erasures TO chronicle;

-- TABLE upload_diagnostics
REVOKE ALL ON TABLE public.upload_diagnostics FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.upload_diagnostics TO chronicle_app;
GRANT ALL ON TABLE public.upload_diagnostics TO chronicle_admin;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.upload_diagnostics TO chronicle;

-- TABLE webhook_deliveries
REVOKE ALL ON TABLE public.webhook_deliveries FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.webhook_deliveries TO chronicle_app;
GRANT ALL ON TABLE public.webhook_deliveries TO chronicle_admin;

-- TABLE webhook_registrations
REVOKE ALL ON TABLE public.webhook_registrations FROM PUBLIC, chronicle_app, chronicle_admin, chronicle;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.webhook_registrations TO chronicle_app;
GRANT ALL ON TABLE public.webhook_registrations TO chronicle_admin;

-- Comments

-- TABLE data_collection_settings_revisions
COMMENT ON TABLE public.data_collection_settings_revisions IS 'Immutable server-issued DataCollection revisions recorded transactionally with studies.settings for delayed consent validation.';

-- TABLE cancelled_enrollment_attempts
COMMENT ON TABLE public.cancelled_enrollment_attempts IS 'Ids of enrollment attempts cancelled before their enrollment acknowledgment; a cancelled attempt never binds or installs its key. Holds no study, participant or device identifier.';

-- TABLE erased_device_key_tombstones
COMMENT ON TABLE public.erased_device_key_tombstones IS 'Hash, erasure kind and expiry of device API keys deleted by participant or study erasure; holds no study, participant or random device identifier.';

-- VIEW exchanged_enrollment_access_codes
COMMENT ON VIEW public.exchanged_enrollment_access_codes IS 'Exchanged enrollment evidence retained for device acknowledgments after collected-data purge.';

-- TABLE mobile_withdrawal_requests
COMMENT ON TABLE public.mobile_withdrawal_requests IS 'Immutable mobile self-withdrawal intent binding one request UUID to one exact API-key subject tuple before credential revocation. Retained through participant erasure for replay; deleted by full study erasure.';

-- COLUMN mobile_withdrawal_requests.api_key_id
COMMENT ON COLUMN public.mobile_withdrawal_requests.api_key_id IS 'Copied UUID evidence of the authorizing credential; intentionally has no FK so credential erasure cannot invalidate or block the durable receipt.';

-- TABLE participant_collection_acknowledgment
COMMENT ON TABLE public.participant_collection_acknowledgment IS 'Append-only participant collection-module acknowledgment trail. DELETE and UPDATE revoked from all application roles for HIPAA-grade tamper evidence. Only the postgres superuser can purge records.';

-- COLUMN participant_collection_acknowledgment.settings_version
COMMENT ON COLUMN public.participant_collection_acknowledgment.settings_version IS 'Server-controlled DataCollection settings revision whose policy the participant accepted or declined; null for legacy clients.';

-- COLUMN participant_collection_acknowledgment.disclosure_version
COMMENT ON COLUMN public.participant_collection_acknowledgment.disclosure_version IS 'Study consent/disclosure version presented with this participant decision; paired with manifest_digest.';

-- COLUMN participant_collection_acknowledgment.manifest_digest
COMMENT ON COLUMN public.participant_collection_acknowledgment.manifest_digest IS 'Lowercase SHA-256 digest of the authoritative enrollment manifest the participant reviewed; paired with disclosure_version.';

-- COLUMN participant_collection_acknowledgment.declined_modules
COMMENT ON COLUMN public.participant_collection_acknowledgment.declined_modules IS 'JSON array of collection module wire-ids the participant declined in this snapshot (per-module consent design §3.3).';

-- COLUMN participant_collection_acknowledgment.unavailable_modules
COMMENT ON COLUMN public.participant_collection_acknowledgment.unavailable_modules IS 'Enabled per-sensor modules physically unavailable on this device; capability evidence, not a decline.';

-- COLUMN participant_collection_acknowledgment.evidence_access_code_id
COMMENT ON COLUMN public.participant_collection_acknowledgment.evidence_access_code_id IS 'Copied UUID of the enrollment invitation receipt whose authoritative disclosure and module scope were validated; intentionally has no FK because consent evidence outlives erasable enrollment rows.';

-- COLUMN participant_collection_acknowledgment.evidence_api_key_id
COMMENT ON COLUMN public.participant_collection_acknowledgment.evidence_api_key_id IS 'Copied UUID of the exact mobile API key installation tied to the enrollment receipt and reporting device; intentionally has no FK because consent evidence outlives erasable credentials.';

-- COLUMN participant_collection_acknowledgment.decision_seq
COMMENT ON COLUMN public.participant_collection_acknowledgment.decision_seq IS 'Per-enrollment-key decision sequence; the phone assigns it in the decision transaction (the server assigns the next one for a client that sends none). Orders decisions and makes a retried acknowledgment idempotent.';

-- COLUMN participant_collection_acknowledgment.collection_trigger
COMMENT ON COLUMN public.participant_collection_acknowledgment.collection_trigger IS 'What produced this decision: ENROLLMENT | PARTICIPANT_TOGGLE | SETTINGS_CHANGE | WITHDRAWAL.';

-- VIEW pending_enrollment_access_codes
COMMENT ON VIEW public.pending_enrollment_access_codes IS 'Bound enrollment attempts with an open replay window and no completed enrollment acknowledgment.';

-- TABLE researcher_create_receipts
COMMENT ON TABLE public.researcher_create_receipts IS 'Atomic create receipts; raw credential results are encrypted with server secret and authenticated scope.';



-- TABLE restore_continuity_reconciliations
COMMENT ON TABLE public.restore_continuity_reconciliations IS 'Immutable receipt proving that post-backup withdrawals, credential revocations, deletion evidence, collection-settings authority, and enrollment-invitation state were reconciled before a restored backend became ready.';

-- VIEW screen_time_usage_deltas
COMMENT ON VIEW public.screen_time_usage_deltas IS 'UTC-safe intervals and deltas derived from raw cumulative hourly iOS DeviceActivity snapshots; malformed non-hourly rows remain raw in ios_screen_time_usage but are excluded here.';

-- COLUMN screen_time_usage_deltas.captured_at
COMMENT ON COLUMN public.screen_time_usage_deltas.captured_at IS 'Actual collection instant. It is not a bucket boundary and must not be rounded for ordering.';

-- COLUMN screen_time_usage_deltas.bucket_start_utc
COMMENT ON COLUMN public.screen_time_usage_deltas.bucket_start_utc IS 'Apple DeviceActivity segment start as an absolute timestamptz; distinct DST fall-back hours remain distinct.';

-- COLUMN screen_time_usage_deltas.interval_start_utc
COMMENT ON COLUMN public.screen_time_usage_deltas.interval_start_utc IS 'Inclusive start of the derived observation interval.';

-- COLUMN screen_time_usage_deltas.interval_end_utc
COMMENT ON COLUMN public.screen_time_usage_deltas.interval_end_utc IS 'Exclusive end of the derived observation interval.';

-- COLUMN screen_time_usage_deltas.delta_status
COMMENT ON COLUMN public.screen_time_usage_deltas.delta_status IS 'ok, or counter_decreased when an Apple cumulative counter moved backward; affected negative deltas are NULL.';

-- COLUMN study_deletion_schedule.operation_id
COMMENT ON COLUMN public.study_deletion_schedule.operation_id IS 'Durable study-erasure operation linked atomically when the schedule is created or adopted';

-- COLUMN study_deletion_schedule.previous_status
COMMENT ON COLUMN public.study_deletion_schedule.previous_status IS 'Lifecycle status restored when a not-yet-started study erasure is cancelled';

-- TABLE study_settings_audit
COMMENT ON TABLE public.study_settings_audit IS 'Append-only study settings audit trail. DELETE and UPDATE revoked from all application roles for HIPAA-grade tamper evidence. Only the postgres superuser can purge records.';

-- INDEX participant_collection_ack_one_decision_per_seq
COMMENT ON INDEX public.participant_collection_ack_one_decision_per_seq IS 'One decision per enrollment key and decision sequence; orders the collection halt gate and makes a retried acknowledgment idempotent.';
