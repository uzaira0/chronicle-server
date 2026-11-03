package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.storage.RecordedCollectionDecision
import com.openlattice.chronicle.storage.current
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
    fun `currently required module with no decision leaves accepted siblings collecting`() {
        assertFalse(
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
    fun `newly required module preserves an acceptance from its optional revision`() {
        assertFalse(
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
                uploadModules = setOf(moduleId),
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
    fun `required module cadence edit keeps its acceptance`() {
        assertFalse(
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
                uploadModules = setOf(moduleId),
            ),
        )
    }

    @Test
    fun `required decline survives every settings edit until accepted or disabled`() {
        val declinedAt = setting(version = 1, enabled = true, required = true)
        for (edit in listOf(
            setting(version = 2, enabled = true, required = true, collectionIntervalSeconds = 60),
            setting(version = 2, enabled = true, required = true, unrelatedEnabled = true),
            setting(version = 2, enabled = true, required = true, unrelatedEnabled = false),
        )) {
            assertTrue("$edit", collectionIsHalted(edit, listOf(declinedAt, edit), mapOf(moduleId to declined(version = 1))))
            assertFalse("$edit", collectionIsHalted(edit, listOf(declinedAt, edit), mapOf(moduleId to accepted(version = 2))))
        }
        val disabled = setting(version = 2, enabled = false, required = false)
        assertFalse(collectionIsHalted(disabled, listOf(declinedAt, disabled), mapOf(moduleId to declined(version = 1))))
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
                uploadModules = setOf(moduleId),
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
                uploadModules = setOf(moduleId),
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
                uploadModules = setOf(moduleId),
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
    fun `rows captured while a module was enabled upload after it is disabled and none are held`() {
        val enabled = setting(version = 1, enabled = true, required = false)
        val disabled = setting(version = 2, enabled = false, required = false)
        val history = listOf(RecordedCollectionDecision(moduleId, accepted(1), seq = 1))
        val beforeDisable = collectionUploadAuthorization(enabled, listOf(enabled, disabled), history.current(1), setOf(moduleId))
        val afterDisable = collectionUploadAuthorization(disabled, listOf(enabled, disabled), history.current(1), setOf(moduleId))
        assertEquals(setOf(moduleId), beforeDisable.acceptedModules)
        assertEquals(setOf(moduleId), afterDisable.discardedModules)
        assertTrue(afterDisable.acceptedModules.isEmpty())
    }

    @Test
    fun `a cadence edit keeps rows on both sides of it accepted`() {
        val old = setting(version = 1, enabled = true, required = false)
        val edited = setting(version = 2, enabled = true, required = false, collectionIntervalSeconds = 300)
        val history = listOf(RecordedCollectionDecision(moduleId, accepted(1), seq = 1))
        listOf(old, edited).forEach { captured ->
            assertEquals(setOf(moduleId),
                collectionUploadAuthorization(captured, listOf(old, edited), history.current(1), setOf(moduleId)).acceptedModules)
        }
    }

    @Test
    fun `decline and reaccept at one version judge each row by the decision in force at its sequence`() {
        val settings = setting(version = 1, enabled = true, required = false)
        val history = listOf(
            RecordedCollectionDecision(moduleId, accepted(1), seq = 1),
            RecordedCollectionDecision(moduleId, declined(1), seq = 2),
            RecordedCollectionDecision(moduleId, accepted(1), seq = 3),
        )
        val accepted = (1L..3L).map { seq ->
            collectionUploadAuthorization(settings, listOf(settings), history.current(seq), setOf(moduleId)).acceptedModules
        }
        assertEquals(listOf(setOf(moduleId), emptySet(), setOf(moduleId)), accepted)
    }

    @Test
    fun `never consented disabled fields are discarded and consented usage is accepted`() {
        val usage = CollectionModuleId.USAGE_EVENTS
        val fields = setOf(CollectionModuleId.IN_APP_ACTIVITY_CLASS, CollectionModuleId.USER_IDENTIFICATION)
        val lifecycle = CollectionModuleId.DEVICE_LIFECYCLE
        val settings = AndroidDataCollectionSetting(modules = mapOf(usage to CollectionModuleSetting(enabled = true),
            lifecycle to CollectionModuleSetting(enabled = false)) +
            fields.associateWith { CollectionModuleSetting(enabled = false) }, settingsVersion = 1)
        val history = listOf(RecordedCollectionDecision(usage, accepted(1), seq = 1))
        val authorization = collectionUploadAuthorization(settings, listOf(settings), history.current(), fields + usage + lifecycle)
        assertEquals(setOf(usage), authorization.acceptedModules)
        assertEquals(fields + lifecycle, authorization.discardedModules)
    }

    @Test
    fun `a required decline in force at capture discards every module of the batch`() {
        val settings = AndroidDataCollectionSetting(modules = mapOf(
            moduleId to CollectionModuleSetting(enabled = true, required = true),
            CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true)), settingsVersion = 1)
        val history = listOf(
            RecordedCollectionDecision(moduleId, accepted(1), seq = 1),
            RecordedCollectionDecision(CollectionModuleId.USAGE_EVENTS, accepted(1), seq = 1),
            RecordedCollectionDecision(moduleId, declined(1), seq = 2),
        )
        val modules = setOf(moduleId, CollectionModuleId.USAGE_EVENTS)
        assertEquals(modules, collectionUploadAuthorization(settings, listOf(settings), history.current(1), modules).acceptedModules)
        assertEquals(modules, collectionUploadAuthorization(settings, listOf(settings), history.current(2), modules).discardedModules)
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
