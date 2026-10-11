package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionHaltGateTest {
    private val moduleId = CollectionModuleId.BATTERY_TELEMETRY

    @Test
    fun `uploaded optional module must be enabled and accepted at its current policy`() {
        val settings = setting(version = 1, enabled = true, required = false)
        listOf(emptyMap(), mapOf(moduleId to declined(version = 1))).forEach { decisions ->
            assertTrue(collectionIsHalted(settings, listOf(settings), decisions, setOf(moduleId)))
        }
        assertFalse(collectionIsHalted(settings, listOf(settings), mapOf(moduleId to accepted(version = 1)), setOf(moduleId)))
        val disabled = setting(version = 2, enabled = false, required = false)
        assertTrue(collectionIsHalted(disabled, listOf(settings, disabled), mapOf(moduleId to accepted(version = 1)), setOf(moduleId)))
    }

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

    @Test
    fun historicalQueueHonorsFlushHoldDiscardAndPolicyEdits() {
        val old = setting(version = 1, enabled = true, required = false)
        val decisions = mapOf(moduleId to accepted(1))
        CollectionDataDisposition.entries.forEach { disposition ->
            val latest = setting(version = 2, enabled = false, required = false).copy(
                modules = mapOf(moduleId to CollectionModuleSetting(enabled = false, disableDisposition = disposition)))
            val authorization = checkNotNull(collectionUploadAuthorization(latest, listOf(old, latest), decisions,
                decisions, setOf(moduleId), 1))
            assertEquals(setOf(moduleId), when (disposition) {
                CollectionDataDisposition.FLUSH_THEN_STOP -> authorization.acceptedModules
                CollectionDataDisposition.HOLD_PENDING -> authorization.heldModules
                CollectionDataDisposition.DISCARD_AND_STOP -> authorization.discardedModules
            })
        }
        val edited = setting(version = 2, enabled = true, required = false, collectionIntervalSeconds = 300)
        assertEquals(setOf(moduleId), checkNotNull(collectionUploadAuthorization(edited, listOf(old, edited), decisions,
            decisions, setOf(moduleId), 1)).acceptedModules)
        assertEquals(setOf(moduleId), checkNotNull(collectionUploadAuthorization(edited, listOf(old, edited), decisions,
            decisions, setOf(moduleId), 2)).heldModules)
        val legacyDisabled = setting(version = 2, enabled = false, required = false)
        assertEquals(setOf(moduleId), checkNotNull(collectionUploadAuthorization(legacyDisabled, listOf(old, legacyDisabled),
            decisions, decisions, setOf(moduleId), null)).acceptedModules)
    }

    @Test
    fun historicalVersionCannotOverrideConsentRevocationOrMissingEvidence() {
        val old = setting(version = 1, enabled = true, required = false)
        val latest = setting(version = 2, enabled = true, required = false)
        val authorization = checkNotNull(collectionUploadAuthorization(latest, listOf(old, latest), mapOf(moduleId to declined(2)),
            mapOf(moduleId to accepted(1)), setOf(moduleId), 1))
        assertTrue(authorization.acceptedModules.isEmpty())
        assertTrue(collectionUploadAuthorization(latest, listOf(latest), mapOf(moduleId to accepted(1)),
            mapOf(moduleId to accepted(1)), setOf(moduleId), 1) == null)
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
