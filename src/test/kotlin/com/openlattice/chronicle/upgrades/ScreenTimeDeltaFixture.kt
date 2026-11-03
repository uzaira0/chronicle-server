package com.openlattice.chronicle.upgrades

import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals

/** Seed, projection, and invoker visibility checks for migrated Screen Time deltas. */
internal class ScreenTimeDeltaFixture(private val connection: Connection) {
    private val times = ScreenTimeDeltaTimes(
        fallBackFirstHour = OffsetDateTime.parse("2026-11-01T06:00:00Z"),
        fallBackSecondHour = OffsetDateTime.parse("2026-11-01T07:00:00Z"),
        beforeMidnight = OffsetDateTime.parse("2026-07-20T04:00:00Z"),
        afterMidnight = OffsetDateTime.parse("2026-07-20T05:00:00Z"),
    )

    private val device = UUID.randomUUID()

    fun verify(studyId: UUID, participantId: String) {
        insertScreenTimeDeltaSnapshots(studyId, participantId, times)
        assertProjection(readDeltas(studyId, participantId), times)
        assertRowVisibility(studyId, participantId)
    }

    private data class DeltaRow(
        val bucketStart: OffsetDateTime,
        val intervalStart: OffsetDateTime,
        val intervalEnd: OffsetDateTime,
        val usageDelta: Double?,
        val status: String,
    )

    private fun readDeltas(studyId: UUID, participantId: String): List<DeltaRow> {
        val projected = mutableListOf<DeltaRow>()
        connection.prepareStatement(
            """
            SELECT bucket_start_utc, interval_start_utc, interval_end_utc,
                   usage_delta_seconds, delta_status
            FROM screen_time_usage_deltas
            WHERE study_id = ? AND participant_id = ?
            ORDER BY bucket_start_utc, interval_end_utc
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    projected += DeltaRow(
                        bucketStart = rows.getObject("bucket_start_utc", OffsetDateTime::class.java),
                        intervalStart = rows.getObject("interval_start_utc", OffsetDateTime::class.java),
                        intervalEnd = rows.getObject("interval_end_utc", OffsetDateTime::class.java),
                        usageDelta = (rows.getObject("usage_delta_seconds") as? Number)?.toDouble(),
                        status = rows.getString("delta_status"),
                    )
                }
            }
        }
        return projected
    }

    private fun assertProjection(projected: List<DeltaRow>, times: ScreenTimeDeltaTimes) {
        assertEquals(7, projected.size)
        assertEquals(
            listOf(300.0, 180.0, 120.0),
            projected.filter { it.bucketStart.toInstant() == times.fallBackFirstHour.toInstant() }.map { it.usageDelta },
        )
        assertEquals(
            listOf(120.0),
            projected.filter { it.bucketStart.toInstant() == times.fallBackSecondHour.toInstant() }.map { it.usageDelta },
        )
        assertEquals(
            "the repeated local 01:00 hour must start a new absolute bucket",
            times.fallBackSecondHour.toInstant(),
            projected.single { it.bucketStart.toInstant() == times.fallBackSecondHour.toInstant() }
                .intervalStart.toInstant(),
        )
        assertEquals(
            listOf(180.0),
            projected.filter { it.bucketStart.toInstant() == times.beforeMidnight.toInstant() }.map { it.usageDelta },
        )
        val afterMidnightRows = projected.filter { it.bucketStart.toInstant() == times.afterMidnight.toInstant() }
        assertEquals(60.0, requireNotNull(afterMidnightRows.first().usageDelta), 0.001)
        assertEquals(times.afterMidnight.toInstant(), afterMidnightRows.first().intervalStart.toInstant())
        assertEquals(null, afterMidnightRows.last().usageDelta)
        assertEquals("counter_decreased", afterMidnightRows.last().status)
    }

    private fun assertRowVisibility(studyId: UUID, participantId: String) {
        // Fresh-install role grants normally run after Flyway. Mirror the underlying-table
        // SELECT grant here, then prove that the view executes as the invoker: no context
        // sees no rows, and an explicit study context sees only that study.
        connection.createStatement().use { statement ->
            statement.execute("GRANT SELECT ON ios_screen_time_usage TO chronicle_app")
            statement.execute("SET ROLE chronicle_app")
        }
        try {
            connection.prepareStatement(
                "SELECT count(*) FROM screen_time_usage_deltas WHERE participant_id = ?"
            ).use { statement ->
                statement.setString(1, participantId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
            connection.prepareStatement(
                "SELECT set_config('app.authorized_studies', ?, false)"
            ).use { statement ->
                statement.setString(1, studyId.toString())
                statement.execute()
            }
            connection.prepareStatement(
                "SELECT count(*) FROM screen_time_usage_deltas WHERE participant_id = ?"
            ).use { statement ->
                statement.setString(1, participantId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertEquals(7, rows.getInt(1))
                }
            }
        } finally {
            connection.createStatement().use { it.execute("RESET ROLE") }
        }
    }

    private data class ScreenTimeDeltaTimes(
        val fallBackFirstHour: OffsetDateTime,
        val fallBackSecondHour: OffsetDateTime,
        val beforeMidnight: OffsetDateTime,
        val afterMidnight: OffsetDateTime,
    )

    private fun insertScreenTimeDeltaSnapshots(
        studyId: UUID,
        participantId: String,
        times: ScreenTimeDeltaTimes,
    ) {
        listOf(
            DirectScreenTimeSnapshot(
                bucketStart = times.fallBackFirstHour,
                bucketEnd = times.fallBackFirstHour.plusHours(1),
                capturedAt = times.fallBackFirstHour.plusMinutes(15),
                usageSeconds = 300, notificationCount = 1, pickupCount = 1,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.fallBackFirstHour,
                bucketEnd = times.fallBackFirstHour.plusHours(1),
                capturedAt = times.fallBackFirstHour.plusMinutes(30),
                usageSeconds = 480, notificationCount = 2, pickupCount = 1,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.fallBackFirstHour,
                bucketEnd = times.fallBackFirstHour.plusHours(1),
                capturedAt = times.fallBackFirstHour.plusMinutes(65),
                usageSeconds = 600, notificationCount = 3, pickupCount = 2,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.fallBackSecondHour,
                bucketEnd = times.fallBackSecondHour.plusHours(1),
                capturedAt = times.fallBackSecondHour.plusMinutes(15),
                usageSeconds = 120, notificationCount = 0, pickupCount = 0,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.beforeMidnight,
                bucketEnd = times.beforeMidnight.plusHours(1),
                capturedAt = times.beforeMidnight.plusMinutes(65),
                usageSeconds = 180, notificationCount = 1, pickupCount = 1,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.afterMidnight,
                bucketEnd = times.afterMidnight.plusHours(1),
                capturedAt = times.afterMidnight.plusMinutes(15),
                usageSeconds = 60, notificationCount = 1, pickupCount = 1,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.afterMidnight,
                bucketEnd = times.afterMidnight.plusHours(1),
                capturedAt = times.afterMidnight.plusMinutes(30),
                usageSeconds = 40, notificationCount = 1, pickupCount = 1,
            ),
            DirectScreenTimeSnapshot(
                bucketStart = times.afterMidnight,
                bucketEnd = times.afterMidnight.plusHours(5),
                capturedAt = times.afterMidnight.plusMinutes(45),
                usageSeconds = 999, notificationCount = 99, pickupCount = 99,
            ),
        ).forEach { snapshot ->
            insertDirectScreenTimeSnapshot(studyId, participantId, snapshot)
        }
    }

    private data class DirectScreenTimeSnapshot(
        val bucketStart: OffsetDateTime,
        val bucketEnd: OffsetDateTime,
        val capturedAt: OffsetDateTime,
        val usageSeconds: Int,
        val notificationCount: Int,
        val pickupCount: Int,
    )

    private fun insertDirectScreenTimeSnapshot(
        studyId: UUID,
        participantId: String,
        snapshot: DirectScreenTimeSnapshot,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO ios_screen_time_usage (
                study_id, participant_id, random_device_id, record_id, row_kind,
                captured_at, observation_start, observation_end, timezone,
                app_name, bundle_identifier, duration_seconds, notification_count, pickup_count
            ) VALUES (
                ?, ?, ?, ?, 'application',
                ?, ?, ?, 'America/Chicago',
                'Fixture App', 'com.example.fixture', ?, ?, ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.setObject(3, device)
            statement.setObject(4, UUID.randomUUID())
            statement.setObject(5, snapshot.capturedAt)
            statement.setObject(6, snapshot.bucketStart)
            statement.setObject(7, snapshot.bucketEnd)
            statement.setInt(8, snapshot.usageSeconds)
            statement.setInt(9, snapshot.notificationCount)
            statement.setInt(10, snapshot.pickupCount)
            statement.executeUpdate()
        }
    }
}
