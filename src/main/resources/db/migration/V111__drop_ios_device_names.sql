-- iOS apps sent the user-assigned device name ("Alex's iPhone"), which identifies the
-- participant. It was stored in devices.source_device ->> 'name', in sensor_data.device_name,
-- and (by a column-mapping bug) in sensor_data.device_system_name. The backend no longer
-- stores it: device_name now holds the enrolled device id, which keeps one participant's
-- devices apart for screen_time_usage_deltas. Existing rows get the same treatment.
--
-- Both tables FORCE row level security. A superuser bypasses it; a non-superuser owner gets
-- study visibility from the policies' own admin setting, transaction-local. No DDL here: an
-- ALTER TABLE would queue behind live sensor writes and then block them. Participants under an
-- active erasure are skipped: the deletion guard trigger refuses their rows, which are being erased.

SET LOCAL max_parallel_workers_per_gather = 0;

SELECT set_config('app.is_admin', 'true', true);

-- One key per stored name: the matching enrolled device, or a fresh random id when no device
-- record carries that name any more.
CREATE TEMP TABLE ios_device_keys ON COMMIT DROP AS
SELECT names.study_id, names.participant_id, names.device_name,
       COALESCE(
           (SELECT d.device_id::text
              FROM devices d
             WHERE d.study_id::text = names.study_id
               AND d.participant_id = names.participant_id
               AND d.device_type = 'Ios'
               AND d.source_device ->> 'name' = names.device_name
             ORDER BY d.device_id
             LIMIT 1),
           gen_random_uuid()::text
       ) AS device_key
  FROM (SELECT DISTINCT study_id, participant_id, device_name FROM sensor_data) names
 WHERE public.chronicle_participant_mutation_allowed(names.study_id::text, names.participant_id);

UPDATE sensor_data s
   SET device_name = k.device_key,
       device_system_name = CASE WHEN s.device_system_name = s.device_name THEN ''
                                 ELSE s.device_system_name END
  FROM ios_device_keys k
 WHERE s.study_id = k.study_id
   AND s.participant_id = k.participant_id
   AND s.device_name = k.device_name;

UPDATE devices
   SET source_device = source_device - 'name'
 WHERE device_type = 'Ios'
   AND source_device ? 'name'
   AND public.chronicle_participant_mutation_allowed(study_id::text, participant_id);

SELECT set_config('app.is_admin', '', true);
