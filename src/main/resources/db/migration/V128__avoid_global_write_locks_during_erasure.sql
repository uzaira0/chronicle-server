-- The per-study exclusive fence and mutation barrier protect data; only DDL needs a table lock.
CREATE OR REPLACE FUNCTION chronicle_lock_study_table_for_erasure(
    target_table TEXT,
    target_study_id UUID
) RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
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
$$;

