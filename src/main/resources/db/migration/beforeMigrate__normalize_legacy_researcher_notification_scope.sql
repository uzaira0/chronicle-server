-- V74 backfills job scope with strict participant validation. Earlier researcher
-- notifications used an empty scalar participantId for an absent participant.
-- Normalize only that definition before the published migration runs; preserving
-- migration bytes also preserves existing Flyway checksums. Once V74 has added
-- participant_ids this callback is a no-op.
DO $$
BEGIN
    IF to_regclass('public.jobs') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM pg_attribute
           WHERE attrelid = to_regclass('public.jobs')
             AND attname = 'participant_ids'
             AND NOT attisdropped
       )
    THEN
        UPDATE public.jobs
        SET definition = definition - 'participantId'
        WHERE COALESCE(definition ->> '@type', definition ->> '@class', '') IN (
                  'Notification',
                  'com.openlattice.chronicle.services.notifications.Notification'
              )
          AND NOT (definition ? 'participantIds')
          AND jsonb_typeof(definition -> 'participantId') = 'string'
          AND btrim(definition ->> 'participantId') = '';
    END IF;
END
$$;
