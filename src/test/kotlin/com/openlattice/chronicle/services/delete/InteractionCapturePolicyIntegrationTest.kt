package com.openlattice.chronicle.services.delete

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.InteractionEventType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.InteractionPositionSource
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionCapturePolicyIntegrationTest : ServerSweepTestFixture() {
    @Test fun disallowedInteractionKindsNeverCommitAndPermittedKindsStillUpload() {
        val study = UUID.randomUUID()
        seed(study, PARTICIPANT)
        pool("interaction-policy").use { dataSource ->
            val service = InteractionEventsUploadService(resolver(dataSource))
            for (clicks in listOf(false, true)) {
                policy(study, InteractionPolicy(captureClicks = clicks, captureScrolls = !clicks), version = if (clicks) 3 else 2)
                InteractionEventType.entries.forEach { kind ->
                    assertUploadFollowsPolicy(service, study, event(kind), allowed = (kind == InteractionEventType.SCROLL) != clicks)
                }
            }
        }
    }

    private fun assertUploadFollowsPolicy(service: InteractionEventsUploadService, study: UUID, sample: AndroidInteractionEvent,
                                          allowed: Boolean) {
        if (allowed) {
            assertEquals(1, service.upload(study, PARTICIPANT, listOf(sample)))
        } else {
            assertThrows(IllegalArgumentException::class.java) { service.upload(study, PARTICIPANT, listOf(sample)) }
            assertEquals(0, count(study, sample.id))
        }
    }

    @Test fun disabledPositionCaptureStripsRawAndDerivedDisplayCoordinatesWithoutDroppingTheEvent() {
        val study = UUID.randomUUID()
        seed(study, PARTICIPANT)
        policy(study, InteractionPolicy(captureElementPosition = false))
        pool("interaction-position").use {
            assertEquals(1, InteractionEventsUploadService(resolver(it)).upload(study, PARTICIPANT, listOf(event(InteractionEventType.CLICK))))
        }
        val stored = storedRow(study)
        listOf("position_source", "node_bounds_left", "node_bounds_top", "node_bounds_right", "node_bounds_bottom",
            "display_id", "raw_x", "raw_y", "screen_width", "screen_height", "normalized_x", "normalized_y",
            "orientation", "screen_density_dpi").forEach { column -> assertNull(column, stored[column]) }
        assertEquals(InteractionEventType.CLICK.name, stored["event_type"])
        assertEquals("android.widget.Button", stored["element_role"])
    }

    /** The study's single stored interaction row, column name to value. */
    private fun storedRow(study: UUID): Map<String, Any?> = postgres.createConnection("").use { connection ->
        connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
        connection.prepareStatement("SELECT * FROM ${ChroniclePostgresTables.INTERACTION_EVENTS.name} WHERE study_id = ?").use {
            it.setObject(1, study)
            it.executeQuery().use { rows ->
                assertTrue(rows.next())
                (1..rows.metaData.columnCount).associate { index -> rows.metaData.getColumnName(index) to rows.getObject(index) }
            }
        }
    }

    private fun policy(study: UUID, policy: InteractionPolicy, version: Int = 2) {
        val settings = StudySettings(mapOf(StudySettingType.DataCollection to AndroidDataCollectionSetting(modules = mapOf(
            CollectionModuleId.INTERACTION_EVENTS to CollectionModuleSetting(enabled = true, interactionPolicy = policy)),
            settingsVersion = version)))
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("UPDATE studies SET settings = ?::jsonb WHERE study_id = ?").use {
                it.setString(1, ObjectMappers.newJsonMapper().writeValueAsString(settings)); it.setObject(2, study); it.executeUpdate()
            }
        }
    }

    private fun event(kind: InteractionEventType) = AndroidInteractionEvent(
        id = UUID.randomUUID().toString(), timestamp = OffsetDateTime.now(), timezone = "UTC", eventType = kind,
        gridRows = 4, gridCols = 3, gridRow = 1, gridCol = 1, elementRole = "android.widget.Button", foregroundPackage = "org.example.app",
        positionSource = InteractionPositionSource.ACCESSIBILITY_NODE_BOUNDS, nodeBoundsLeft = 10, nodeBoundsTop = 20,
        nodeBoundsRight = 100, nodeBoundsBottom = 200, displayId = 0, rawX = 55, rawY = 110, screenWidth = 1080, screenHeight = 1920,
        normalizedX = .1, normalizedY = .2, orientation = 0, screenDensityDpi = 320,
    )

    private fun count(study: UUID, event: String): Int = postgres.createConnection("").use { connection ->
        connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
        connection.prepareStatement(
            "SELECT count(*) FROM ${ChroniclePostgresTables.INTERACTION_EVENTS.name} WHERE study_id = ? AND event_id = ?",
        ).use {
            it.setObject(1, study); it.setString(2, event)
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }

    private companion object { const val PARTICIPANT = "capture-policy-participant" }
}
