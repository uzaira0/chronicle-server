package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import com.openlattice.chronicle.study.ParticipantDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/** The iOS Screen Time export against the real schema: repeated captures of one window export once, newest wins. */
class IosScreenTimeExportCollapseTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("ios_screen_time_export_collapse")

        @BeforeClass
        @JvmStatic
        fun setUp() {
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
        }

        private val HOUR = OffsetDateTime.parse("2026-06-24T10:00:00Z")
        private val DEVICE_1 = UUID.fromString("11111111-1111-4111-8111-111111111111")
    }

    private val studyId = UUID.randomUUID()

    private fun insert(
        capturedAt: OffsetDateTime,
        seconds: Int,
        app: String = "Instagram",
        hourStart: OffsetDateTime = HOUR,
        device: UUID = DEVICE_1,
        recordId: UUID = UUID.randomUUID(),
        category: String? = null,
    ) = connection().use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO ios_screen_time_usage (study_id, participant_id, random_device_id, record_id,
                row_kind, captured_at, observation_start, observation_end, timezone, app_name, duration_seconds, category_name)
            VALUES (?, 'participant-1', ?, ?, 'application', ?, ?, ?, 'UTC', ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, studyId)
            ps.setObject(2, device)
            ps.setObject(3, recordId)
            ps.setObject(4, capturedAt)
            ps.setObject(5, hourStart)
            ps.setObject(6, hourStart.plusHours(1))
            ps.setString(7, app)
            ps.setInt(8, seconds)
            ps.setString(9, category)
            ps.executeUpdate()
        }
    }

    private fun export(start: OffsetDateTime, end: OffsetDateTime): List<Pair<String, Int>> = connection().use { connection ->
        val sql = DataDownloadService.collectionDataSql(ParticipantDataType.IOSScreenTime, filterParticipants = true)
        connection.prepareStatement(sql).use { ps ->
            var index = DataDownloadService.bindDownloadScope(ps, studyId, setOf("participant-1"))
            ps.setObject(++index, start)
            ps.setObject(++index, end)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) (rs.getString("app_name") ?: rs.getString("web_domain")) to rs.getInt("duration_seconds") else null
                }
                    .toList()
            }
        }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    @Test
    fun `each window exports once with its newest capture`() {
        insert(HOUR.plusMinutes(30), 300)
        insert(HOUR.plusMinutes(45), 600)
        insert(HOUR.plusHours(5), 900)
        insert(HOUR.plusMinutes(45), 120, app = "Safari")

        assertEquals(setOf("Instagram" to 900, "Safari" to 120), export(HOUR.minusDays(1), HOUR.plusDays(1)).toSet())
        assertEquals(2, export(HOUR.minusDays(1), HOUR.plusDays(1)).size)
    }

    @Test
    fun `web-domain rows collapse repeated captures too`() {
        insert(HOUR.plusMinutes(30), 300)
        insert(HOUR.plusMinutes(45), 900)
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE ios_screen_time_usage SET row_kind = 'webDomain', web_domain = 'example.com', app_name = NULL WHERE study_id = ?",
            ).use {
                it.setObject(1, studyId)
                assertEquals(2, it.executeUpdate())
            }
        }
        assertEquals(listOf("example.com" to 900), export(HOUR, HOUR.plusHours(1)))
    }

    @Test
    fun `a window belongs to the range holding its start even when captured later`() {
        insert(HOUR.plusDays(1), 900)

        assertEquals(listOf("Instagram" to 900), export(HOUR, HOUR.plusHours(1)))
        assertEquals(emptyList<Pair<String, Int>>(), export(HOUR.plusHours(1), HOUR.plusDays(2)))
    }

    @Test
    fun `separate devices and windows never collapse`() {
        insert(HOUR.plusMinutes(30), 300)
        insert(HOUR.plusMinutes(30), 200, device = UUID.randomUUID())
        insert(HOUR.plusHours(1).plusMinutes(30), 100, hourStart = HOUR.plusHours(1))

        assertEquals(3, export(HOUR.minusDays(1), HOUR.plusDays(1)).size)
    }

    /** Missing labels (NULL bundle id, domain, category) still identify one capture, so a retry cannot slip in. */
    @Test
    fun `a capture key with missing labels admits one row`() {
        insert(HOUR.plusMinutes(30), 300)
        val duplicate = assertThrows(SQLException::class.java) { insert(HOUR.plusMinutes(30), 400) }
        assertEquals("23505", duplicate.sqlState)
        assertEquals(listOf("Instagram" to 300), export(HOUR, HOUR.plusHours(1)))
    }

    private fun surveyCategories(): List<Pair<String, Int>> = connection().use { connection ->
        connection.prepareStatement(SurveysService.GET_DEVICE_USAGE_IOS_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, "participant-1")
            statement.setObject(3, HOUR)
            statement.setObject(4, HOUR.plusHours(1))
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getString("category") to rows.getInt(SurveysService.TOTAL_USAGE_FIELD) else null }
                    .toList()
            }
        }
    }

    @Test
    fun `survey app totals also select only the newest capture`() {
        insert(HOUR.plusMinutes(30), 300)
        insert(HOUR.plusMinutes(45), 600)
        insert(HOUR.plusHours(5), 900)
        // Apple named no category, so the app's own label stands in.
        assertEquals(listOf("Instagram" to 900), surveyCategories())
    }

    @Test
    fun `survey usage is keyed by the Apple category`() {
        insert(HOUR.plusMinutes(30), 300, category = "Social")
        assertEquals(listOf("Social" to 300), surveyCategories())
    }

    @Test
    fun `erasure and upload use the same capture instant`() {
        insert(HOUR.plusMinutes(30), 300)
        insert(HOUR.plusHours(5), 900)
        val sql = "SELECT captured_at, observation_end, ${ScreenTimeUsageCaptures.CAPTURE_TIME_SQL} AS capture_time " +
            "FROM ios_screen_time_usage WHERE study_id = ? ORDER BY captured_at"
        val rows = connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { rs ->
                    generateSequence {
                        if (rs.next()) Triple(
                            rs.getObject("captured_at", OffsetDateTime::class.java),
                            rs.getObject("observation_end", OffsetDateTime::class.java),
                            rs.getObject("capture_time", OffsetDateTime::class.java),
                        ) else null
                    }.toList()
                }
            }
        }
        // One capture while the hour is still measured, one after it closed.
        assertEquals(listOf(HOUR.plusMinutes(30), HOUR.plusHours(1)).map { it.toInstant() }, rows.map { it.third.toInstant() })
        rows.forEach { (capturedAt, observationEnd, sqlTime) ->
            assertEquals(ScreenTimeUsageCaptures.captureTime(capturedAt, observationEnd).toInstant(), sqlTime.toInstant())
        }
    }

    @Test
    fun `missing pickup counts remain missing in the delta view`() {
        insert(HOUR.plusMinutes(30), 300)
        insert(HOUR.plusMinutes(45), 600)
        connection().use { connection ->
            connection.prepareStatement("SELECT cumulative_pickup_count, pickup_delta_count FROM screen_time_usage_deltas WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeQuery().use { rows ->
                    val counts = generateSequence {
                        if (rows.next()) listOf(rows.getObject("cumulative_pickup_count"), rows.getObject("pickup_delta_count"))
                        else null
                    }.toList()
                    assertEquals(List(2) { listOf(null, null) }, counts)
                }
            }
        }
    }
}
