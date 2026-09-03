package com.openlattice.chronicle.study

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.util.tests.TestSourceDevice
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.UUID

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
    fun testGetAllStudies() {
        val studyApi = chronicleClient.studyApi
        val studies = (0 until 5).map {
            Study(title = "This is a test study.", contact = "test@openlattice.com", version = it.toString())
        }.toSet()

        studies.forEach { it.id = studyApi.createStudy(it) }

        val all = studyApi.getAllStudies().map { it.id }.toSet()
        val expected = studies.map { it.id }.toSet()

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
                notificationsEnabled = false
            )
        )

        val updatedStudy1 = studyApi.getStudy(studyId)

        Assert.assertEquals(studyId, study.id)
        Assert.assertEquals(desc, updatedStudy1.description)
        Assert.assertFalse(updatedStudy1.notificationsEnabled)

        val updatedStudy2 = studyApi.updateStudy(
            studyId, StudyUpdate(
                notificationsEnabled = true
            ), true
        )!!

        Assert.assertEquals(studyId, study.id)
        Assert.assertEquals(desc, updatedStudy2.description)
        Assert.assertTrue(updatedStudy2.notificationsEnabled)

    }

    @Test
    fun studyEncryptionSettingIsMobileReadableAndFailsClosedDisabled() {
        // Encrypted payloads cannot yet be decrypted into the participant export contract.
        // The mobile-readable default remains disabled and the server rejects attempts to
        // select the unexportable data sink.
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(Study(title = "enc test", contact = "test@openlattice.com"))

        val absent = studyApi.getStudySetting(studyId, StudySettingType.Encryption) as StudyEncryptionSetting
        Assert.assertFalse("un-provisioned study must default to disabled", absent.enabled)

        Assert.assertThrows(RhizomeRetrofitCallException::class.java) {
            studyApi.updateStudySettings(
                studyId,
                StudySettingType.Encryption,
                StudyEncryptionSetting(
                    enabled = true,
                    keyId = "unsupported-key",
                    publicKeyPem = "unsupported-public-key",
                    mlkemPublicKey = "unsupported-mlkem-public-key",
                ),
            )
        }

        val read = studyApi.getStudySetting(studyId, StudySettingType.Encryption) as StudyEncryptionSetting
        Assert.assertFalse("rejected setting must not change the stored default", read.enabled)
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
        clientUser1.studyApi.registerParticipant(studyId, p)
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

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testStudyLimits() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        val partcipantCount = 25
        //Will be one more participant than allowed and should fail on the last
        repeat(partcipantCount + 1) {
            val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
            studyApi.registerParticipant(studyId, participant)
        }
    }

    @Test
    fun testLegacyUsageEventUpload() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        val chroniclev2Api = chronicleClient.chronicleApi
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)

        val sourceDevice = TestDataFactory.androidDevice()
        studyApi.enroll(studyId, participant.participantId, sourceDevice.deviceId, sourceDevice)

        val chronicleData = TestDataFactory.legacyChronicleUsageEvents()
        chroniclev2Api.upload(
            UUID.randomUUID(),
            studyId,
            participant.participantId,
            sourceDevice.deviceId,
            chronicleData
        )

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

        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)

        val sourceDevice = TestDataFactory.androidDevice()
        studyApi.enroll(studyId, participant.participantId, sourceDevice.deviceId, sourceDevice)

        val chronicleData = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId)
        studyApi.uploadAndroidUsageEventData(
            studyId,
            participant.participantId,
            sourceDevice.deviceId,
            chronicleData
        )
    }

    // ===================== Phase 9A: generalized DataCollection settings =====================

    @Test
    fun testGetAndroidSensorSettingsUnchangedForMissingSetting() {
        // Regression baseline (compatibility matrix row 1): a current mobile client
        // reading AndroidSensor on a study with no sensor setting gets NO_SENSORS,
        // exactly as before — the Phase 9 changes do not touch this path.
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        Assert.assertEquals(AndroidSensorSetting.NO_SENSORS, studyApi.getAndroidSensorSettings(studyId))
    }

    @Test
    fun testAndroidSensorSettingRoundTripStillWorks() {
        // Regression baseline: write + read of the legacy AndroidSensor setting is
        // unchanged. Mobile clients that only know AndroidSensor keep working.
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        val sensorSetting = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            samplingRateHz = 17,
        )
        studyApi.updateStudySettings(studyId, StudySettingType.AndroidSensor, sensorSetting)

        val viaTyped = studyApi.getStudySetting(studyId, StudySettingType.AndroidSensor)
        Assert.assertTrue(viaTyped is AndroidSensorSetting)
        Assert.assertEquals(sensorSetting, viaTyped)
        // The dedicated mobile-facing endpoint also returns the same value.
        Assert.assertEquals(sensorSetting, studyApi.getAndroidSensorSettings(studyId))
    }

    @Test
    fun testDataCollectionSettingStorageRoundTrip() {
        // New setting storage round-trip (controller -> service -> Postgres -> back),
        // RLS-enforced: the owning client writes and reads its own study's setting.
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        val setting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS),
                CollectionModuleId.HARDWARE_SENSORS to CollectionModuleSetting(
                    enabled = true,
                    sensorPolicy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.light)),
                ),
            ),
        )
        studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, setting)

        val read = studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue("DataCollection must read back as AndroidDataCollectionSetting", read is AndroidDataCollectionSetting)
        val dc = read as AndroidDataCollectionSetting
        Assert.assertTrue(dc.modules.getValue(CollectionModuleId.HARDWARE_SENSORS).enabled)
        Assert.assertEquals(
            setOf(AndroidSensorType.light),
            dc.modules.getValue(CollectionModuleId.HARDWARE_SENSORS).sensorPolicy?.sensors,
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
        val studyId = studyApi.createStudy(TestDataFactory.study())

        // First settings write: legacy AndroidSensor.
        val sensorSetting = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer),
            samplingRateHz = 11,
        )
        studyApi.updateStudySettings(studyId, StudySettingType.AndroidSensor, sensorSetting)

        // Establish a DataCollection setting, then toggle modules with a second write so the
        // audit summary exercises the module-granular (not first-time) path.
        studyApi.updateStudySettings(
            studyId,
            StudySettingType.DataCollection,
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
                    CollectionModuleId.HARDWARE_SENSORS to CollectionModuleSetting(enabled = false),
                ),
            ),
        )
        studyApi.updateStudySettings(
            studyId,
            StudySettingType.DataCollection,
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false),
                    CollectionModuleId.HARDWARE_SENSORS to CollectionModuleSetting(enabled = true),
                ),
            ),
        )

        // (a) The per-module toggles read back.
        val readDc = studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        Assert.assertFalse(readDc.modules.getValue(CollectionModuleId.BATTERY_TELEMETRY).enabled)
        Assert.assertTrue(readDc.modules.getValue(CollectionModuleId.HARDWARE_SENSORS).enabled)

        // (b) The AndroidSensor setting survived the DataCollection writes (no clobber).
        Assert.assertEquals(sensorSetting, studyApi.getStudySetting(studyId, StudySettingType.AndroidSensor))

        // (c) The audit trail captured the AndroidSensor write and both DataCollection writes,
        // with a module-granular summary for the toggle.
        val audit = studyApi.getStudySettingsAudit(studyId, 50, 0)
        Assert.assertTrue(
            "Audit must include an AndroidSensor entry",
            audit.any { it.settingKey == StudySettingType.AndroidSensor },
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
                it.contains("disabled 'battery_telemetry'") && it.contains("enabled 'hardware_sensors'")
            },
        )
    }

    @Test
    fun testDataCollectionSettingFallsBackToLegacyAndroidSensor() {
        // old -> new fallback (compatibility matrix row 4): a study with only a legacy
        // AndroidSensor setting and no DataCollection setting derives the named per-sensor module.
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())
        studyApi.updateStudySettings(
            studyId,
            StudySettingType.AndroidSensor,
            AndroidSensorSetting(sensors = setOf(AndroidSensorType.proximity)),
        )

        val read = studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue(read is AndroidDataCollectionSetting)
        val proximity = (read as AndroidDataCollectionSetting).modules.getValue(CollectionModuleId.SENSOR_PROXIMITY)
        Assert.assertTrue("legacy sensors must enable the per-sensor module via the bridge", proximity.enabled)
        Assert.assertEquals(setOf(AndroidSensorType.proximity), proximity.sensorPolicy?.sensors)
    }

    @Test
    fun testDataCollectionSettingMissingYieldsSafeDefault() {
        // Missing-setting fallback: no DataCollection and no AndroidSensor setting
        // yields a safe default — no sensor enabled, never silently enabled.
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())

        val read = studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue(read is AndroidDataCollectionSetting)
        Assert.assertTrue(
            "missing setting must not enable any sensor module",
            (read as AndroidDataCollectionSetting).modules.values.none { it.enabled },
        )
    }

    @Test
    fun testDataCollectionSettingReadIsMobilePublicLikeAndroidSensor() {
        // Authz contract (Phase 9A design decision): the generalized DataCollection
        // read is mobile-public — it routes through ensureValidStudy, exactly like
        // AndroidSensor, NOT through ensureReadAccess. The DTO carries no secrets, so
        // a non-owning client may read it just as a mobile client reads AndroidSensor.
        // The read is still study-scoped: getStudySettings keys strictly on studyId.
        val studyId = chronicleClient.studyApi.createStudy(TestDataFactory.study())
        val setting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS),
            ),
        )
        chronicleClient.studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, setting)

        // A different client reads the same study's DataCollection setting and gets
        // exactly the study-scoped value — mirrors getAndroidSensorSettings reachability.
        val readByOther = chronicleClient2.studyApi.getStudySetting(studyId, StudySettingType.DataCollection)
        Assert.assertTrue(readByOther is AndroidDataCollectionSetting)
        Assert.assertEquals(setting, readByOther)
    }

    @Test
    fun testDataCollectionSettingReadForUnknownStudyYieldsSafeDefault() {
        // An unknown study id resolves to no settings -> the safe default. This is
        // identical to the long-standing AndroidSensor behavior (NO_SENSORS) — the
        // generalized read does not throw and never enables a privacy-sensitive
        // module for an unknown study.
        val unknownStudyId = UUID.randomUUID()
        val read = chronicleClient.studyApi.getStudySetting(unknownStudyId, StudySettingType.DataCollection)
        Assert.assertTrue(read is AndroidDataCollectionSetting)
        Assert.assertTrue(
            (read as AndroidDataCollectionSetting).modules.values.none { it.enabled },
        )
        // Baseline parity: AndroidSensor for an unknown study likewise yields NO_SENSORS.
        Assert.assertEquals(
            AndroidSensorSetting.NO_SENSORS,
            chronicleClient.studyApi.getStudySetting(unknownStudyId, StudySettingType.AndroidSensor),
        )
    }

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testUsageEventUploadWithUnknownDevice() {
        val studyApi = chronicleClient.studyApi
        val studyId = studyApi.createStudy(TestDataFactory.study())

        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        studyApi.registerParticipant(studyId, participant)

        val sourceDevice = TestSourceDevice()
        studyApi.enroll(studyId, participant.participantId, sourceDevice.testDeviceLabel, sourceDevice)

        val chronicleData = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId)
        studyApi.uploadAndroidUsageEventData(
            studyId,
            participant.participantId,
            sourceDevice.testDeviceLabel,
            chronicleData
        )
    }
}
