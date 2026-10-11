CREATE FUNCTION scrub_cancelled_ios_device_names(p_study_id TEXT, p_participant_id TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    previous_admin TEXT := current_setting('app.is_admin', true);
BEGIN
    PERFORM set_config('app.is_admin', 'true', true);
    WITH names AS MATERIALIZED (
        SELECT DISTINCT s.study_id, s.participant_id, s.device_name
        FROM public.sensor_data s
        WHERE s.study_id::text = p_study_id
          AND (p_participant_id IS NULL OR s.participant_id = p_participant_id)
          AND public.chronicle_participant_mutation_allowed(s.study_id::text, s.participant_id)
    ), device_keys AS MATERIALIZED (
        SELECT names.*, CASE WHEN names.device_name ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN names.device_name ELSE COALESCE((
            SELECT d.device_id::text FROM public.devices d
            WHERE d.study_id::text = names.study_id::text AND d.participant_id = names.participant_id
              AND d.device_type = 'Ios' AND d.source_device ->> 'name' = names.device_name
            ORDER BY d.device_id LIMIT 1
        ), gen_random_uuid()::text) END AS device_key
        FROM names
    )
    UPDATE public.sensor_data s
    SET device_name = k.device_key,
        device_system_name = CASE WHEN s.device_system_name = s.device_name THEN '' ELSE s.device_system_name END
    FROM device_keys k
    WHERE s.study_id = k.study_id AND s.participant_id = k.participant_id AND s.device_name = k.device_name;

    UPDATE public.devices d SET source_device = source_device - 'name'
    WHERE d.study_id::text = p_study_id
      AND (p_participant_id IS NULL OR d.participant_id = p_participant_id)
      AND d.device_type = 'Ios' AND d.source_device ? 'name'
      AND public.chronicle_participant_mutation_allowed(d.study_id::text, d.participant_id);
    PERFORM set_config('app.is_admin', COALESCE(previous_admin, ''), true);
END $$;

REVOKE ALL ON FUNCTION scrub_cancelled_ios_device_names(TEXT, TEXT) FROM PUBLIC;

CREATE FUNCTION scrub_cancelled_ios_device_names()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF NEW.status = 'CANCELLED' THEN
        PERFORM public.scrub_cancelled_ios_device_names(NEW.study_id::text, NEW.participant_id);
    END IF;
    RETURN NEW;
END $$;

REVOKE ALL ON FUNCTION scrub_cancelled_ios_device_names() FROM PUBLIC;
CREATE TRIGGER scrub_cancelled_ios_device_names
AFTER UPDATE OF status ON public.data_deletion_operations
FOR EACH ROW EXECUTE FUNCTION scrub_cancelled_ios_device_names();

-- Repair participants whose cancellation preceded this forward migration as well.
DO $$
DECLARE
    previous_admin TEXT := current_setting('app.is_admin', true);
    operation RECORD;
BEGIN
    PERFORM set_config('app.is_admin', 'true', true);
    FOR operation IN SELECT DISTINCT study_id, participant_id
        FROM public.data_deletion_operations WHERE status = 'CANCELLED'
    LOOP
        PERFORM public.scrub_cancelled_ios_device_names(operation.study_id::text, operation.participant_id);
    END LOOP;
    PERFORM set_config('app.is_admin', COALESCE(previous_admin, ''), true);
END $$;
