package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudySettingType
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.controllers.kAnyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.*

class StudySettingsAuditServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var service: StudySettingsAuditService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet

    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service = StudySettingsAuditService(storageResolver)
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    // --- recordSettingsChange tests ---

    @Test
    fun testRecordSettingsChangeExecutesInsert() {
        val studyId = UUID.randomUUID()

        service.recordSettingsChange(
            studyId = studyId,
            changedBy = "user-1",
            sourceIp = "192.168.1.100",
            settingKey = StudySettingType.Notifications,
            beforeValue = mapOf("enabled" to false),
            afterValue = mapOf("enabled" to true),
            changeSummary = "Enabled notifications"
        )

        verify(mockPs).setObject(kEq(2), kEq(studyId))
        verify(mockPs).setString(3, "user-1")
        verify(mockPs).setString(5, "192.168.1.100")
        verify(mockPs).setString(6, StudySettingType.Notifications.name)
        verify(mockPs).setString(kEq(9), kEq("Enabled notifications"))
        verify(mockPs).executeUpdate()
    }

    @Test
    fun testRecordSettingsChangeWithNullBeforeValue() {
        service.recordSettingsChange(
            studyId = UUID.randomUUID(),
            changedBy = "user-1",
            sourceIp = null,
            settingKey = StudySettingType.Sensor,
            beforeValue = null,
            afterValue = mapOf("sensors" to listOf("accelerometer")),
            changeSummary = "First time configuration"
        )

        verify(mockPs).setString(5, null) // sourceIp
        verify(mockPs).setString(7, null) // beforeValue
        verify(mockPs).executeUpdate()
    }

    @Test
    fun testRecordSettingsChangeWithNullSourceIp() {
        service.recordSettingsChange(
            studyId = UUID.randomUUID(),
            changedBy = "system",
            sourceIp = null,
            settingKey = StudySettingType.DataCollection,
            beforeValue = mapOf("key" to "old"),
            afterValue = mapOf("key" to "new"),
            changeSummary = "Updated data collection"
        )

        verify(mockPs).setString(5, null)
    }

    @Test
    fun testRecordSettingsChangeForAllSettingTypes() {
        for (settingType in StudySettingType.values()) {
            val ps = Mockito.mock(PreparedStatement::class.java)
            `when`(ps.executeUpdate()).thenReturn(1)
            `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(ps)

            service.recordSettingsChange(
                studyId = UUID.randomUUID(),
                changedBy = "user",
                sourceIp = "10.0.0.1",
                settingKey = settingType,
                beforeValue = null,
                afterValue = mapOf("configured" to true),
                changeSummary = "Set $settingType"
            )

            verify(ps).setString(6, settingType.name)
        }
    }

    // --- getAuditHistory tests ---

    @Test
    fun testGetAuditHistoryReturnsEmptyList() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.getAuditHistory(UUID.randomUUID(), 50, 0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun testGetAuditHistorySetsCorrectParameters() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        service.getAuditHistory(studyId, 25, 10)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setInt(2, 25)
        verify(mockPs).setInt(3, 10)
    }

    @Test
    fun testGetAuditHistoryWithZeroOffset() {
        `when`(mockRs.next()).thenReturn(false)

        service.getAuditHistory(UUID.randomUUID(), 100, 0)

        verify(mockPs).setInt(3, 0)
    }

    // --- generateChangeSummary tests ---

    @Test
    fun testGenerateChangeSummaryForFirstTimeConfig() {
        val result = service.generateChangeSummary(
            StudySettingType.Notifications,
            null,
            mapOf("enabled" to true)
        )

        assertEquals("Setting 'Notifications' was configured for the first time", result)
    }

    @Test
    fun testGenerateChangeSummaryNoEffectiveChange() {
        val value = mapOf("enabled" to true)
        val result = service.generateChangeSummary(
            StudySettingType.Sensor,
            value,
            value
        )

        assertEquals("Setting 'Sensor' was updated (no effective change)", result)
    }

    @Test
    fun testGenerateChangeSummaryWithAddedKey() {
        val before = mapOf("a" to 1)
        val after = mapOf("a" to 1, "b" to 2)

        val result = service.generateChangeSummary(StudySettingType.DataCollection, before, after)

        assertTrue(result.contains("added 'b'"))
    }

    @Test
    fun testGenerateChangeSummaryWithRemovedKey() {
        val before = mapOf("a" to 1, "b" to 2)
        val after = mapOf("a" to 1)

        val result = service.generateChangeSummary(StudySettingType.DataCollection, before, after)

        assertTrue(result.contains("removed 'b'"))
    }

    @Test
    fun testGenerateChangeSummaryWithChangedKey() {
        val before = mapOf("a" to 1)
        val after = mapOf("a" to 2)

        val result = service.generateChangeSummary(StudySettingType.Survey, before, after)

        assertTrue(result.contains("changed 'a'"))
    }

    @Test
    fun testGenerateChangeSummaryWithMultipleChanges() {
        val before = mapOf("a" to 1, "b" to 2)
        val after = mapOf("a" to 99, "c" to 3)

        val result = service.generateChangeSummary(StudySettingType.TimeUseDiary, before, after)

        assertTrue(result.contains("changed 'a'"))
        assertTrue(result.contains("removed 'b'"))
        assertTrue(result.contains("added 'c'"))
    }

    @Test
    fun testGenerateChangeSummaryNonMapValues() {
        val result = service.generateChangeSummary(
            StudySettingType.Notifications,
            "old value",
            "new value"
        )

        assertEquals("Setting 'Notifications' was updated", result)
    }

    @Test
    fun testGenerateChangeSummaryWithEmptyMaps() {
        val before = emptyMap<String, Any>()
        val after = emptyMap<String, Any>()

        val result = service.generateChangeSummary(StudySettingType.Pipeline, before, after)

        // Same maps, no changes
        assertEquals("Setting 'Pipeline' was updated (no effective change)", result)
    }

    // --- module-granular DataCollection summaries ---

    @Test
    fun testGenerateChangeSummaryModuleGranularEnableDisable() {
        val before = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false),
                CollectionModuleId.HARDWARE_SENSORS to CollectionModuleSetting(enabled = true),
            )
        )
        val after = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
                CollectionModuleId.HARDWARE_SENSORS to CollectionModuleSetting(enabled = false),
            )
        )

        val result = service.generateChangeSummary(StudySettingType.DataCollection, before, after)

        assertTrue(result, result.contains("enabled 'battery_telemetry'"))
        assertTrue(result, result.contains("disabled 'hardware_sensors'"))
    }

    @Test
    fun testGenerateChangeSummaryModuleGranularAddsModuleAndSkipsUnchanged() {
        val before = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
            )
        )
        val after = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
                CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = true),
            )
        )

        val result = service.generateChangeSummary(StudySettingType.DataCollection, before, after)

        assertTrue(result, result.contains("enabled 'questionnaire'"))
        // The unchanged module must not be reported.
        assertFalse(result.contains("battery_telemetry"))
    }

    @Test
    fun testGenerateChangeSummaryModuleGranularFirstTimeIsNotModuleDiff() {
        // before == null short-circuits to the first-time message, even for DataCollection.
        val after = AndroidDataCollectionSetting(
            modules = mapOf(CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true))
        )

        val result = service.generateChangeSummary(StudySettingType.DataCollection, null, after)

        assertEquals("Setting 'DataCollection' was configured for the first time", result)
    }
}
