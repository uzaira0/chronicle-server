package com.openlattice.chronicle.storage

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.UsageEventColumn
import com.openlattice.chronicle.services.upload.UsageEventQueueEntry
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.CLIENT_EVENT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.INTERACTION_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USERNAME
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

class UsageEventTimestampContractTest {
    companion object {
        private val OCCURRED_AT = OffsetDateTime.parse("2026-10-29T15:00:00-03:00")
        private val FIRST_UPLOAD = OffsetDateTime.parse("2026-10-29T15:05:00-03:00")
        private lateinit var hds: HikariDataSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            // Real fresh install: framework tables plus the Flyway corpus (ChronicleContractTestSchema.sharedPostgres).
            val postgres = ChronicleContractTestSchema.sharedPostgres
            hds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            })
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::hds.isInitialized) hds.close()
        }
    }

    private class Event(val activityClass: String?, val clientEventId: UUID? = UUID.randomUUID())

    /** Drains each upload through the production writer and returns stored rows per activity class. */
    private fun storedPerActivityClass(vararg uploads: List<Event>): Map<String?, Int> {
        val study = UUID.randomUUID()
        uploads.forEachIndexed { index, events ->
            val uploadedAt = FIRST_UPLOAD.plusMinutes(index.toLong())
            UsageEventStorageWriter.write(hds, events.map { UsageEventQueueEntry(study, "p1", columns(it), uploadedAt) },
                LoggerFactory.getLogger(UsageEventTimestampContractTest::class.java)) {}
        }
        return hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT activity_class, count(*) FROM chronicle_usage_events WHERE study_id = ? GROUP BY activity_class",
            ).use { statement ->
                statement.setString(1, study.toString())
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getInt(2) else null }.toMap()
                }
            }
        }
    }

    private fun columns(event: Event): Map<String, UsageEventColumn> = listOf(
        APP_PACKAGE_NAME to "org.example.pilot",
        ACTIVITY_CLASS to event.activityClass,
        INTERACTION_TYPE to "Move to Foreground",
        EVENT_TYPE to 1,
        TIMESTAMP to OCCURRED_AT,
        TIMEZONE to "America/Santiago",
        USERNAME to "",
        APPLICATION_LABEL to "Pilot",
        CLIENT_EVENT_ID to event.clientEventId?.toString(),
    ).associate { (column, value) ->
        column.name to UsageEventColumn(column.name, column.datatype,
            PostgresEventTables.getInsertUsageEventColumnIndex(column), value)
    }

    @Test
    fun eventsDifferingOnlyInActivityClassAreBothStored() {
        assertEquals(
            mapOf<String?, Int>("org.example.pilot.ScreenA" to 1, "org.example.pilot.ScreenB" to 1),
            storedPerActivityClass(listOf(Event("org.example.pilot.ScreenA", null), Event("org.example.pilot.ScreenB", null))),
        )
    }

    @Test
    fun eventWithoutActivityClassBesideOneWithItAreBothStored() {
        assertEquals(
            mapOf<String?, Int>(null to 1, "org.example.pilot.ScreenA" to 1),
            storedPerActivityClass(listOf(Event(null, null), Event("org.example.pilot.ScreenA", null))),
        )
    }

    @Test
    fun identicalEventsStoreOnceWithinAndAcrossUploads() {
        // Distinct phone ids, same logical event: one row per class; NULL and empty classes stay distinct events.
        val upload = { listOf(Event("org.example.pilot.ScreenA"), Event("org.example.pilot.ScreenA"),
            Event(null), Event(null), Event(""), Event(""), Event(null, clientEventId = null)) }
        assertEquals(
            mapOf<String?, Int>("org.example.pilot.ScreenA" to 1, null to 1, "" to 1),
            storedPerActivityClass(upload(), upload()),
        )
    }

    @Test
    fun retriedClientEventIdStoresOnce() {
        val retried = Event("org.example.pilot.ScreenA")
        assertEquals(mapOf<String?, Int>("org.example.pilot.ScreenA" to 1),
            storedPerActivityClass(listOf(retried, retried),
                listOf(Event("org.example.pilot.ScreenB", retried.clientEventId), retried)))
    }

    @Test
    fun maximumAdmittedUtf8KeyFitsTheFreshIdentityIndex() {
        val study = UUID.randomUUID()
        val data = columns(Event("org.example.pilot.ScreenA")).toMutableMap()
        val textBytes = study.toString().toByteArray(Charsets.UTF_8).size + "p1".length + data.values
            .filter { it.datatype == com.geekbeast.postgres.PostgresDatatype.TEXT && it.name != APPLICATION_LABEL.name }
            .filter { it.name != CLIENT_EVENT_ID.name }
            .sumOf { (it.value as? String)?.toByteArray(Charsets.UTF_8)?.size ?: 0 }
        val room = AppDataUploadService.MAX_USAGE_EVENT_KEY_TEXT_BYTES - textBytes
        // Distinct Unicode code points keep this boundary independent of text compression.
        val label = (0 until room / 3).joinToString("") { (0x1000 + it).toChar().toString() } + "x".repeat(room % 3)
        data[APPLICATION_LABEL.name] = data.getValue(APPLICATION_LABEL.name).copy(value = label)
        assertEquals(AppDataUploadService.MAX_USAGE_EVENT_KEY_TEXT_BYTES,
            textBytes + label.toByteArray(Charsets.UTF_8).size)
        assertEquals(1, UsageEventStorageWriter.write(hds,
            listOf(UsageEventQueueEntry(study, "p1", data, FIRST_UPLOAD)),
            LoggerFactory.getLogger(UsageEventTimestampContractTest::class.java)) {})
    }

    /**
     * The database owns logical event identity, not only the writer's dedupe pass: the fresh schema declares the
     * identity over every column but the phone's id, NULL equal to NULL, and the key text columns NOT NULL.
     */
    @Test
    fun freshSchemaDeclaresTheUsageEventIdentity() {
        val identity = PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.map { it.name } - CLIENT_EVENT_ID.name
        hds.connection.use { connection ->
            connection.prepareStatement("""
                SELECT array_agg(a.attname::text ORDER BY k.ord), bool_and(i.indnullsnotdistinct)
                FROM pg_constraint c
                JOIN pg_index i ON i.indexrelid = c.conindid
                CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                WHERE c.conrelid = 'public.chronicle_usage_events'::regclass
                  AND c.conname = 'chronicle_usage_events_identity' AND c.contype = 'u'
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(identity, (rows.getArray(1)?.array as? Array<*>)?.map(Any?::toString))
                    assertTrue("NULL activity classes are equal for identity", rows.getBoolean(2))
                }
            }
            connection.prepareStatement("""
                SELECT attname::text FROM pg_attribute
                WHERE attrelid = 'public.chronicle_usage_events'::regclass AND attnum > 0 AND NOT attisdropped AND attnotnull
            """.trimIndent()).use { statement ->
                val notNull = statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() }
                val required = listOf(APP_PACKAGE_NAME, INTERACTION_TYPE, TIMESTAMP, TIMEZONE, USERNAME, APPLICATION_LABEL, UPLOADED_AT)
                    .map { it.name }
                assertEquals("identity text columns that accept NULL", emptySet<String>(), required.toSet() - notNull)
            }
        }
    }

    /** With no dedupe pass, the database itself refuses a second row with the same identity, NULL class included. */
    @Test
    fun databaseRefusesADuplicateIdentityWithoutTheWriter() {
        val study = UUID.randomUUID()
        val logger = LoggerFactory.getLogger(UsageEventTimestampContractTest::class.java)
        assertEquals(1, UsageEventStorageWriter.write(hds,
            listOf(UsageEventQueueEntry(study, "p1", columns(Event(null)), FIRST_UPLOAD)), logger) {})
        // Same identity, no phone id: the client_event_id index cannot be what refuses it.
        val identity = (PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns - CLIENT_EVENT_ID).joinToString { it.name }
        val duplicate = assertThrows(PSQLException::class.java) {
            hds.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO chronicle_usage_events ($identity) SELECT $identity FROM chronicle_usage_events WHERE study_id = ?",
                ).use { statement ->
                    statement.setString(1, study.toString())
                    statement.executeUpdate()
                }
            }
        }
        assertEquals("23505", duplicate.sqlState)
        assertEquals("chronicle_usage_events_identity", duplicate.serverErrorMessage?.constraint)
    }

    @Test
    fun usageEventsExposeOccurrenceAndReceiptTimesOnly() {
        val columns = PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.map { it.name }

        assertTrue(columns.contains(TIMESTAMP.name))
        assertTrue(columns.contains(UPLOADED_AT.name))
        // One clock: no phone queue-write time beside the server's receipt time.
        assertFalse(columns.contains("collected_at"))
    }

    @Test
    fun transportTimestampsDoNotChangeLogicalDeduplicationIdentity() {
        val sql = PostgresEventTables.buildTempTableOfDuplicates("duplicate_events_test")
        val partition = sql.substringAfter("PARTITION BY ").substringBefore("ORDER BY")

        assertFalse(partition.contains(UPLOADED_AT.name))
        assertTrue(sql.contains("ORDER BY ${UPLOADED_AT.name} ASC"))
        assertTrue(sql.contains("duplicate_rank > 1"))
    }
}
