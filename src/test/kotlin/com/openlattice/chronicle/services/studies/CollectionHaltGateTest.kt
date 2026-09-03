package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionHaltGateTest {
    private val moduleId = CollectionModuleId.BATTERY_TELEMETRY

    @Test
    fun `currently required module with no decision halts every upload`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 2, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = false),
                    setting(version = 2, enabled = true, required = true),
                ),
                decisions = emptyMap(),
            ),
        )
    }

    @Test
    fun `newly required module rejects an acceptance from its optional revision`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 2, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = false),
                    setting(version = 2, enabled = true, required = true),
                ),
                decisions = mapOf(moduleId to accepted(version = 1)),
            ),
        )
    }

    @Test
    fun `acceptance at the newly required revision lifts the halt`() {
        assertFalse(
            collectionIsHalted(
                latestSettings = setting(version = 2, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = false),
                    setting(version = 2, enabled = true, required = true),
                ),
                decisions = mapOf(moduleId to accepted(version = 2)),
            ),
        )
    }

    @Test
    fun `current decline of a required module halts every upload`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 2, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = true),
                    setting(version = 2, enabled = true, required = true),
                ),
                decisions = mapOf(moduleId to declined(version = 2)),
            ),
        )
    }

    @Test
    fun `disable then reenable requires a new acceptance even when final policy matches`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 3, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = true),
                    setting(version = 2, enabled = false, required = false),
                    setting(version = 3, enabled = true, required = true),
                ),
                decisions = mapOf(moduleId to accepted(version = 1)),
            ),
        )
    }

    @Test
    fun `acceptance after reenable lifts the halt`() {
        assertFalse(
            collectionIsHalted(
                latestSettings = setting(version = 3, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = true),
                    setting(version = 2, enabled = false, required = false),
                    setting(version = 3, enabled = true, required = true),
                ),
                decisions = mapOf(moduleId to accepted(version = 3)),
            ),
        )
    }

    @Test
    fun `continuous required acceptance survives unrelated collection revisions`() {
        assertFalse(
            collectionIsHalted(
                latestSettings = setting(
                    version = 3,
                    enabled = true,
                    required = true,
                    unrelatedEnabled = false,
                ),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = true, unrelatedEnabled = false),
                    setting(version = 2, enabled = true, required = true, unrelatedEnabled = true),
                    setting(version = 3, enabled = true, required = true, unrelatedEnabled = false),
                ),
                decisions = mapOf(moduleId to accepted(version = 1)),
            ),
        )
    }

    @Test
    fun `required module policy change requires a decision at or after that change`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(
                    version = 2,
                    enabled = true,
                    required = true,
                    collectionIntervalSeconds = 60,
                ),
                issuedRevisions = listOf(
                    setting(
                        version = 1,
                        enabled = true,
                        required = true,
                        collectionIntervalSeconds = 900,
                    ),
                    setting(
                        version = 2,
                        enabled = true,
                        required = true,
                        collectionIntervalSeconds = 60,
                    ),
                ),
                decisions = mapOf(moduleId to accepted(version = 1)),
            ),
        )
    }

    @Test
    fun `unavailable required sensor is an explicit valid exemption`() {
        val sensor = CollectionModuleId.SENSOR_ACCELEROMETER
        val current = setting(
            version = 2,
            moduleId = sensor,
            enabled = true,
            required = true,
        )
        assertFalse(
            collectionIsHalted(
                latestSettings = current,
                issuedRevisions = listOf(
                    setting(version = 1, moduleId = sensor, enabled = true, required = true),
                    current,
                ),
                decisions = mapOf(sensor to unavailable(version = 1)),
            ),
        )
    }

    @Test
    fun `unavailable non sensor cannot exempt a required module`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 1, enabled = true, required = true),
                issuedRevisions = listOf(setting(version = 1, enabled = true, required = true)),
                decisions = mapOf(moduleId to unavailable(version = 1)),
            ),
        )
    }

    @Test
    fun `missing authoritative revision in an older decision suffix fails closed`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 3, enabled = true, required = true),
                issuedRevisions = listOf(
                    setting(version = 1, enabled = true, required = true),
                    setting(version = 3, enabled = true, required = true),
                ),
                decisions = mapOf(moduleId to accepted(version = 1)),
            ),
        )
    }

    @Test
    fun `exact current acceptance safely dominates omitted older migration history`() {
        assertFalse(
            collectionIsHalted(
                latestSettings = setting(version = 3, enabled = true, required = true),
                issuedRevisions = listOf(setting(version = 3, enabled = true, required = true)),
                decisions = mapOf(moduleId to accepted(version = 3)),
            ),
        )
    }

    @Test
    fun `missing current authoritative revision fails closed even with a current decision`() {
        assertTrue(
            collectionIsHalted(
                latestSettings = setting(version = 3, enabled = true, required = true),
                issuedRevisions = listOf(setting(version = 2, enabled = true, required = true)),
                decisions = mapOf(moduleId to accepted(version = 3)),
            ),
        )
    }

    @Test
    fun `optional or disabled current modules do not halt without a decision`() {
        listOf(
            setting(version = 1, enabled = true, required = false),
            setting(version = 1, enabled = false, required = true),
        ).forEach { current ->
            assertFalse(collectionIsHalted(current, listOf(current), emptyMap()))
        }
    }

    @Test
    fun `active decision query includes all three states in authoritative server order`() {
        val normalized = ParticipantCollectionAcknowledgmentService.ACTIVE_COLLECTION_DECISIONS_SQL
            .replace(Regex("\\s+"), " ")

        assertTrue(normalized.contains("acknowledgment.acknowledged_modules"))
        assertTrue(normalized.contains("acknowledgment.declined_modules"))
        assertTrue(normalized.contains("acknowledgment.unavailable_modules"))
        assertTrue(
            normalized.contains(
                "ORDER BY acknowledgment.settings_version, acknowledgment.recorded_at, acknowledgment.id",
            ),
        )
        assertFalse(normalized.contains("acknowledgment.acknowledged_at"))
    }

    private fun accepted(version: Int) = IssuedCollectionDecision(
        state = IssuedCollectionDecisionState.ACCEPTED,
        settingsVersion = version,
    )

    private fun declined(version: Int) = IssuedCollectionDecision(
        state = IssuedCollectionDecisionState.DECLINED,
        settingsVersion = version,
    )

    private fun unavailable(version: Int) = IssuedCollectionDecision(
        state = IssuedCollectionDecisionState.UNAVAILABLE,
        settingsVersion = version,
    )

    private fun setting(
        version: Int,
        moduleId: CollectionModuleId = this.moduleId,
        enabled: Boolean,
        required: Boolean,
        unrelatedEnabled: Boolean? = null,
        collectionIntervalSeconds: Long = 900,
    ): AndroidDataCollectionSetting {
        val modules = linkedMapOf(
            moduleId to CollectionModuleSetting(
                enabled = enabled,
                required = required,
                collectionCadence = CollectionCadence(collectionIntervalSeconds),
            )
        )
        if (unrelatedEnabled != null) {
            modules[CollectionModuleId.USAGE_EVENTS] = CollectionModuleSetting(enabled = unrelatedEnabled)
        }
        return AndroidDataCollectionSetting(modules = modules, settingsVersion = version)
    }
}
