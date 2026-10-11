-- V76's erasure definer owns the narrow, leased worker check. Runtime roles keep no DELETE grant.
DO $$
DECLARE
    erasure_owner TEXT;
    target_table TEXT;
BEGIN
    SELECT pg_get_userbyid(proowner) INTO erasure_owner
    FROM pg_proc WHERE oid = 'public.chronicle_delete_study_rows(text,uuid)'::regprocedure;
    FOREACH target_table IN ARRAY ARRAY['data_collection_settings_revisions', 'mobile_withdrawal_requests'] LOOP
        EXECUTE format('GRANT SELECT, DELETE ON public.%I TO %I', target_table, erasure_owner);
        EXECUTE format(
            'CREATE POLICY erasure_worker_delete ON public.%I FOR DELETE TO %I USING (public.chronicle_is_deletion_worker())',
            target_table, erasure_owner
        );
        -- DELETE also needs SELECT visibility when the trusted owner is not a superuser.
        EXECUTE format(
            'CREATE POLICY erasure_worker_select ON public.%I FOR SELECT TO %I USING (public.chronicle_is_deletion_worker())',
            target_table, erasure_owner
        );
    END LOOP;
END $$;
