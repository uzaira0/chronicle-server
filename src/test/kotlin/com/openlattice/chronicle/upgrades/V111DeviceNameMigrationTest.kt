package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class V111DeviceNameMigrationTest {
    @Test fun iosDeviceNamesAreReplacedByPerDeviceKeys() {
        ChronicleContractTestSchema.prodPostgresContainer("device_name_upgrade").use { pg ->
            pg.start(); ChronicleContractTestSchema.waitForQueryReady(pg)
            pg.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)
            assertTrue(FlywayMigrationService.baseConfiguration().dataSource(pg.jdbcUrl, pg.username, pg.password)
                .target("110").load().migrate().success)

            val study = UUID.randomUUID(); val phone = UUID.randomUUID()
            pg.createConnection("").use { c -> c.createStatement().use { s ->
                s.execute("""INSERT INTO devices (study_id, device_id, participant_id, device_type, source_device)
                    VALUES ('$study', '$phone', 'p1', 'Ios',
                    '{"@class":"com.openlattice.chronicle.sources.IOSDevice","name":"Alex''s iPhone","model":"iPhone"}')""")
                for ((sample, name) in listOf("a" to "Alex's iPhone", "b" to "Alex's iPhone", "c" to "Old iPad")) {
                    s.execute("""INSERT INTO sensor_data (study_id, participant_id, sample_id, sensor_type, sample_duration,
                        device_version, device_name, device_model, device_system_name)
                        VALUES ('$study', 'p1', '$sample', 'deviceUsage', 1, '17.0', '${name.replace("'", "''")}', 'iPhone',
                        '${name.replace("'", "''")}')""")
                }
            } }

            assertTrue(ChronicleContractTestSchema.migrate(pg).success)

            pg.createConnection("").use { c -> c.createStatement().use { s ->
                val keys = s.executeQuery("SELECT sample_id, device_name, device_system_name FROM sensor_data ORDER BY sample_id").use { r ->
                    buildList { while (r.next()) { add(r.getString(2)); assertEquals("", r.getString(3)) } }
                }
                assertEquals(phone.toString(), keys[0]); assertEquals(phone.toString(), keys[1])
                assertNotEquals("Devices must stay apart for screen-time deltas", keys[0], keys[2])
                assertFalse(keys[2].contains("iPad"))
                s.executeQuery("SELECT source_device ? 'name', source_device ->> 'model' FROM devices").use { r ->
                    r.next(); assertFalse(r.getBoolean(1)); assertEquals("iPhone", r.getString(2))
                }
                s.executeQuery("SELECT relforcerowsecurity FROM pg_class WHERE relname IN ('sensor_data', 'devices')").use { r ->
                    while (r.next()) assertTrue("FORCE RLS must be restored", r.getBoolean(1))
                }
            } }
        }
    }
}
