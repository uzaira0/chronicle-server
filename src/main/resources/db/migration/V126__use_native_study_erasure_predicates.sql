-- Preserve native study_id indexes for both UUID and text-backed tables.
CREATE OR REPLACE FUNCTION chronicle_delete_study_rows(
    target_table TEXT,
    target_study_id UUID
) RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
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
           'audit_logs',
           'study_settings_audit',
           'participant_collection_acknowledgment',
           'study_lifecycle_events',
           'data_deletion_operations',
           'data_deletion_steps',
           'retention_holds',
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
$$;

CREATE OR REPLACE FUNCTION chronicle_count_study_rows_for_erasure(
    target_table TEXT,
    target_study_id UUID
) RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
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
           'audit_logs',
           'study_settings_audit',
           'participant_collection_acknowledgment',
           'study_lifecycle_events',
           'data_deletion_operations',
           'data_deletion_steps',
           'retention_holds',
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
$$;

