package com.openlattice.chronicle.study

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.awaitSessionsBlockedBy
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.client.updateParticipationStatusAsRead
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.UsageEnrollmentFixtures
import com.openlattice.chronicle.UsageEnrollmentFixtures.beginUsageEnrollmentFixture
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.ParticipantConsentState
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upload authorization by integers: a row is judged by the settings version and the
 * decision sequence the phone was under when it captured the row. Every case runs with the phone clock behind and
 * ahead of the server by seconds, minutes and hours; none of the outcomes may depend on it.
 */
class ConsentCaptureAuthorizationTest : ChronicleServerTests() {
    private val skews = listOf(-3L * 3600, -120L, -5L, 5L, 120L, 3L * 3600).map(Duration::ofSeconds)
    private val chronicleClient: ChronicleClient = clientUser1
    private val studyApi get() = chronicleClient.studyApi

    /** One enrolled phone; a [sibling] shares its study and participant as a second device. */
    private inner class Phone(val skew: Duration, sibling: Phone? = null) {
        val studyId: UUID = sibling?.studyId
            ?: studyApi.createStudy(Study(title = "consent by integers", contact = "test@openlattice.com"))
        val participant: Participant = sibling?.participant ?: TestDataFactory.participant()
        val device = TestDataFactory.androidDevice()
        private var prepared = sibling != null
        private var enrolled: UsageEnrollmentFixtures.UsageEnrollmentFixture? = null
        val enrollment get() = checkNotNull(enrolled) { "enroll() runs first" }
        var seq = 0L
        private var clock: OffsetDateTime = OffsetDateTime.now().plus(skew)

        /** The phone's wall clock, which only moves forward between steps. */
        fun now(): OffsetDateTime = clock.also { clock = clock.plusSeconds(1) }

        /** Enrolls this device (again, with a new key, when repeated); the first call also sets up the study. */
        fun enroll(
            declined: Set<CollectionModuleId> = emptySet(),
            extra: Set<CollectionModuleId> = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        ) {
            if (!prepared) {
                UsageEnrollmentFixtures.prepareUsageFixture(chronicleClient, studyId)
                update { it + extra.associateWith { module -> CollectionDefaults.moduleSetting(module, enabled = true) } }
                studyApi.registerParticipant(studyId, participant)
                prepared = true
            }
            enrolled = beginUsageEnrollmentFixture(studyId, participant.participantId, device)
            val receipt = enrollment.acknowledgment
            seq++
            report(receipt.copy(acknowledgedModules = receipt.acknowledgedModules - declined, declinedModules = declined,
                acknowledgedAt = now(), decisionSeq = seq))
        }

        fun version(): Int = (studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
            as AndroidDataCollectionSetting).settingsVersion

        fun update(change: (Map<CollectionModuleId, CollectionModuleSetting>) -> Map<CollectionModuleId, CollectionModuleSetting>) {
            val current = studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
            studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, current.copy(modules = change(current.modules)))
        }

        fun setModule(module: CollectionModuleId, change: (CollectionModuleSetting) -> CollectionModuleSetting) =
            update { it + (module to change(it.getValue(module))) }

        fun decide(accepted: Set<CollectionModuleId> = emptySet(), declined: Set<CollectionModuleId> = emptySet()) {
            seq++
            report(decision(accepted, declined, seq))
        }

        fun decision(accepted: Set<CollectionModuleId>, declined: Set<CollectionModuleId>, decisionSeq: Long) =
            CollectionAcknowledgment(acknowledgedModules = accepted, declinedModules = declined, acknowledgedAt = now(),
                trigger = ConsentTrigger.PARTICIPANT_TOGGLE, settingsVersion = version(),
                disclosureVersion = enrollment.acknowledgment.disclosureVersion,
                manifestDigest = enrollment.acknowledgment.manifestDigest, decisionSeq = decisionSeq)

        fun report(acknowledgment: CollectionAcknowledgment) {
            enrollment.mobileApi.reportCollectionAcknowledgmentV4(studyId, participant.participantId, device.randomDeviceId,
                acknowledgment)
        }

        fun usage(activityClass: String?): ChronicleUsageEvent = (TestDataFactory.chronicleUsageEvents(studyId,
            participant.participantId, count = 1).single() as ChronicleUsageEvent)
            .copy(timestamp = now(), activityClass = activityClass, clientEventId = UUID.randomUUID())

        /** One device lifecycle row per Android lifecycle broadcast, each with its state in the activity class. */
        fun lifecycle(): List<ChronicleUsageEvent> = lifecycleStates.map { (interaction, state) ->
            usage(state).copy(appPackageName = "android", interactionType = interaction, eventType = fromInteractionType(interaction),
                user = "", applicationLabel = "Android System")
        }

        fun uploadUsage(events: List<ChronicleUsageEvent>, version: Int, decisionSeq: Long) = enrollment.mobileApi
            .uploadAndroidUsageEventDataV4(studyId, participant.participantId, device.randomDeviceId, ChronicleData(events),
                version, decisionSeq)

        fun battery(): BatterySample = BatterySample(UUID.randomUUID().toString(), now(), "UTC", 50,
            BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 250, 4000, BatteryHealth.GOOD)

        fun uploadBattery(samples: List<BatterySample>, version: Int, decisionSeq: Long) = enrollment.mobileApi
            .uploadBatteryTelemetryV4(studyId, participant.participantId, device.randomDeviceId, samples, version, decisionSeq)

        fun storedActivityClasses(): Map<String, String?> = hds.connection.use { connection ->
            connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
            connection.prepareStatement("""
                SELECT app_package_name, activity_class FROM chronicle_usage_events WHERE study_id::text = ? AND participant_id = ?
                UNION ALL
                SELECT sample -> 'app_package_name' ->> 'value', sample -> 'activity_class' ->> 'value'
                FROM upload_buffer, jsonb_array_elements(data) sample
                WHERE study_id::text = ? AND participant_id = ? AND upload_type = 'Android'
            """.trimIndent()).use { statement ->
                for (offset in listOf(0, 2)) {
                    statement.setString(offset + 1, studyId.toString())
                    statement.setString(offset + 2, participant.participantId)
                }
                statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
            }
        }

        /** Every usage row stored for the participant after the buffer drains; a row stored twice appears twice. */
        fun storedUsage(): List<StoredUsage> {
            drainUsageBuffer()
            return hds.connection.use { connection ->
                connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
                connection.prepareStatement("""
                    SELECT client_event_id, activity_class, username FROM chronicle_usage_events
                    WHERE study_id::text = ? AND participant_id = ?
                """.trimIndent()).use { statement ->
                    statement.setString(1, studyId.toString())
                    statement.setString(2, participant.participantId)
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) add(StoredUsage(UUID.fromString(rows.getString(1)), rows.getString(2),
                            rows.getString(3).orEmpty()))
                    } }
                }
            }
        }

        /** The usernames each buffered (not yet drained) usage row carries, by client event ID. */
        fun bufferedUsers(): Map<UUID, Set<String>> = hds.connection.use { connection ->
            connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
            connection.prepareStatement("""
                SELECT sample -> 'client_event_id' ->> 'value', sample -> 'username' ->> 'value'
                FROM upload_buffer, jsonb_array_elements(data) sample
                WHERE study_id::text = ? AND participant_id = ? AND upload_type = 'Android'
            """.trimIndent()).use { statement ->
                statement.setString(1, studyId.toString())
                statement.setString(2, participant.participantId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(UUID.fromString(rows.getString(1)) to rows.getString(2).orEmpty()) }
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
            }
        }

        /** Battery sample IDs stored for the participant, each once per stored row. */
        fun storedBatteryIds(): List<String> = hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT sample_id::text FROM battery_telemetry WHERE study_id = ? AND participant_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participant.participantId)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

        fun lastUpload(): OffsetDateTime? = studyApi.getParticipantStats(studyId)[participant.participantId]?.androidLastPing

        fun decisionRows(): Int = hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM participant_collection_acknowledgment WHERE study_id = ? AND participant_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participant.participantId)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }

    private data class StoredUsage(val clientEventId: UUID, val activityClass: String?, val username: String)

    private fun drainUsageBuffer() = RLSRequestContext.withSystemContext {
        testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage()
    }

    private fun eachClock(case: (Phone) -> Unit) = skews.forEach { skew ->
        try { case(Phone(skew)) } catch (failure: AssertionError) {
            throw AssertionError("phone clock skewed by $skew: ${failure.message}", failure)
        }
    }

    /** An optional field is stripped from an event it was not authorized for; the event itself is stored. */
    @Test
    fun aFieldAcceptedAfterEnrollmentIsStrippedFromEarlierEventsAndKeptOnLaterOnes() = eachClock { phone ->
        phone.enroll(declined = setOf(CollectionModuleId.IN_APP_ACTIVITY_CLASS))
        val version = phone.version()
        val beforeUpload = phone.usage("BeforeActivity")
        val first = phone.uploadUsage(listOf(beforeUpload), version, phone.seq)
        assertEquals(setOf(beforeUpload.clientEventId), first.acceptedIds)
        val queuedBeforeAcceptance = phone.usage("QueuedActivity")
        val enrollmentSeq = phone.seq
        phone.decide(accepted = setOf(CollectionModuleId.IN_APP_ACTIVITY_CLASS))
        val afterAcceptance = phone.usage("AcceptedActivity")
        val queued = phone.uploadUsage(listOf(queuedBeforeAcceptance), version, enrollmentSeq)
        val after = phone.uploadUsage(listOf(afterAcceptance), version, phone.seq)
        assertEquals(setOf(queuedBeforeAcceptance.clientEventId), queued.acceptedIds)
        assertEquals(setOf(afterAcceptance.clientEventId), after.acceptedIds)
        assertEquals(mapOf(beforeUpload.appPackageName to null, queuedBeforeAcceptance.appPackageName to null,
            afterAcceptance.appPackageName to "AcceptedActivity"), phone.storedActivityClasses())
    }

    /** Decline and re-accept under one settings version; each row is judged by the decision it was captured under. */
    @Test
    fun aDeclineAndReacceptAtOneVersionAuthorizeEachRowByItsDecision() = eachClock { phone ->
        phone.enroll()
        val version = phone.version()
        val accepted = phone.battery()
        val acceptedSeq = phone.seq
        phone.decide(declined = setOf(CollectionModuleId.BATTERY_TELEMETRY))
        val declined = phone.battery()
        val declinedSeq = phone.seq
        phone.decide(accepted = setOf(CollectionModuleId.BATTERY_TELEMETRY))
        val reaccepted = phone.battery()
        val first = phone.uploadBattery(listOf(accepted), version, acceptedSeq)
        val lastUpload = phone.lastUpload()
        val second = phone.uploadBattery(listOf(declined), version, declinedSeq)
        assertEquals("a discarded batch does not advance the last upload", lastUpload, phone.lastUpload())
        val third = phone.uploadBattery(listOf(reaccepted), version, phone.seq)
        assertEquals(setOf(UUID.fromString(accepted.id)), first.acceptedIds)
        assertEquals(setOf(UUID.fromString(declined.id)), second.discardedIds)
        assertEquals(emptySet<UUID>(), second.acceptedIds)
        assertEquals(setOf(UUID.fromString(reaccepted.id)), third.acceptedIds)
        // The receipt and the table agree, and a retried batch gets the same answer and stores nothing twice.
        assertEquals(first, phone.uploadBattery(listOf(accepted), version, acceptedSeq))
        assertEquals(second, phone.uploadBattery(listOf(declined), version, declinedSeq))
        assertEquals(third, phone.uploadBattery(listOf(reaccepted), version, phone.seq))
        assertEquals(listOf(accepted.id, reaccepted.id).sorted(), phone.storedBatteryIds().sorted())
    }

    /** A retried acknowledgment after a lost response records one decision. */
    @Test
    fun theSameAcknowledgmentDeliveredTwiceRecordsOneDecision() = eachClock { phone ->
        phone.enroll()
        val toggle = phone.decision(emptySet(), setOf(CollectionModuleId.BATTERY_TELEMETRY), phone.seq + 1)
        phone.report(toggle)
        phone.report(toggle)
        assertEquals(2, phone.decisionRows())
        val conflicting = toggle.copy(acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY), declinedModules = emptySet())
        val refused = runCatching { phone.report(conflicting) }.exceptionOrNull() as RhizomeRetrofitCallException
        assertEquals(400, refused.code)
        assertEquals(2, phone.decisionRows())
    }

    /** A battery interval edit keeps the participant's acceptance at the new version. */
    @Test
    fun aBatteryIntervalEditKeepsBatteryAccepted() = eachClock { phone ->
        phone.enroll()
        val before = phone.version()
        phone.setModule(CollectionModuleId.BATTERY_TELEMETRY) {
            it.copy(collectionCadence = CollectionCadence(intervalSeconds = 1800, jitterSeconds = 0))
        }
        val after = phone.version()
        val queued = phone.battery()
        val sampled = phone.battery()
        assertEquals(setOf(UUID.fromString(queued.id)), phone.uploadBattery(listOf(queued), before, phone.seq).acceptedIds)
        assertEquals(setOf(UUID.fromString(sampled.id)), phone.uploadBattery(listOf(sampled), after, phone.seq).acceptedIds)
        assertEquals(listOf(queued.id, sampled.id).sorted(), phone.storedBatteryIds().sorted())
    }

    /**
     * A researcher disables modules while the phone is offline. Rows and fields captured before the phone applied the
     * disable are stored; nothing is held. Disable then re-enable needs a new acceptance.
     */
    @Test
    fun rowsCapturedBeforeADisableUploadAndAReenableNeedsANewAcceptance() = eachClock { phone ->
        phone.enroll()
        val enrolled = phone.version()
        val battery = phone.battery()
        val usage = phone.usage("OfflineActivity")
        phone.setModule(CollectionModuleId.BATTERY_TELEMETRY) { it.copy(enabled = false) }
        phone.setModule(CollectionModuleId.IN_APP_ACTIVITY_CLASS) { it.copy(enabled = false) }
        val disabled = phone.version()
        assertEquals(setOf(UUID.fromString(battery.id)), phone.uploadBattery(listOf(battery), enrolled, phone.seq).acceptedIds)
        val receipt = phone.uploadUsage(listOf(usage), enrolled, phone.seq)
        assertEquals(setOf(usage.clientEventId), receipt.acceptedIds)
        assertEquals("OfflineActivity", phone.storedActivityClasses()[usage.appPackageName])
        val afterDisable = phone.battery()
        assertEquals(setOf(UUID.fromString(afterDisable.id)),
            phone.uploadBattery(listOf(afterDisable), disabled, phone.seq).discardedIds)

        phone.setModule(CollectionModuleId.BATTERY_TELEMETRY) { it.copy(enabled = true) }
        val reenabled = phone.version()
        val unconsented = phone.battery()
        assertEquals(setOf(UUID.fromString(unconsented.id)),
            phone.uploadBattery(listOf(unconsented), reenabled, phone.seq).discardedIds)
        phone.decide(accepted = setOf(CollectionModuleId.BATTERY_TELEMETRY))
        val consented = phone.battery()
        assertEquals(setOf(UUID.fromString(consented.id)),
            phone.uploadBattery(listOf(consented), reenabled, phone.seq).acceptedIds)
        assertEquals("discarded rows are never stored", listOf(battery.id, consented.id).sorted(), phone.storedBatteryIds().sorted())
    }

    /**
     * An acknowledgment is admitted whenever the participant's uploads are. A choice the phone recorded before the
     * researcher marked the participant completed still lands, and the rows the phone held for it upload.
     */
    @Test
    fun aDecisionDeliveredAfterCompletionLandsAndItsRowsUpload() {
        for (status in listOf(ParticipationStatus.COLLECTION_COMPLETED, ParticipationStatus.PAUSED)) {
            val phone = Phone(Duration.ZERO)
            phone.enroll(declined = setOf(CollectionModuleId.BATTERY_TELEMETRY))
            val version = phone.version()
            val pending = phone.decision(setOf(CollectionModuleId.BATTERY_TELEMETRY), emptySet(), phone.seq + 1)
            val held = phone.battery()
            studyApi.updateParticipationStatusAsRead(phone.studyId, phone.participant.participantId, status)
            phone.report(pending)
            phone.seq = checkNotNull(pending.decisionSeq)
            assertEquals(status.name, 2, phone.decisionRows())
            assertEquals(status.name, setOf(UUID.fromString(held.id)),
                phone.uploadBattery(listOf(held), version, phone.seq).acceptedIds)
            assertEquals(status.name, listOf(held.id), phone.storedBatteryIds())
        }
    }

    /** Mark not enrolled and a withdrawal are a hard stop for acknowledgments too. */
    @Test
    fun anAcknowledgmentAfterMarkNotEnrolledOrWithdrawalIsRefused() {
        val marked = Phone(Duration.ZERO).apply { enroll() }
        studyApi.updateParticipationStatusAsRead(marked.studyId, marked.participant.participantId, ParticipationStatus.NOT_ENROLLED)
        assertRefused(marked)

        val withdrawn = Phone(Duration.ZERO).apply { enroll() }
        val replay = withdrawn.enrollment.replay
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH}"))
                .header(ChronicleHeaders.API_KEY_HEADER, replay.proposedApiKey)
                .header(ChronicleHeaders.WITHDRAWAL_REQUEST_ID_HEADER, UUID.randomUUID().toString())
                .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, replay.device.randomDeviceId)
                .DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(response.body(), response.statusCode() in 200..299)
        assertRefused(withdrawn)
    }

    private fun assertRefused(phone: Phone) {
        val rows = phone.decisionRows()
        val refused = runCatching { phone.decide(declined = setOf(CollectionModuleId.BATTERY_TELEMETRY)) }.exceptionOrNull()
        assertTrue("expected a refusal, got $refused", refused is RhizomeRetrofitCallException && refused.code in 401..403)
        assertEquals(rows, phone.decisionRows())
    }

    /**
     * The participants list carries the server's own reading of the participant's decisions, so the page can say
     * why an enrolled participant is not collecting. The latest decision governs.
     */
    @Test
    fun theParticipantsListReportsARequiredDeclineAndModulesAwaitingReview() {
        val phone = Phone(Duration.ZERO)
        phone.enroll()
        val battery = CollectionModuleId.BATTERY_TELEMETRY
        fun state() = studyApi.getStudyParticipants(phone.studyId).single().consentState
        assertEquals(ParticipantConsentState(emptySet(), emptySet()), state())
        phone.setModule(battery) { it.copy(required = true) }
        // The phone's halt flow: declining a newly required module is a settings-change decision.
        phone.seq++
        phone.report(phone.decision(emptySet(), setOf(battery), phone.seq).copy(trigger = ConsentTrigger.SETTINGS_CHANGE))
        assertEquals(ParticipantConsentState(setOf(battery), emptySet()), state())
        phone.decide(accepted = setOf(battery))
        assertEquals(ParticipantConsentState(emptySet(), emptySet()), state())
        phone.setModule(battery) { it.copy(enabled = false) }
        phone.setModule(battery) { it.copy(enabled = true) }
        assertEquals(ParticipantConsentState(emptySet(), setOf(battery)), state())
    }

    /**
     * Lifecycle rows ride the usage batch but answer to their own optional module. A row captured while
     * lifecycle was declined is discarded and never stored; one captured after acceptance is stored with its state in
     * the activity class, although the in-app activity class stays declined and is still stripped from ordinary rows.
     */
    @Test
    fun lifecycleRowsInAMixedBatchFollowTheirOwnConsentAndKeepTheirState() {
        val lifecycle = CollectionModuleId.DEVICE_LIFECYCLE
        val phone = Phone(Duration.ZERO)
        phone.enroll(declined = setOf(lifecycle, CollectionModuleId.IN_APP_ACTIVITY_CLASS),
            extra = setOf(CollectionModuleId.BATTERY_TELEMETRY, lifecycle))
        val version = phone.version()
        val declinedSeq = phone.seq
        val usageWhileDeclined = phone.usage("org.example.Main")
        val lifecycleWhileDeclined = phone.lifecycle()
        phone.decide(accepted = setOf(lifecycle))
        val usageWhileAccepted = phone.usage("org.example.Second")
        val lifecycleWhileAccepted = phone.lifecycle()
        val batches = listOf(listOf(usageWhileDeclined) + lifecycleWhileDeclined to declinedSeq,
            listOf(usageWhileAccepted) + lifecycleWhileAccepted to phone.seq)
        val receipts = batches.map { (events, seq) -> phone.uploadUsage(events, version, seq) }
        assertEquals(setOf(usageWhileDeclined.clientEventId), receipts[0].acceptedIds)
        assertEquals(lifecycleWhileDeclined.mapTo(hashSetOf()) { it.clientEventId }, receipts[0].discardedIds)
        assertEquals((listOf(usageWhileAccepted) + lifecycleWhileAccepted).mapTo(hashSetOf()) { it.clientEventId },
            receipts[1].acceptedIds)
        assertEquals(emptySet<UUID>(), receipts[1].discardedIds)
        assertEquals("a retried batch gets the same receipt", receipts,
            batches.map { (events, seq) -> phone.uploadUsage(events, version, seq) })
        val expected = listOf(
            StoredUsage(checkNotNull(usageWhileDeclined.clientEventId), null, usageWhileDeclined.user),
            StoredUsage(checkNotNull(usageWhileAccepted.clientEventId), null, usageWhileAccepted.user),
        ) + lifecycleWhileAccepted.map { StoredUsage(checkNotNull(it.clientEventId), it.activityClass, "") }
        assertEquals(expected.sortedBy { it.clientEventId }, phone.storedUsage().sortedBy { it.clientEventId })
    }

    /**
     * The identifying label follows its own module, decision by decision, while base usage stays accepted.
     * Rows captured under each decision upload out of order and twice; only rows captured under acceptance keep the
     * label, in the buffer, in storage and in the export.
     */
    @Test
    fun identificationLabelsAreKeptOnlyOnRowsCapturedUnderItsAcceptance() {
        val identification = CollectionModuleId.USER_IDENTIFICATION
        val phone = Phone(Duration.ZERO)
        phone.enroll()
        val version = phone.version()
        val acceptedSeq = phone.seq
        val before = phone.usage(null)
        phone.decide(declined = setOf(identification))
        val declinedSeq = phone.seq
        val during = phone.usage(null)
        phone.decide(accepted = setOf(identification))
        val after = phone.usage(null)
        val uploads = listOf(after to phone.seq, before to acceptedSeq, during to declinedSeq)
        repeat(2) {
            uploads.forEach { (event, seq) ->
                assertEquals(setOf(event.clientEventId), phone.uploadUsage(listOf(event), version, seq).acceptedIds)
            }
        }
        val expectedUsers = mapOf(checkNotNull(before.clientEventId) to before.user, checkNotNull(during.clientEventId) to "",
            checkNotNull(after.clientEventId) to after.user)
        assertTrue("labels are non-empty before upload", listOf(before, during, after).all { it.user.isNotEmpty() })
        assertEquals(expectedUsers.mapValues { setOf(it.value) }, phone.bufferedUsers())
        val stored = phone.storedUsage()
        assertEquals("each row is stored once", expectedUsers.keys, stored.mapTo(hashSetOf()) { it.clientEventId })
        assertEquals(expectedUsers.size, stored.size)
        assertEquals(expectedUsers, stored.associate { it.clientEventId to it.username })
        val exported = chronicleClient.testStudyApi.getParticipantsData(phone.studyId, ParticipantDataType.UsageEvents,
            setOf(phone.participant.participantId), OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), "json")
        assertEquals(expectedUsers, exported.associate {
            UUID.fromString(it.getValue("client_event_id").toString()) to it["username"]?.toString().orEmpty()
        })
    }

    /**
     * Two devices of one participant, with identical settings versions and decision sequences but opposite
     * decisions, upload concurrently. Each device's rows are judged by its own decisions only, before and after the
     * decisions flip and after one device is enrolled again with a new key. A key never speaks for the other device.
     */
    @Test
    fun eachDeviceOfOneParticipantIsJudgedByItsOwnDecisions() {
        val battery = CollectionModuleId.BATTERY_TELEMETRY
        val identification = CollectionModuleId.USER_IDENTIFICATION
        val first = Phone(Duration.ZERO).apply { enroll() }
        val second = Phone(Duration.ZERO, sibling = first).apply { enroll(declined = setOf(battery, identification)) }
        val version = first.version()
        assertEquals(first.seq, second.seq)
        val stored = mutableListOf<String>()
        val expectedUsers = mutableMapOf<UUID, String>()

        /** Both devices upload one battery sample and one usage row at once; [accepting] is the device whose decision accepts. */
        fun uploadBoth(accepting: Phone) {
            val outcomes = listOf(first, second).map { phone ->
                val sample = phone.battery()
                val event = phone.usage(null)
                val seq = phone.seq
                CompletableFuture.supplyAsync {
                    Triple(sample, phone.uploadBattery(listOf(sample), version, seq), event to phone.uploadUsage(listOf(event), version, seq))
                }.thenApply { phone to it }
            }.map { it.get(60, TimeUnit.SECONDS) }
            for ((phone, outcome) in outcomes) {
                val (sample, batteryReceipt, usage) = outcome
                val (event, usageReceipt) = usage
                val accepted = phone === accepting
                assertEquals(if (accepted) setOf(UUID.fromString(sample.id)) else emptySet(), batteryReceipt.acceptedIds)
                assertEquals(if (accepted) emptySet() else setOf(UUID.fromString(sample.id)), batteryReceipt.discardedIds)
                assertEquals("base usage is accepted on both devices", setOf(event.clientEventId), usageReceipt.acceptedIds)
                if (accepted) stored += sample.id
                expectedUsers[checkNotNull(event.clientEventId)] = if (accepted) event.user else ""
            }
            assertEquals(stored.sorted(), first.storedBatteryIds().sorted())
            assertEquals(expectedUsers, first.storedUsage().associate { it.clientEventId to it.username })
        }

        uploadBoth(accepting = first)
        first.decide(declined = setOf(battery, identification))
        second.decide(accepted = setOf(battery, identification))
        assertEquals(first.seq, second.seq)
        uploadBoth(accepting = second)

        // A new key for the second device carries only its own new decisions; the first device's evidence is unchanged.
        second.enroll(declined = setOf(battery, identification))
        first.decide(accepted = setOf(battery, identification))
        uploadBoth(accepting = first)

        // Negative control: the first device's key cannot upload as the second device.
        val wrongDevice = runCatching {
            first.enrollment.mobileApi.uploadBatteryTelemetryV4(first.studyId, first.participant.participantId,
                second.device.randomDeviceId, listOf(first.battery()), version, first.seq)
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $wrongDevice", wrongDevice is RhizomeRetrofitCallException && wrongDevice.code in 401..403)
        assertEquals(stored.sorted(), first.storedBatteryIds().sorted())
    }

    /**
     * A required-module decline and an upload race over HTTP. Whichever transaction takes the device's
     * evidence lock first wins whole: an upload that wins stores its batch before the decline lands; a decline that wins
     * refuses the upload, which stores nothing. Either way the receipt and the table agree and later uploads are refused.
     */
    @Test
    fun aRequiredDeclineAndAnUploadSerializeWhicheverWins() = listOf(true, false).forEach(::raceRequiredDecline)

    private fun raceRequiredDecline(uploadWins: Boolean) {
        val battery = CollectionModuleId.BATTERY_TELEMETRY
        val phone = Phone(Duration.ZERO).apply { enroll() }
        phone.setModule(battery) { it.copy(required = true) }
        val version = phone.version()
        val sample = phone.battery()
        val uploadSeq = phone.seq
        phone.seq++
        val decline = phone.decision(emptySet(), setOf(battery), phone.seq).copy(trigger = ConsentTrigger.SETTINGS_CHANGE)
        val decisions = phone.decisionRows()
        // The barrier blocks the winner at its first write, after it holds the device lock; the loser queues behind it.
        val lock: (java.sql.Statement) -> Unit = if (uploadWins) {
            { it.execute("LOCK TABLE battery_telemetry IN SHARE MODE") }
        } else {
            { it.execute("LOCK TABLE participant_collection_acknowledgment IN SHARE MODE") }
        }
        val executor = Executors.newFixedThreadPool(2)
        val tasks = listOf(
            Callable { runCatching<Any> { phone.uploadBattery(listOf(sample), version, uploadSeq) } },
            Callable { runCatching<Any> { phone.report(decline) } },
        )
        val (receipt, acknowledgment) = try {
            val submitted = submitBehindLock(lock, if (uploadWins) tasks else tasks.reversed(), executor)
            (if (uploadWins) submitted else submitted.reversed()).map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        acknowledgment.getOrThrow()
        assertEquals("the decline is recorded once", decisions + 1, phone.decisionRows())
        if (uploadWins) {
            assertEquals(setOf(UUID.fromString(sample.id)), (receipt.getOrThrow() as UploadReceipt).acceptedIds)
            assertEquals(listOf(sample.id), phone.storedBatteryIds())
        } else {
            val refused = receipt.exceptionOrNull()
            assertTrue("expected the halt to refuse the upload, got $refused", refused is RhizomeRetrofitCallException && refused.code == 403)
            assertEquals(emptyList<String>(), phone.storedBatteryIds())
        }
        val late = runCatching { phone.uploadBattery(listOf(phone.battery()), version, uploadSeq) }.exceptionOrNull()
        assertTrue("no write lands after the decline, got $late", late is RhizomeRetrofitCallException && late.code == 403)
        assertEquals(if (uploadWins) listOf(sample.id) else emptyList(), phone.storedBatteryIds())
    }

    /** Holds [lock] while [tasks] start in order, each queued behind it before the next starts, then releases them. */
    private fun <T> submitBehindLock(
        lock: (java.sql.Statement) -> Unit, tasks: List<Callable<T>>, executor: java.util.concurrent.ExecutorService,
    ) =
        hds.connection.use { barrier ->
            barrier.autoCommit = false
            try {
                barrier.createStatement().use(lock)
                val futures = tasks.mapIndexed { index, task -> executor.submit(task).also { awaitSessionsBlockedBy(barrier, index + 1) } }
                assertFalse("nothing commits while the winner is held", futures.any { it.isDone })
                futures
            } finally {
                barrier.rollback()
            }
        }

    private companion object {
        /** Android lifecycle broadcasts and the state the phone records with each (independent of the server's list). */
        val lifecycleStates = listOf(
            "Battery Low" to "android.intent.action.BATTERY_LOW",
            "Battery Okay" to "android.intent.action.BATTERY_OKAY",
            "Battery Charging" to "battery:high:charging",
            "Battery Discharging" to "battery:medium:discharging",
            "Power Save Mode On" to "android.os.action.POWER_SAVE_MODE_CHANGED:on",
            "Power Save Mode Off" to "android.os.action.POWER_SAVE_MODE_CHANGED:off",
            "Network Connected" to "android.net.conn.CONNECTIVITY_CHANGE:connected",
            "Network Disconnected" to "android.net.conn.CONNECTIVITY_CHANGE:disconnected",
            "Low Memory" to "memory:trim-level:80",
        )
    }
}
