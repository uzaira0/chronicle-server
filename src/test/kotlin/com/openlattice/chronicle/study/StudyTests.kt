package com.openlattice.chronicle.study

import com.openlattice.chronicle.client.updateParticipationStatusAsRead
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.survey.SurveySettings
import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.acknowledgeUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.beginUsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.newUsageEnrollment
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.storedUsageEventIds
import com.openlattice.chronicle.UsageEnrollmentFixtures.storedUsageTimestamps
import com.openlattice.chronicle.UsageEnrollmentFixtures.submitUsageEnrollment
import com.openlattice.chronicle.UsageEnrollmentFixtures.usageSettingsWithoutUserIdentification
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.ChronicleUsageEventType
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.client.Environment
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.withoutConsentEpochs
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert
import org.junit.Before
import com.openlattice.chronicle.client.assertHttpStatus
import org.junit.Test
import org.springframework.http.HttpStatus

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class StudyTests : ChronicleServerTests() {

    private val chronicleClient2: ChronicleClient = clientUser2
    private val chronicleClient: ChronicleClient = clientUser1
    private var c1: Candidate? = null
    private var c2: Candidate? = null
    private var s1: Study? = null

    @Before
    fun beforeEachTest() {
        c1 = Candidate()
        c2 = Candidate()
        s1 = Study(title = "test study", contact = "test@openlattice.com")
    }

    @After
    fun afterEachTest() {
        c1 = null
        c2 = null
        s1 = null
    }

    @Test
    fun createStudy() {
        val studyApi = chronicleClient.studyApi
        val expected = Study(title = "This is a test study.", contact = "test@openlattice.com")
        val studyId = studyApi.createStudy(expected)
        val study = studyApi.getStudy(studyId)
        Assert.assertEquals(studyId, study.id)
    }

    @Test
    fun longStudyMetadataSurvivesCreateUpdateAndReread() {
        val api = chronicleClient.studyApi
        val original = Study(
            title = "t".repeat(256), description = "d".repeat(4001),
            group = "g".repeat(256), version = "v".repeat(51), contact = "test@openlattice.com",
        )
        val id = api.createStudy(original)
        val created = api.getStudy(id)
        Assert.assertEquals(original.title, created.title)
        Assert.assertEquals(original.description, created.description)
        Assert.assertEquals(original.group, created.group)
        Assert.assertEquals(original.version, created.version)

        val update = StudyUpdate(
            title = original.title.uppercase(), description = original.description.uppercase(),
            group = original.group.uppercase(), version = original.version.uppercase(),
        )
        api.updateStudy(id, update)
        val updated = api.getStudy(id)
        Assert.assertEquals(update.title, updated.title)
        Assert.assertEquals(update.description, updated.description)
        Assert.assertEquals(update.group, updated.group)
        Assert.assertEquals(update.version, updated.version)
    }

    @Test
    fun concurrentFirstEnrollmentOfOneAttemptBindsOneDeviceAndOneCredential() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(Study(title = "Concurrent first enrollment", contact = "test@openlattice.com"))
        prepareUsageFixture(chronicleClient, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)
        val (replay, _) = newUsageEnrollment(studyId, participant.participantId, TestDataFactory.androidDevice())
        val attempts = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(attempts)
        val responses = try {
            List(attempts) { pool.submit<EnrollmentResponse> { start.await(); submitUsageEnrollment(replay) } }
                .also { start.countDown() }
                .map { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }

        Assert.assertEquals(setOf(replay.proposedApiKey), responses.map { it.apiKey }.toSet())
        Assert.assertEquals(1, responses.map { it.chronicleId to it.enrollmentId }.toSet().size)
        val devices = hds.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM devices WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, studyId)
                it.setString(2, participant.participantId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
        Assert.assertEquals(1, devices)
    }

    @Test
    fun testGetAllStudies() {
        val studyApi = chronicleClient.studyApi
        val studies = (0 until 5).map {
            Study(title = "This is a test study.", contact = "test@openlattice.com", version = it.toString())
        }.toSet()

        studies.forEach { it.id = studyApi.createStudy(it) }

        // The suite's other tests also create studies for this user, so read every page, not just the first.
        val all = generateSequence(0) { it + 100 }
            .map { offset -> studyApi.getAllStudies(limit = 100, offset = offset).map { it.id } }
            .takeWhile { it.isNotEmpty() }
            .flatten()
            .toList()
        val expected = studies.map { it.id }.toSet()

        Assert.assertEquals("a study repeated across pages", all.size, all.toSet().size)
        Assert.assertTrue(all.containsAll(expected))
    }


    @Test
    fun updateStudy() {
        val studyApi = chronicleClient.studyApi
        val expected = Study(title = "This is a test study.", contact = "test@openlattice.com")
        val studyId = studyApi.createStudy(expected)
        val study = studyApi.getStudy(studyId)
        Assert.assertEquals(studyId, study.id)

        val desc = "Now it has a description"
        studyApi.updateStudy(
            studyId, StudyUpdate(
                description = desc,
            )
        )

        val updatedStudy1 = studyApi.getStudy(studyId)

        Assert.assertEquals(studyId, study.id)
        Assert.assertEquals(desc, updatedStudy1.description)

        val updatedStudy2 = studyApi.updateStudy(
            studyId, StudyUpdate(
                contact = "updated@openlattice.com"
            ), true
        )!!

        Assert.assertEquals(studyId, study.id)
        Assert.assertEquals(desc, updatedStudy2.description)
        Assert.assertEquals("updated@openlattice.com", updatedStudy2.contact)

    }

    /** An unknown module key fails the whole request as a 400 before StudyService runs: no field is applied. */
    @Test
    fun updateStudyRefusesAnUnknownModuleBeforeAnyChange() {
        val studyId = createCollectionStudy()
        val before = chronicleClient.studyApi.getStudy(studyId)
        val response = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI("http://localhost:$testHttpPort${StudyApi.BASE}/$studyId"))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(
                    """{"title":"renamed","modules":{"CHRONICLE_DATA_COLLECTION":{},"NOT_A_FEATURE":{}}}""",
                )).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        Assert.assertEquals(response.body(), 400, response.statusCode())
        Assert.assertEquals(before, chronicleClient.studyApi.getStudy(studyId))
    }

    @Test
    fun studySurveyFrequencyIsIndependentAndRoundTrips() {
        val api = chronicleClient.studyApi
        val id = api.createStudy(Study(title = "frequency", contact = "test@example.org"))
        Assert.assertEquals(AppUsageSurveySetting(), api.getStudySetting(id, StudySettingType.AppUsageSurvey))
        val collection = api.getStudySetting(id, StudySettingType.DataCollection)
        api.updateStudySettings(id, StudySettingType.AppUsageSurvey, AppUsageSurveySetting(AppUsageFrequency.HOURLY))
        Assert.assertEquals(AppUsageFrequency.HOURLY, chronicleClient.surveyApi.getAppUsageFrequency(id).appUsageFrequency)
        Assert.assertEquals(collection, api.getStudySetting(id, StudySettingType.DataCollection))
        api.updateStudySettings(id, StudySettingType.AppUsageSurvey, AppUsageSurveySetting())
        Assert.assertEquals(AppUsageFrequency.DAILY, chronicleClient.surveyApi.getAppUsageFrequency(id).appUsageFrequency)
    }

    @Test
    fun partialAndAbsentSettingsPreserveOtherKeysAfterEnrollment() {
        val api = chronicleClient.studyApi
        val initial = StudySettings(mapOf(
            StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting(),
            StudySettingType.ParticipantPolicy to TestDataFactory.participantPolicy(),
            StudySettingType.Withdrawal to StudyWithdrawalSetting(),
            StudySettingType.TimeUseDiary to TimeUseDiarySettings(),
        ))
        val id = api.createStudy(Study(title = "settings patch", contact = "test@example.org", settings = initial))
        val before = api.getStudy(id).settings
        api.updateStudy(id, StudyUpdate(settings = StudySettings(mapOf(
            StudySettingType.DataCollection to before.getValue(StudySettingType.DataCollection),
        ))))
        Assert.assertEquals(before, api.getStudy(id).settings)
        api.updateStudy(id, StudyUpdate(description = "partial", settings = StudySettings(mapOf(
            StudySettingType.TimeUseDiary to TimeUseDiarySettings(timeZone = "America/Chicago"),
        ))))
        val patched = api.getStudy(id).settings
        before.filterKeys { it != StudySettingType.TimeUseDiary }.forEach { (key, setting) ->
            Assert.assertEquals("omitted $key must survive", setting, patched[key])
        }
        Assert.assertEquals("America/Chicago", (patched[StudySettingType.TimeUseDiary] as TimeUseDiarySettings).timeZone)
        api.registerParticipant(id, TestDataFactory.participant(ParticipationStatus.ENROLLED))
        api.updateStudy(id, StudyUpdate(title = "details only"))
        Assert.assertEquals(patched, api.getStudy(id).settings)
        // An enrolled participant locks disclosure; an unrelated per-key patch must still succeed.
        api.updateStudy(id, StudyUpdate(description = "after enrollment", settings = StudySettings(mapOf(
            StudySettingType.TimeUseDiary to TimeUseDiarySettings(timeZone = "UTC"),
        ))))
        Assert.assertEquals(initial[StudySettingType.ParticipantPolicy], api.getStudy(id).settings[StudySettingType.ParticipantPolicy])
    }

    @Test
    fun getOrgStudies() {
        val organization1 = Organization(title = "test org 1")
        val organization2 = Organization(title = "test org 2")
        val organization3 = Organization(title = "test org 3")

        // client 1 is owner of orgs 1 & 2 and study 1 & 2
        // client 2 is owner of org 3 and study 3
        val client1OrgId1 = chronicleClient.organizationApi.createOrganization(organization1)
        val client1OrgId2 = chronicleClient.organizationApi.createOrganization(organization2)
        val client2OrgId3 = chronicleClient2.organizationApi.createOrganization(organization3)

        // study 1 owned by client 1, org 1
        val study1OrgIds = setOf(client1OrgId1)
        val study1Title = "org 1 study 1"
        val expectedStudy1 = Study(
            title = study1Title,
            contact = "test@openlattice.com",
            organizationIds = study1OrgIds
        )
        // Study 2 is owned by client 1, in both org 1 and org 2
        val study2OrgIds = setOf(client1OrgId1, client1OrgId2)
        val study2Title = "org 2 study 2"
        val expectedStudy2 = Study(
            title = study2Title,
            contact = "test@openlattice.com",
            organizationIds = study2OrgIds
        )
        // Study 3 is owned by client 2, org 3
        val study3OrgIds = setOf(client2OrgId3)
        val study3Title = "org 3 study 3"
        val expectedStudy3 = Study(
            title = study3Title,
            contact = "test@openlattice.com",
            organizationIds = setOf(client2OrgId3)
        )

        val study1Id = chronicleClient.studyApi.createStudy(expectedStudy1)
        val study2Id = chronicleClient.studyApi.createStudy(expectedStudy2)
        val study3Id = chronicleClient2.studyApi.createStudy(expectedStudy3)

        val actualOrg1Studies = chronicleClient.studyApi.getOrgStudies(client1OrgId1)
        // API returns list in descending creation times (recent first)
        // expect [study 2, study 1] from org 1
        Assert.assertEquals(2, actualOrg1Studies.size)
        Assert.assertEquals(listOf(study2Id, study1Id), actualOrg1Studies.map { study -> study.id })
        Assert.assertEquals(listOf(study2Title, study1Title), actualOrg1Studies.map { study -> study.title })
        Assert.assertEquals(study2OrgIds, actualOrg1Studies[0].organizationIds)
        Assert.assertEquals(study1OrgIds, actualOrg1Studies[1].organizationIds)

        // expect [study 2] from org 2
        val actualOrg2Studies = chronicleClient.studyApi.getOrgStudies(client1OrgId2)
        Assert.assertEquals(1, actualOrg2Studies.size)
        Assert.assertEquals(listOf(study2Id), actualOrg2Studies.map { study -> study.id })
        Assert.assertEquals(listOf(study2Title), actualOrg2Studies.map { study -> study.title })
        Assert.assertEquals(study2OrgIds, actualOrg2Studies[0].organizationIds)

        // expect [study 3] from org 3
        val actualOrg3Studies = chronicleClient2.studyApi.getOrgStudies(client2OrgId3)
        Assert.assertEquals(1, actualOrg3Studies.size)
        Assert.assertEquals(listOf(study3Id), actualOrg3Studies.map { study -> study.id })
        Assert.assertEquals(listOf(study3Title), actualOrg3Studies.map { study -> study.title })
        Assert.assertEquals(study3OrgIds, actualOrg3Studies[0].organizationIds)
    }

    /** RLS INSERT checks are study-scoped; a non-admin clone must still link the new study to its orgs. */
    @Test
    fun nonAdminCloneKeepsOrganizationLinksUnderStudyScopedRls() {
        val orgId = chronicleClient.organizationApi.createOrganization(Organization(title = "clone org"))
        val sourceId = chronicleClient.studyApi.createStudy(
            Study(title = "clone source", contact = "test@openlattice.com", organizationIds = setOf(orgId)),
        )
        val cloneId = chronicleClient.studyLifecycleApi.cloneStudy(sourceId, StudyCloneRequest(newTitle = "clone copy"))
        Assert.assertEquals(setOf(orgId), chronicleClient.studyApi.getStudy(cloneId).organizationIds)
    }

    @Test
    fun registerParticipant() {
        val p = Participant("p1", c1!!, ParticipationStatus.ENROLLED)
        val studyId = clientUser1.studyApi.createStudy(s1!!)
        val candidateId = clientUser1.studyApi.registerParticipant(studyId, p)
        // getCandidate was removed from CandidateApi; verify registration succeeded via returned ID
        Assert.assertNotNull(candidateId)
    }

    @Test
    fun registerParticipantWithExistingCandidate() {
        val candidateId = clientUser1.candidateApi.registerCandidate(c1!!)
        c1!!.id = candidateId
        val p = Participant("p1", c1!!, ParticipationStatus.ENROLLED)
        val studyId = clientUser1.studyApi.createStudy(s1!!)

        val registered = clientUser1.studyApi.registerParticipant(studyId, p)

        // The existing candidate is reused, not copied: the participant row links the same candidate id.
        Assert.assertEquals(candidateId, registered)
        val stored = clientUser1.studyApi.getStudyParticipants(studyId).single { it.participantId == "p1" }
        Assert.assertEquals(ParticipationStatus.ENROLLED, stored.participationStatus)
        Assert.assertEquals(candidateId, stored.candidate.id)
    }

    @Test
    fun registerParticipantWithRandomCandidate() {
        try {
            c1!!.id = UUID.randomUUID()
            val p = Participant("p1", c1!!, ParticipationStatus.ENROLLED)
            val studyId = clientUser1.studyApi.createStudy(s1!!)
            clientUser1.studyApi.registerParticipant(studyId, p)
            Assert.fail()
        } catch (e: RhizomeRetrofitCallException) {
            Assert.assertTrue(
                "should fail with expected error message",
                e.body.contains("cannot register candidate with an invalid id")
            )
        }
    }

    @Test
    fun testStudyParticipants() {
        val study = TestDataFactory.study()
        val p1 = Participant("p1", c1!!, ParticipationStatus.ENROLLED)
        val p2 = Participant("p2", c2!!, ParticipationStatus.PAUSED)
        val studyId = clientUser1.studyApi.createStudy(study)
        val candidateId1 = clientUser1.studyApi.registerParticipant(studyId, p1)
        val candidateId2 = clientUser1.studyApi.registerParticipant(studyId, p2)
        c1!!.id = candidateId1
        c2!!.id = candidateId2
        val actualParticipants = clientUser1.studyApi.getStudyParticipants(studyId).associateBy { it.participantId }

        val actualP1 = actualParticipants[p1.participantId]!!
        compareParticipants(p1, actualP1)
        val actualP2 = actualParticipants[p2.participantId]!!
        compareParticipants(p2, actualP2)
    }

    private fun compareParticipants(a: Participant, b: Participant) {
        Assert.assertEquals(a.participantId, b.participantId)
        Assert.assertEquals(a.participationStatus, b.participationStatus)
        Assert.assertEquals(a.candidate.id, b.candidate.id)
    }

    @Test
    fun enrollmentHasNoParticipantCountLimit() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        // The removed default cap was 25; enrolling past it must still succeed.
        repeat(26) {
            studyApi.registerParticipant(studyId, TestDataFactory.participant(ParticipationStatus.ENROLLED))
        }
        Assert.assertEquals(26, studyApi.getStudyParticipants(studyId).count())
    }



    @Test
    fun testUsageEventUpload() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(
            Study(
                title = "versioned usage-event upload",
                contact = "test@openlattice.com",
                settings = StudySettings(
                    mapOf(
                        StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting(),
                    ),
                ),
            ),
        )

        prepareUsageFixture(chronicleClient, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)

        val sourceDevice = TestDataFactory.androidDevice()
        val mobileApi = enrollUsageFixture(studyId, participant.participantId, sourceDevice).mobileApi

        val chronicleData = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId)
        Assert.assertEquals(chronicleData.size, mobileApi.uploadAndroidUsageEventDataV4(
            studyId,
            participant.participantId,
            sourceDevice.randomDeviceId,
            chronicleData
        ).acceptedIds.size)
    }

    @Test
    fun testAndroidEnrollmentConsentAndMixedUploadSequence() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(Study(title = "Android consent sequence", contact = "test@openlattice.com"))
        prepareUsageFixture(chronicleClient, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)
        val device = TestDataFactory.androidDevice()
        val data = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId, count = 1)
        val anonymousApi = RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE).create(StudyApi::class.java)
        Assert.assertEquals(HttpStatus.UNAUTHORIZED.value(), Assert.assertThrows(RhizomeRetrofitCallException::class.java) {
            anonymousApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId, device.randomDeviceId, data)
        }.code)
        val enrollment = beginUsageEnrollmentFixture(studyId, participant.participantId, device)
        val mobileApi = enrollment.mobileApi
        val unconsented = Assert.assertThrows(RhizomeRetrofitCallException::class.java) {
            mobileApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId, device.randomDeviceId, data)
        }
        Assert.assertEquals(HttpStatus.FORBIDDEN.value(), unconsented.code)
        // Until the device acknowledges its enrollment receipt, the disclosure it reviewed is frozen.
        val revised = usageSettingsWithoutUserIdentification(studyApi, studyId)
        // Another device's later enrollment acknowledgment cannot finish this pending attempt.
        val otherDevice = TestDataFactory.androidDevice()
        enrollUsageFixture(studyId, participant.participantId, otherDevice)
        val frozen = Assert.assertThrows(RhizomeRetrofitCallException::class.java) {
            studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, revised)
        }
        Assert.assertEquals(HttpStatus.CONFLICT.value(), frozen.code)
        acknowledgeUsageFixture(enrollment)
        Assert.assertEquals(1, mobileApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId, device.randomDeviceId, data)
            .acceptedIds.size)

        val lifecycle = (data.single() as ChronicleUsageEvent).copy(
            appPackageName = "android", interactionType = "Battery Low",
            eventType = ChronicleUsageEventType.BATTERY_LOW.value, clientEventId = UUID.randomUUID(),
        )
        // A batch of only unauthorized rows is answered discarded, not refused, so the phone moves past them.
        val optionalOnly = mobileApi.uploadAndroidUsageEventDataV4(
            studyId, participant.participantId, device.randomDeviceId, ChronicleData(listOf(lifecycle)),
        )
        Assert.assertEquals(setOf(lifecycle.clientEventId), optionalOnly.discardedIds)
        Assert.assertEquals(emptySet<UUID>(), optionalOnly.acceptedIds)
        val usageId = checkNotNull((data.single() as ChronicleUsageEvent).clientEventId)
        Assert.assertEquals("a discarded row is not stored", setOf(usageId), storedUsageEventIds(studyId, participant.participantId))

        // A server-side stop can precede the phone's next settings sync. Its queued batch
        // must still deliver accepted usage, without the stopped optional fields or lifecycle row.
        val settings = studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        val stopped = settings.copy(modules = settings.modules +
            setOf(CollectionModuleId.IN_APP_ACTIVITY_CLASS, CollectionModuleId.USER_IDENTIFICATION)
                .associateWith { settings.modules.getValue(it).copy(enabled = false) })
        // The enrollment is acknowledged, so the researcher may revise settings inside the replay window.
        studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, stopped)
        val queued = (data.single() as ChronicleUsageEvent).copy(clientEventId = UUID.randomUUID())
        val mixed = mobileApi.uploadAndroidUsageEventDataV4(
            studyId, participant.participantId, device.randomDeviceId, ChronicleData(listOf(queued, lifecycle)),
        )
        Assert.assertEquals(setOf(queued.clientEventId), mixed.acceptedIds)
        Assert.assertEquals(setOf(lifecycle.clientEventId), mixed.discardedIds)
        Assert.assertEquals("the receipt and the table agree", setOf(usageId, queued.clientEventId),
            storedUsageEventIds(studyId, participant.participantId))

        // A requiredness-only edit preserves the participant's standing acceptance.
        studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, stopped.copy(
            modules = stopped.modules + (CollectionModuleId.USAGE_EVENTS to
                stopped.modules.getValue(CollectionModuleId.USAGE_EVENTS).copy(required = true)),
        ))
        Assert.assertEquals(setOf((data.single() as ChronicleUsageEvent).clientEventId),
            mobileApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId, device.randomDeviceId, data).acceptedIds)
    }

    /**
     * The server never compares a phone capture time with its own cutoff.
     * A skewed phone stamps a pre-pause row after the server's cutoff; the server stores both rows the phone sends,
     * because which rows fall inside a closed window is the phone's decision on its own clock.
     */
    @Test
    fun pausedAndCompletedUploadsStoreEveryQueuedRowWhateverItsPhoneStamp() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(Study(title = "Cutoff batch", contact = "test@openlattice.com"))
        prepareUsageFixture(chronicleClient, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)
        val device = TestDataFactory.androidDevice()
        val mobileApi = enrollUsageFixture(studyId, participant.participantId, device).mobileApi
        val template = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId, count = 1).single() as ChronicleUsageEvent
        val expected = linkedSetOf<java.time.Instant>()
        for ((index, status) in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED).withIndex()) {
            studyApi.updateParticipationStatusAsRead(studyId, participant.participantId, status)
            val cutoff = captureCutoff(studyId, participant.participantId)
            val before = template.copy(timestamp = cutoff.minusSeconds(index + 1L), clientEventId = UUID.randomUUID())
            val skewed = template.copy(timestamp = cutoff.plusSeconds(20L + index), clientEventId = UUID.randomUUID())
            val mixed = mobileApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId,
                device.randomDeviceId, ChronicleData(listOf(before, skewed)))
            Assert.assertEquals(setOf(before.clientEventId, skewed.clientEventId), mixed.acceptedIds)
            Assert.assertEquals("no row is judged by its phone stamp", emptySet<UUID>(), mixed.discardedIds)
            expected += before.timestamp.toInstant()
            expected += skewed.timestamp.toInstant()
            Assert.assertEquals(expected, storedUsageTimestamps(studyId, participant.participantId))
        }
    }

    private fun captureCutoff(studyId: UUID, participantId: String): java.time.OffsetDateTime = hds.connection.use { connection ->
        connection.prepareStatement("SELECT capture_cutoff FROM study_participants WHERE study_id = ? AND participant_id = ?").use {
            it.setObject(1, studyId); it.setString(2, participantId)
            it.executeQuery().use { rows -> Assert.assertTrue(rows.next()); rows.getObject(1, java.time.OffsetDateTime::class.java) }
        }
    }

    // ===================== Generalized DataCollection settings =====================

    private fun createCollectionStudy(): UUID = chronicleClient.studyApi.createStudy(
        Study(
            title = "collection settings test",
            contact = "test@openlattice.com",
            modules = mapOf(StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()),
        ),
    )

    /** New creates publish collection settings; remove them to model an actual legacy study. */
    private fun createSensorCollectionStudy(): UUID = createCollectionStudy().also { studyId ->
        hds.connection.use { connection ->
            connection.prepareStatement("UPDATE studies SET settings = settings - 'DataCollection' WHERE study_id = ?").use {
                it.setObject(1, studyId)
                Assert.assertEquals(1, it.executeUpdate())
            }
        }
    }





    @Test
    fun testDataCollectionSettingStorageRoundTrip() {
        // New setting storage round-trip (controller -> service -> Postgres -> back),
        // RLS-enforced: the owning client writes and reads its own study's setting.
        val studyApi = chronicleClient.studyApi
        val studyId = createCollectionStudy()
        val setting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS),
                CollectionModuleId.SENSOR_LIGHT to CollectionModuleSetting(
                    enabled = true,
                    sensorPolicy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.light)),
                ),
            ),
        )
        studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, setting)

        val read = studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue("DataCollection must read back as AndroidDataCollectionSetting", read is AndroidDataCollectionSetting)
        val dc = read as AndroidDataCollectionSetting
        Assert.assertTrue(dc.modules.getValue(CollectionModuleId.SENSOR_LIGHT).enabled)
        Assert.assertEquals(
            setOf(AndroidSensorType.light),
            dc.modules.getValue(CollectionModuleId.SENSOR_LIGHT).sensorPolicy?.sensors,
        )
    }

    @Test
    fun testPerModuleEnableDisableWithAuditTrailAndNoClobber() {
        // End-to-end proof of the per-module enable/disable feature exactly as the web app
        // drives it: a legacy AndroidSensor write followed by DataCollection writes (the two
        // settings PATCHes the UI now issues sequentially). Asserts (a) the per-module toggles
        // read back, (b) the AndroidSensor setting is NOT clobbered by the DataCollection
        // write, and (c) the settings-audit trail records the DataCollection changes with a
        // module-granular summary.
        val studyApi = chronicleClient.studyApi
        val studyId = createCollectionStudy()

        val sensorSetting = SurveySettings(appUsageThresholdInSeconds = 42)
        studyApi.updateStudySettings(studyId, StudySettingType.Survey, sensorSetting)

        // Establish a DataCollection setting, then toggle modules with a second write so the
        // audit summary exercises the module-granular (not first-time) path.
        studyApi.updateStudySettings(
            studyId,
            StudySettingType.DataCollection,
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
                    CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(enabled = false),
                ),
            ),
        )
        studyApi.updateStudySettings(
            studyId,
            StudySettingType.DataCollection,
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false),
                    CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(enabled = true),
                ),
            ),
        )

        // (a) The per-module toggles read back.
        val readDc = studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        Assert.assertFalse(readDc.modules.getValue(CollectionModuleId.BATTERY_TELEMETRY).enabled)
        Assert.assertTrue(readDc.modules.getValue(CollectionModuleId.SENSOR_ACCELEROMETER).enabled)

        // (b) The AndroidSensor setting survived the DataCollection writes (no clobber).
        Assert.assertEquals(sensorSetting, studyApi.getStudySetting(studyId, StudySettingType.Survey))

        // (c) The audit trail captured the AndroidSensor write and both DataCollection writes,
        // with a module-granular summary for the toggle.
        val audit = studyApi.getStudySettingsAudit(studyId, 50, 0)
        Assert.assertTrue(
            "Audit must include an AndroidSensor entry",
            audit.any { it.settingKey == StudySettingType.Survey },
        )
        val dataCollectionSummaries = audit
            .filter { it.settingKey == StudySettingType.DataCollection }
            .map { it.changeSummary }
        Assert.assertTrue(
            "Both DataCollection writes must be audited, got: $dataCollectionSummaries",
            dataCollectionSummaries.size >= 2,
        )
        Assert.assertTrue(
            "A DataCollection audit entry must carry a module-granular summary, got: $dataCollectionSummaries",
            dataCollectionSummaries.any {
                it.contains("disabled 'battery_telemetry'") && it.contains("enabled 'sensor_accelerometer'")
            },
        )
    }



    @Test
    fun testDataCollectionSettingMissingYieldsSafeDefault() {
        // Missing-setting fallback: no DataCollection and no AndroidSensor setting
        // yields a safe default — no sensor enabled, never silently enabled.
        val studyApi = chronicleClient.studyApi
        val studyId = createSensorCollectionStudy()

        val read = studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue(read is AndroidDataCollectionSetting)
        Assert.assertTrue(
            "missing setting must not enable any sensor module",
            SensorCollectionModules.sensorModuleIds.none { (read as AndroidDataCollectionSetting).modules.getValue(it).enabled },
        )
    }

    @Test
    fun testDataCollectionSettingReadIsMobilePublicLikeAndroidSensor() {
        // Authz contract: the generalized DataCollection
        // read is mobile-public — it routes through ensureValidStudy, exactly like
        // AndroidSensor, NOT through ensureReadAccess. The DTO carries no secrets, so
        // a non-owning client may read it just as a mobile client reads AndroidSensor.
        // The read is still study-scoped: getStudySettings keys strictly on studyId.
        val studyId = createCollectionStudy()
        val setting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS, enabled = false),
            ),
        )
        chronicleClient.studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, setting)

        // A different client reads the same study's DataCollection setting and gets
        // exactly the study-scoped value — mirrors getAndroidSensorSettings reachability.
        val readByOther = chronicleClient2.studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue(readByOther is AndroidDataCollectionSetting)
        Assert.assertEquals(
            setting.copy(modules = setting.effectiveModules(CollectionModuleId.activeModules), settingsVersion = 2),
            (readByOther as AndroidDataCollectionSetting).withoutConsentEpochs(),
        )
    }

    @Test
    fun testPublicSettingReadForUnknownStudyIsNotFound() {
        // Public setting reads must not reveal which study IDs exist, and must not hand a device
        // made-up defaults: an unknown study is 404 for every mobile-public setting type. The app
        // keeps its last settings and retries on this error; it never treats it as "all off".
        val unknownStudyId = UUID.randomUUID()
        listOf(
            StudySettingType.DataCollection,
        ).forEach { type ->
            val error = Assert.assertThrows(RhizomeRetrofitCallException::class.java) {
                chronicleClient.studyApi.getStudySetting(unknownStudyId, type)
            }
            Assert.assertEquals("$type", 404, error.code)
        }
    }

    @Test
    fun testUsageEventUploadWithUnknownDevice() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        prepareUsageFixture(chronicleClient, studyId)

        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)

        val sourceDevice = TestDataFactory.androidDevice()
        val mobileApi = enrollUsageFixture(studyId, participant.participantId, sourceDevice).mobileApi
        val chronicleData = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId)
        Assert.assertEquals(chronicleData.size, mobileApi.uploadAndroidUsageEventDataV4(
            studyId, participant.participantId, sourceDevice.randomDeviceId, chronicleData,
        ).acceptedIds.size)
        assertHttpStatus(HttpStatus.FORBIDDEN) {
            mobileApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId, UUID.randomUUID().toString(), chronicleData)
        }
    }
}
