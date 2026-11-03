package com.openlattice.chronicle.study

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public collection projection remains narrow even when the caller cannot read the study. */
class PublicCollectionSettingsProjectionTest : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMappers.getJsonMapper()
    private val callers = listOf(testUser1, testUser2, null)

    @Test fun ownerOtherResearcherAndAnonymousReceiveTheSameRevisionAndConsentProjection() {
        val study = createStudy()
        clientUser1.studyApi.updateStudySettings(study, StudySettingType.DataCollection, usageSetting(false))
        clientUser1.studyApi.updateStudySettings(study, StudySettingType.DataCollection, usageSetting(true))
        val revision = settingsRevision(study)
        for (caller in callers) {
            val response = get(study, caller)
            assertEquals(response.body(), 200, response.statusCode())
            assertEquals("\"$revision\"", response.headers().firstValue("ETag").orElse(null))
            assertPrivateFieldsAbsent(response.body())
            val setting = mapper.readValue(response.body(), AndroidDataCollectionSetting::class.java)
            assertEquals(3, setting.settingsVersion)
            assertTrue(setting.modules.getValue(CollectionModuleId.USAGE_EVENTS).enabled)
            assertEquals(3, setting.modules.getValue(CollectionModuleId.USAGE_EVENTS).consentEpoch)
        }
        // Reading the public projection does not lend the caller general study access.
        assertEquals(403, get(study, testUser2, "settings").statusCode())
    }

    @Test fun featureOffNarrowsStoredChoicesWithoutExposingOrChangingThem() {
        val study = createStudy(featureEnabled = false)
        clientUser1.studyApi.updateStudySettings(study, StudySettingType.DataCollection, usageSetting(true))
        val revision = settingsRevision(study)
        for (caller in callers) {
            val response = get(study, caller)
            assertEquals(response.body(), 200, response.statusCode())
            assertEquals("\"$revision\"", response.headers().firstValue("ETag").orElse(null))
            val setting = mapper.readValue(response.body(), AndroidDataCollectionSetting::class.java)
            assertFalse(setting.modules.getValue(CollectionModuleId.USAGE_EVENTS).enabled)
            assertTrue(setting.modules.getValue(CollectionModuleId.UPLOAD_TELEMETRY).enabled)
            assertPrivateFieldsAbsent(response.body())
        }
        hds.connection.use { connection ->
            connection.prepareStatement("SELECT settings FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, study)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val stored = mapper.readTree(rows.getString(1))
                    assertTrue(stored["DataCollection"]["modules"][CollectionModuleId.USAGE_EVENTS.id]["enabled"].asBoolean())
                }
            }
        }
    }

    @Test fun missingStoredCollectionSettingKeepsTheSafeLegacyFallbackAndRevision() {
        val study = createStudy()
        hds.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE studies SET settings = settings - 'DataCollection', settings_revision = settings_revision + 1
                WHERE study_id = ?
            """.trimIndent()).use { statement ->
                statement.setObject(1, study); assertEquals(1, statement.executeUpdate())
            }
        }
        val revision = settingsRevision(study)
        for (caller in callers) {
            val response = get(study, caller)
            assertEquals(response.body(), 200, response.statusCode())
            assertEquals("\"$revision\"", response.headers().firstValue("ETag").orElse(null))
            val setting = mapper.readValue(response.body(), AndroidDataCollectionSetting::class.java)
            assertEquals(AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION, setting.settingsVersion)
            assertTrue(SensorCollectionModules.sensorModuleIds.none { setting.modules.getValue(it).enabled })
            assertTrue(setting.modules.values.all { it.consentEpoch == AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION })
            assertPrivateFieldsAbsent(response.body())
        }
    }

    @Test fun unknownDeletedQuarantinedAndCompletedErasureStudiesRemainUnavailable() {
        val missing = UUID.randomUUID()
        val deleted = createStudy()
        hds.connection.use { connection ->
            connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, deleted); assertEquals(1, statement.executeUpdate())
            }
        }
        val hidden = listOf("QUARANTINED", "COMPLETED").map { status ->
            createStudy().also { study -> recordStudyErasure(study, status) }
        }
        for (study in listOf(missing, deleted) + hidden) {
            for (caller in callers) {
                val response = get(study, caller)
                assertEquals("Hidden study must have no public projection", 404, response.statusCode())
                assertFalse(response.headers().firstValue("ETag").isPresent)
                assertFalse(response.body().contains(PRIVATE_SENTINEL))
            }
        }
    }

    private fun createStudy(featureEnabled: Boolean = true): UUID = clientUser1.studyApi.createStudy(Study(
        title = PRIVATE_SENTINEL,
        description = PRIVATE_SENTINEL,
        contact = "private-projection@chronicle.test",
        phoneNumber = "PRIVATE-PHONE",
        modules = if (featureEnabled) mapOf(StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()) else emptyMap(),
    ))

    private fun usageSetting(enabled: Boolean): AndroidDataCollectionSetting = AndroidDataCollectionSetting(
        modules = mapOf(CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS, enabled)),
    )

    private fun get(study: UUID, caller: Principal?, suffix: String = "settings/type/DataCollection"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/study/$study/$suffix"))
        caller?.let { request.header("Authorization", "Bearer ${activeFixtureToken(it)}") }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun settingsRevision(study: UUID): Long = hds.connection.use { connection ->
        connection.prepareStatement("SELECT settings_revision FROM studies WHERE study_id = ?").use { statement ->
            statement.setObject(1, study)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
        }
    }

    private fun assertPrivateFieldsAbsent(body: String) {
        assertFalse(body.contains(PRIVATE_SENTINEL))
        val root = mapper.readTree(body)
        for (field in listOf("studyId", "title", "description", "contact", "phoneNumber", "storage", "organizationIds")) {
            assertFalse("Private study field $field must stay absent", root.has(field))
        }
    }

    private fun recordStudyErasure(study: UUID, status: String) {
        hds.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO data_deletion_operations
                    (operation_id, study_id, mode, status, requested_by, idempotency_key, registry_version)
                VALUES (?, ?, 'STUDY_ERASURE', ?, 'public-projection-test', ?, 1)
            """.trimIndent()).use { statement ->
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, study)
                statement.setString(3, status); statement.setObject(4, UUID.randomUUID()); statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val PRIVATE_SENTINEL = "PRIVATE_COLLECTION_PROJECTION_SENTINEL"
    }
}
