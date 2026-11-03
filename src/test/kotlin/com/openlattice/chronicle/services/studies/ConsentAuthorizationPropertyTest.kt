package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * Property: over generated sequences of participant decisions, researcher revisions, late settings syncs and captures,
 * every row captured while its module (and its field) was accepted on the phone is stored, and no other row or field
 * is, whatever the phone's clock says. The expected outcome comes from a phone model that knows nothing of the
 * server's rule: a decision turns a module on or off, and a disable the phone applies ends its acceptance.
 */
class ConsentAuthorizationPropertyTest {
    companion object {
        private lateinit var db: ConsentGatePostgres
        private const val PARTICIPANT_ID = ConsentGatePostgres.PARTICIPANT_ID
        private val gated = listOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            CollectionModuleId.BATTERY_TELEMETRY)

        @BeforeClass @JvmStatic fun setUp() { db = ConsentGatePostgres("chronicle_consent_property") }
        @AfterClass @JvmStatic fun tearDown() { if (Companion::db.isInitialized) db.close() }
    }

    private data class Capture(val modules: Set<CollectionModuleId>, val version: Int, val seq: Long,
                               val expected: Set<CollectionModuleId>)

    private class Server(val revisions: MutableList<Map<CollectionModuleId, CollectionModuleSetting>>)

    /** The phone: the revision it last applied, its decision counter and its standing acceptances. */
    /** One enrolled phone: its study, random device id and API key. */
    private data class Device(val studyId: UUID, val id: UUID, val key: UUID)

    private class PhoneModel(var applied: Int, var seq: Long, val accepted: MutableSet<CollectionModuleId>)

    @Test
    fun everyCaptureIsAuthorizedExactlyForTheModulesAcceptedAtCapture() {
        val random = Random(20261028)
        repeat(30) { run -> sequence(random, run) }
    }

    private fun sequence(random: Random, run: Int) {
        val initial = gated.associateWith { CollectionModuleSetting(enabled = true) } +
            (CollectionModuleId.UPLOAD_TELEMETRY to CollectionModuleSetting(enabled = true))
        val studyId = db.newStudy(required = false, settingsVersion = 1, collection = setting(initial, 1))
        val device = UUID.randomUUID()
        val key = UUID.randomUUID()
        val server = Server(mutableListOf(initial))
        val enrollmentAccepted = gated.filterTo(linkedSetOf()) { random.nextBoolean() }
        val phone = PhoneModel(applied = 1, seq = 1, accepted = enrollmentAccepted.toMutableSet())
        record(Device(studyId, device, key), enrollmentAccepted, gated.toSet() - enrollmentAccepted, phone, ConsentTrigger.ENROLLMENT, random)
        val captures = mutableListOf<Capture>()
        repeat(14) {
            when (random.nextInt(4)) {
                0 -> decide(studyId, device, key, server, phone, random)
                1 -> revise(studyId, server, random)
                2 -> apply(server, phone, random)
                else -> captures += capture(server, phone, random)
            }
        }
        captures.forEach { capture ->
            val request = CollectionUploadRequest(studyId, PARTICIPANT_ID, device, capture.version, capture.seq)
            val authorized = db.service.withCollectionUploadRecheck(request, capture.modules, allowPartial = true) { it.acceptedModules }
            assertEquals("run $run: $capture", capture.expected, authorized)
        }
    }

    private fun decide(studyId: UUID, device: UUID, key: UUID, server: Server, phone: PhoneModel, random: Random) {
        val enabled = gated.filter { server.revisions[phone.applied - 1].getValue(it).enabled }
        if (enabled.isEmpty()) return
        val module = enabled.random(random)
        val accept = random.nextBoolean()
        phone.seq++
        if (accept) phone.accepted += module else phone.accepted -= module
        record(Device(studyId, device, key), if (accept) setOf(module) else emptySet(), if (accept) emptySet() else setOf(module),
            phone, ConsentTrigger.PARTICIPANT_TOGGLE, random)
    }

    /** The researcher toggles a module or edits the battery interval; the phone has not synced yet. */
    private fun revise(studyId: UUID, server: Server, random: Random) {
        val latest = server.revisions.last()
        val module = gated.random(random)
        val next = if (module == CollectionModuleId.BATTERY_TELEMETRY && random.nextBoolean()) {
            latest + (module to latest.getValue(module).copy(collectionCadence = CollectionCadence(
                if (latest.getValue(module).collectionCadence.intervalSeconds == 900L) 1800 else 900)))
        } else latest + (module to latest.getValue(module).copy(enabled = !latest.getValue(module).enabled))
        server.revisions += next
        db.postgres.createConnection("").use { connection ->
            db.updateCollectionSettings(connection, studyId, required = false, settingsVersion = server.revisions.size,
                collection = setting(next, server.revisions.size))
        }
    }

    /** A settings sync, possibly skipping revisions: any disable it crosses ends the module's acceptance. */
    private fun apply(server: Server, phone: PhoneModel, random: Random) {
        val target = random.nextInt(phone.applied, server.revisions.size + 1)
        ((phone.applied + 1)..target).forEach { version ->
            gated.filterNot { server.revisions[version - 1].getValue(it).enabled }.forEach(phone.accepted::remove)
        }
        phone.applied = target
    }

    /** The phone may capture whatever its state; a buggy or tampered phone must not get unaccepted rows stored. */
    private fun capture(server: Server, phone: PhoneModel, random: Random): Capture {
        val policy = server.revisions[phone.applied - 1]
        val collecting = gated.filterTo(linkedSetOf()) { it in phone.accepted && policy.getValue(it).enabled }
        val modules = if (random.nextBoolean()) setOf(CollectionModuleId.BATTERY_TELEMETRY)
            else setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        return Capture(modules, phone.applied, phone.seq, modules.intersect(collecting))
    }

    /**
     * Records the phone's decision at its applied version and current sequence. Each decision carries a phone wall
     * time anywhere within a day of the server: it must decide nothing.
     */
    private fun record(device: Device, accepted: Set<CollectionModuleId>,
                       declined: Set<CollectionModuleId>, phone: PhoneModel, trigger: ConsentTrigger, random: Random) {
        if (accepted.isEmpty() && declined.isEmpty()) return
        db.postgres.createConnection("").use { connection ->
            db.seedDecision(connection, device.studyId, device.id, device.key, CollectionAcknowledgment(
                acknowledgedModules = accepted, declinedModules = declined,
                acknowledgedAt = OffsetDateTime.now().plusSeconds(random.nextLong(-86_400, 86_400)),
                trigger = trigger, settingsVersion = phone.applied, decisionSeq = phone.seq))
        }
    }

    private fun setting(modules: Map<CollectionModuleId, CollectionModuleSetting>, version: Int) =
        AndroidDataCollectionSetting(modules = modules, settingsVersion = version)
}
