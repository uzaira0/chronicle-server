-- Align older local deployments with the derived-device-id schema used by the
-- current server code.
--
-- Some pre-derived schemas kept a raw `source_device_id` column on the Android
-- sensor tables. The current upload/enrollment path instead writes a derived
-- `device_id` UUID, computed by DeviceIdUtils.deriveDeviceId as a version-3
-- (MD5 name-based) UUID of the string "studyId:participantId:sourceDeviceId"
-- (java.util.UUID.nameUUIDFromBytes). A raw `source_device_id::uuid` cast does
-- NOT equal that derived value, so this migration must reproduce the v3
-- derivation in SQL — otherwise repaired rows would never join against rows
-- written by current code.
--
-- The migration adds `device_id` where missing and, only where the legacy
-- `source_device_id` column still exists, backfills `device_id` with the
-- correctly derived UUID. On a current/fresh schema (no `source_device_id`
-- column) every legacy-column reference is skipped, so the migration is a
-- safe no-op there.

-- v3 (MD5 name-based) UUID, byte-for-byte equivalent to
-- java.util.UUID.nameUUIDFromBytes(name): MD5 of the name, with the version
-- nibble forced to 3 and the variant bits forced to the IETF variant.
-- Declared in pg_temp so it is dropped automatically at session end.
CREATE OR REPLACE FUNCTION pg_temp.chronicle_derive_device_id(
    p_study_id uuid,
    p_participant_id text,
    p_source_device_id text
) RETURNS uuid LANGUAGE sql IMMUTABLE AS $fn$
    SELECT (
        substr(h, 1, 8) || '-' ||
        substr(h, 9, 4) || '-' ||
        '3' || substr(h, 14, 3) || '-' ||
        to_hex(((('x' || substr(h, 17, 2))::bit(8)::int) & 63) | 128) || substr(h, 19, 2) || '-' ||
        substr(h, 21, 12)
    )::uuid
    FROM (
        SELECT md5(p_study_id::text || ':' || p_participant_id || ':' || p_source_device_id) AS h
    ) m;
$fn$;

ALTER TABLE android_sensor_data
    ADD COLUMN IF NOT EXISTS device_id UUID;

ALTER TABLE android_device_sensor_availability
    ADD COLUMN IF NOT EXISTS device_id UUID;

-- Backfill android_sensor_data in bounded batches so a high-volume sensor table
-- is not rewritten under a single long-held lock. Only runs where the legacy
-- source_device_id column is present; the UPDATE inside the guarded branch is
-- never planned otherwise.
DO $$
DECLARE
    rows_updated integer;
    unrepaired   bigint;
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('android_sensor_data')
          AND attname = 'source_device_id'
          AND NOT attisdropped
    ) THEN
        LOOP
            UPDATE android_sensor_data
            SET device_id = pg_temp.chronicle_derive_device_id(study_id, participant_id, source_device_id)
            WHERE ctid IN (
                SELECT ctid
                FROM android_sensor_data
                WHERE device_id IS NULL
                  AND study_id IS NOT NULL
                  AND participant_id IS NOT NULL
                  AND source_device_id IS NOT NULL
                  AND source_device_id <> ''
                LIMIT 10000
            );
            GET DIAGNOSTICS rows_updated = ROW_COUNT;
            EXIT WHEN rows_updated = 0;
        END LOOP;

        SELECT count(*) INTO unrepaired
        FROM android_sensor_data
        WHERE device_id IS NULL;

        IF unrepaired > 0 THEN
            RAISE NOTICE 'V21: % android_sensor_data row(s) left with NULL device_id (no derivable source_device_id)', unrepaired;
        END IF;
    END IF;
END $$;

-- Backfill and reshape android_device_sensor_availability. The primary key is
-- dropped first: in a legacy schema source_device_id is part of it, so its
-- NOT NULL cannot be relaxed until the key is gone.
--
-- The PK drop is name-agnostic. A bare `DROP CONSTRAINT IF EXISTS
-- android_device_sensor_availability_pkey` would silently no-op on a legacy
-- database whose primary key carries a non-standard name (e.g. `legacy_avail_pk`),
-- and the later `ALTER COLUMN source_device_id DROP NOT NULL` would then fail
-- because source_device_id is still part of a primary key. Instead, discover the
-- actual primary-key constraint from pg_constraint and drop whatever PK exists.
DO $$
DECLARE
    pk_name text;
BEGIN
    SELECT conname INTO pk_name
    FROM pg_constraint
    WHERE conrelid = to_regclass('android_device_sensor_availability')
      AND contype = 'p';

    IF pk_name IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE android_device_sensor_availability DROP CONSTRAINT %I',
            pk_name
        );
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('android_device_sensor_availability')
          AND attname = 'source_device_id'
          AND NOT attisdropped
    ) THEN
        UPDATE android_device_sensor_availability
        SET device_id = pg_temp.chronicle_derive_device_id(study_id, participant_id, source_device_id)
        WHERE device_id IS NULL
          AND study_id IS NOT NULL
          AND participant_id IS NOT NULL
          AND source_device_id IS NOT NULL
          AND source_device_id <> '';

        -- Rows still lacking a derivable device_id predate the derived-id schema
        -- and cannot be repaired. This is small, derived device-availability
        -- metadata that the next sensor upload regenerates, so dropping them is
        -- safe — and required, since device_id becomes part of the primary key.
        DELETE FROM android_device_sensor_availability
        WHERE device_id IS NULL;

        ALTER TABLE android_device_sensor_availability
            ALTER COLUMN source_device_id DROP NOT NULL;
    END IF;
END $$;

ALTER TABLE android_device_sensor_availability
    ALTER COLUMN device_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS android_device_sensor_availability_device_id_key
    ON android_device_sensor_availability (study_id, participant_id, device_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'android_device_sensor_availability'::regclass
          AND conname = 'android_device_sensor_availability_pkey'
    ) THEN
        ALTER TABLE android_device_sensor_availability
            ADD CONSTRAINT android_device_sensor_availability_pkey
            PRIMARY KEY (study_id, participant_id, device_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS android_sensor_data_device_id_idx
    ON android_sensor_data (device_id);
