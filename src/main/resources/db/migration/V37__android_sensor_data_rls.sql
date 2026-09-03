-- =============================================================================
-- V37 — Row-Level Security for android_sensor_data + android_device_sensor_availability
-- =============================================================================
-- Both tables are created by the rhizome PostgresTables framework
-- (ChroniclePostgresTables.kt) but were the only Android collection tables left
-- WITHOUT a study-isolation RLS policy: V1 covers iOS sensor_data / usage / upload_buffer,
-- and V24/V31/V34/V35/V36 cover battery / interaction / audio-activity / audio-content /
-- notification. This retrofits the same FORCE ROW LEVEL SECURITY +
-- chronicle_has_study_access(study_id) policy so raw Android sensor samples and the
-- per-device sensor-availability rows are DB-level study-isolated exactly like every
-- sibling collection table.
--
-- Idempotent: guarded on table existence with DROP POLICY IF EXISTS before CREATE, so it
-- is safe to apply whether or not the tables already exist. Not auto-discovered by
-- filename — executed only via the registered AndroidSensorDataRlsUpgrade bean.
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'android_sensor_data') THEN
        ALTER TABLE android_sensor_data ENABLE ROW LEVEL SECURITY;
        ALTER TABLE android_sensor_data FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_android_sensor_data ON android_sensor_data;
        CREATE POLICY study_isolation_android_sensor_data ON android_sensor_data
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'android_device_sensor_availability') THEN
        ALTER TABLE android_device_sensor_availability ENABLE ROW LEVEL SECURITY;
        ALTER TABLE android_device_sensor_availability FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_android_device_sensor_availability ON android_device_sensor_availability;
        CREATE POLICY study_isolation_android_device_sensor_availability ON android_device_sensor_availability
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V37__android_sensor_data_rls', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
