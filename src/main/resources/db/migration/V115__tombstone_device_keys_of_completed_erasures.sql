-- chronicle:destructive-approved Deletes only API keys whose participant or study erasure already completed.
-- Before V114 a completed WITHDRAW_AND_ERASE revoked the participant's device keys but kept their rows,
-- with participant and device identifiers. Give each such device key the tombstone V114 erasures write
-- (hash, kind, 365 days from now, whatever the key's own expiry), then delete every api_keys row of a
-- completed participant or study erasure. A restored database migrates through here as well.
-- Before key material was checked at installation, an erased subject's revoked key could be installed
-- for another subject. Such a hash is still owned by a retained row, so it gets no tombstone: the
-- retained credential stays an ordinary credential and never reads as erased.
DO $$
DECLARE shared_hashes BIGINT;
BEGIN
    PERFORM set_config('app.is_admin', 'true', true);
    PERFORM set_config('app.current_user_id', 'chronicle-deletion-worker', true);

    CREATE TEMP TABLE v115_erased_keys ON COMMIT DROP AS
    SELECT k.key_id, k.key_hash, operation.mode,
           k.participant_id IS NOT NULL AND k.device_id IS NOT NULL AS device_bound
    FROM api_keys k
    JOIN data_deletion_operations operation
      ON operation.study_id = k.study_id AND operation.status = 'COMPLETED'
     AND (operation.mode = 'STUDY_ERASURE'
          OR (operation.mode = 'WITHDRAW_AND_ERASE'
              AND operation.participant_block_token = md5(k.study_id::text || ':' || k.participant_id)));

    SELECT count(DISTINCT erased.key_hash) INTO shared_hashes
    FROM v115_erased_keys erased
    WHERE erased.device_bound
      AND EXISTS (SELECT 1 FROM api_keys retained
                  WHERE retained.key_hash = erased.key_hash
                    AND retained.key_id NOT IN (SELECT key_id FROM v115_erased_keys));
    IF shared_hashes > 0 THEN
        RAISE NOTICE 'V115: % erased device key hash(es) are still owned by a retained api_keys row; no tombstone written for them',
            shared_hashes;
    END IF;

    INSERT INTO erased_device_key_tombstones (key_hash, kind, expires_at)
    SELECT DISTINCT ON (erased.key_hash) erased.key_hash, erased.mode, now() + interval '365 days'
    FROM v115_erased_keys erased
    WHERE erased.device_bound
      AND NOT EXISTS (SELECT 1 FROM api_keys retained
                      WHERE retained.key_hash = erased.key_hash
                        AND retained.key_id NOT IN (SELECT key_id FROM v115_erased_keys))
    ORDER BY erased.key_hash, erased.mode
    ON CONFLICT (key_hash) DO NOTHING;

    DELETE FROM api_keys WHERE key_id IN (SELECT key_id FROM v115_erased_keys);
END $$;
