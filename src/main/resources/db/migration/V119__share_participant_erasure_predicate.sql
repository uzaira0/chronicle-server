SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Runtime fences share the durable participant token and preserve the caller's RLS visibility.
CREATE FUNCTION chronicle_participant_block_token(p_study_id UUID, p_participant_id TEXT)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog
AS $$
    SELECT md5(p_study_id::text || ':' || p_participant_id)
$$;

CREATE FUNCTION chronicle_has_participant_erasure(p_study_id UUID, p_participant_id TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.data_deletion_operations erasure
        WHERE erasure.study_id = p_study_id AND erasure.status NOT IN ('PREVIEW', 'CANCELLED')
          AND (erasure.mode = 'STUDY_ERASURE' OR
               (erasure.mode = 'WITHDRAW_AND_ERASE' AND erasure.participant_block_token =
                    public.chronicle_participant_block_token(p_study_id, p_participant_id)))
    )
$$;
