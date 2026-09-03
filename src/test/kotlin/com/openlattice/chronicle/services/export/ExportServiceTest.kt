package com.openlattice.chronicle.services.export

import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportJobStatus
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.download.DataDownloadManager
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kAnyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExportServiceTest {

    @Test
    fun `study visible export failures contain only safe categories and correlation references`() {
        val failures = listOf(
            SQLException(
                "connection to db.internal.example:5432 failed; SELECT secret FROM participant_private",
            ) to "database",
            java.net.ConnectException("connect 10.42.0.19:8443 token=private") to "network",
            java.nio.file.FileSystemException("/srv/chronicle/private/study-123/export.csv") to "storage",
        )
        val forbidden = listOf(
            "db.internal.example",
            "5432",
            "participant_private",
            "10.42.0.19",
            "token=private",
            "/srv/chronicle/private",
            "SQLException",
            "ConnectException",
            "FileSystemException",
        )

        failures.forEach { (failure, category) ->
            val message = ExportService.withFailureCause("Export failed", failure)
            assertTrue(message.contains("category=$category"))
            assertTrue(
                message.matches(
                    Regex("Export failed \\(category=[a-z-]+; reference=[0-9a-f-]{36}\\)"),
                ),
            )
            forbidden.forEach { marker ->
                assertFalse("study-visible failure leaked $marker", message.contains(marker, ignoreCase = true))
            }
        }
    }

    private lateinit var storageResolver: StorageResolver
    private lateinit var downloadManager: DataDownloadManager
    private lateinit var idGenerationService: HazelcastIdGenerationService
    private lateinit var webhookService: WebhookService
    private lateinit var service: ExportService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet
    private lateinit var mockStatement: Statement
    private lateinit var mockStatementRs: ResultSet
    private lateinit var mockExecutor: ExecutorService
    private lateinit var mockLeaseExecutor: ScheduledExecutorService

    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        downloadManager = Mockito.mock(DataDownloadManager::class.java)
        idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        webhookService = Mockito.mock(WebhookService::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)
        mockStatement = Mockito.mock(Statement::class.java)
        mockStatementRs = Mockito.mock(ResultSet::class.java)
        mockExecutor = Mockito.mock(ExecutorService::class.java)
        mockLeaseExecutor = Mockito.mock(ScheduledExecutorService::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockConnection.createStatement()).thenReturn(mockStatement)
        `when`(mockStatement.executeQuery(kAnyString())).thenReturn(mockStatementRs)
        `when`(mockStatementRs.next()).thenReturn(true)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service = ExportService(
            storageResolver,
            downloadManager,
            idGenerationService,
            webhookService,
            mockExecutor,
            mockLeaseExecutor,
        )
    }

    @After
    fun tearDown() {
        service.shutdown()
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    @Test
    fun testHungManagedCapacityScanFailsClosedAndReleasesAdvisoryTransaction() {
        val isolatedStorageResolver = Mockito.mock(StorageResolver::class.java)
        val isolatedDataSource = Mockito.mock(HikariDataSource::class.java)
        val isolatedConnection = Mockito.mock(Connection::class.java)
        val isolatedStatement = Mockito.mock(Statement::class.java)
        val isolatedResultSet = Mockito.mock(ResultSet::class.java)
        `when`(isolatedStorageResolver.getPlatformStorage()).thenReturn(isolatedDataSource)
        `when`(isolatedDataSource.connection).thenReturn(isolatedConnection)
        `when`(isolatedConnection.createStatement()).thenReturn(isolatedStatement)
        `when`(isolatedStatement.executeQuery(kAnyString())).thenReturn(isolatedResultSet)
        `when`(isolatedResultSet.next()).thenReturn(true)
        val capacityReadStarted = CountDownLatch(1)
        val releaseCapacityRead = CountDownLatch(1)
        val blockingService = object : ExportService(
            isolatedStorageResolver,
            downloadManager,
            idGenerationService,
            webhookService,
            mockExecutor,
            mockLeaseExecutor,
        ) {
            override fun freshStorageCapacityForExportAdmission(): ExportStorageCapacity {
                capacityReadStarted.countDown()
                releaseCapacityRead.await()
                throw ExportCapacityUnavailableException()
            }
        }
        val caller = Executors.newSingleThreadExecutor()
        val claim = ExportService.ClaimedExport(
            exportId = UUID.randomUUID(),
            studyId = UUID.randomUUID(),
            requestJson = "{}",
            format = ExportFormat.CSV,
            createdBy = "capacity-order-test",
            attemptCount = 0,
            recoveryCount = 0,
            leaseToken = UUID.randomUUID(),
        )

        try {
            val result = caller.submit<Boolean> {
                assertThrows(ExportResourceLimitException::class.java) {
                    blockingService.reserveExportCapacity(claim)
                }
                true
            }
            assertTrue(capacityReadStarted.await(1, TimeUnit.SECONDS))
            verify(isolatedStatement).executeQuery(kAnyString())

            releaseCapacityRead.countDown()
            assertTrue(result.get(1, TimeUnit.SECONDS))
            verify(isolatedConnection).rollback()
            verify(isolatedConnection).close()
        } finally {
            releaseCapacityRead.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
            blockingService.shutdown()
        }
    }

    @Test
    fun testCapacitySnapshotOccursOnlyAfterAdvisoryLockEliminatingPublicationRace() {
        val advisoryLockAcquired = AtomicBoolean()
        `when`(mockStatement.executeQuery(kAnyString())).thenAnswer {
            advisoryLockAcquired.set(true)
            mockStatementRs
        }
        val orderedService = object : ExportService(
            storageResolver,
            downloadManager,
            idGenerationService,
            webhookService,
            mockExecutor,
            mockLeaseExecutor,
        ) {
            override fun freshStorageCapacityForExportAdmission(): ExportStorageCapacity {
                assertTrue("capacity must be sampled only while publication/release is fenced", advisoryLockAcquired.get())
                return ExportStorageCapacity(usableBytes = Long.MAX_VALUE, managedArtifactBytesAtSample = 0L)
            }
        }
        val claim = ExportService.ClaimedExport(
            exportId = UUID.randomUUID(),
            studyId = UUID.randomUUID(),
            requestJson = "{}",
            format = ExportFormat.CSV,
            createdBy = "capacity-generation-test",
            attemptCount = 0,
            recoveryCount = 0,
            leaseToken = UUID.randomUUID(),
        )
        `when`(mockRs.next()).thenReturn(true, true)
        `when`(mockRs.getLong(1)).thenReturn(0L)

        try {
            assertTrue(orderedService.reserveExportCapacity(claim))
            assertTrue(advisoryLockAcquired.get())
            verify(mockConnection).commit()
        } finally {
            orderedService.shutdown()
        }
    }

    @Test
    fun testCompletedExportCommitsStatusAndWebhookTogether() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV,
        )
        org.mockito.kotlin.whenever(
            downloadManager.getParticipantsUsageEventsData(
                org.mockito.kotlin.eq(studyId),
                org.mockito.kotlin.eq(emptySet<String>()),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            )
        ).thenReturn(emptyList())
        `when`(mockRs.next()).thenReturn(true, true, true, true, true)
        `when`(mockRs.getBoolean("revoked")).thenReturn(false)
        `when`(mockRs.getBoolean(1)).thenReturn(true)

        try {
            service.executeExport(exportId, studyId, request)

            val completionOrder = Mockito.inOrder(mockPs, webhookService, mockConnection)
            completionOrder.verify(mockPs).executeUpdate()
            completionOrder.verify(webhookService).enqueueEvent(
                org.mockito.kotlin.eq(mockConnection),
                org.mockito.kotlin.eq(studyId),
                org.mockito.kotlin.eq(WebhookEventType.EXPORT_COMPLETED),
                org.mockito.kotlin.check {
                    assertEquals(exportId.toString(), it["exportId"])
                    assertEquals(ExportFormat.CSV.name, it["format"])
                    assertEquals(listOf(ParticipantDataType.UsageEvents.name), it["dataTypes"])
                    assertEquals(0L, it["rowCount"])
                },
            )
            completionOrder.verify(mockConnection).commit()
            verify(downloadManager).getParticipantsUsageEventsData(
                studyId,
                emptySet(),
                OffsetDateTime.MIN,
                OffsetDateTime.MAX,
            )
        } finally {
            ExportFileWriter.deleteExportArtifactsForErasure(exportId, null)
        }
    }

    @Test
    fun testExactly366DayIosExportRequestsEveryKnownSensorType() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        val start = OffsetDateTime.parse("2025-01-01T00:00:00Z")
        val end = start.plusDays(366)
        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.IOSSensor),
            participantIds = setOf("participant-1"),
            format = ExportFormat.CSV,
            startDate = start,
            endDate = end,
        )
        org.mockito.kotlin.whenever(
            downloadManager.getParticipantsSensorData(
                org.mockito.kotlin.eq(studyId),
                org.mockito.kotlin.eq(setOf("participant-1")),
                org.mockito.kotlin.eq(SensorType.entries.toSet()),
                org.mockito.kotlin.eq(start),
                org.mockito.kotlin.eq(end),
            ),
        ).thenReturn(emptyList())
        `when`(mockRs.next()).thenReturn(true, true, true, true, true)
        `when`(mockRs.getBoolean("revoked")).thenReturn(false)
        `when`(mockRs.getBoolean(1)).thenReturn(true)

        try {
            service.executeExport(exportId, studyId, request)

            verify(downloadManager).getParticipantsSensorData(
                studyId,
                setOf("participant-1"),
                SensorType.entries.toSet(),
                start,
                end,
            )
            Files.list(ExportFileWriter.EXPORT_DIR).use { paths ->
                assertTrue(
                    paths.anyMatch { path ->
                        val name = path.fileName.toString()
                        name.startsWith("$exportId.csv.") && !name.endsWith(".part")
                    },
                )
            }
        } finally {
            ExportFileWriter.deleteExportArtifactsForErasure(exportId, null)
        }
    }

    @Test
    fun `all dedicated Play streams dispatch through the collection table exporter`() {
        val studyId = UUID.randomUUID()
        val collectionTypes = setOf(
            ParticipantDataType.SensorAvailability,
            ParticipantDataType.BatteryTelemetry,
            ParticipantDataType.InteractionEvents,
            ParticipantDataType.AudioActivity,
            ParticipantDataType.AudioContent,
            ParticipantDataType.NotificationActivity,
            ParticipantDataType.SleepEvents,
            ParticipantDataType.ActivityRecognition,
            ParticipantDataType.HealthMetrics,
            ParticipantDataType.ConnectivityState,
            ParticipantDataType.AppNetworkUsage,
            ParticipantDataType.DeviceSettings,
        )
        org.mockito.kotlin.whenever(
            downloadManager.getParticipantsCollectionData(
                org.mockito.kotlin.eq(studyId),
                org.mockito.kotlin.eq(setOf("participant-1")),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.eq(OffsetDateTime.MIN),
                org.mockito.kotlin.eq(OffsetDateTime.MAX),
            ),
        ).thenReturn(emptyList())

        collectionTypes.forEach { dataType ->
            service.loadDataForExport(
                studyId,
                setOf("participant-1"),
                dataType,
                OffsetDateTime.MIN,
                OffsetDateTime.MAX,
            )
            verify(downloadManager).getParticipantsCollectionData(
                studyId,
                setOf("participant-1"),
                dataType,
                OffsetDateTime.MIN,
                OffsetDateTime.MAX,
            )
        }
    }

    @Test
    fun testDateRangeBeyond366DaysFailsBeforeDataRetrieval() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        val start = OffsetDateTime.parse("2025-01-01T00:00:00Z")
        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV,
            startDate = start,
            endDate = start.plusDays(366).plusNanos(1),
        )

        service.executeExport(exportId, studyId, request)

        Mockito.verifyNoInteractions(downloadManager)
        Files.list(ExportFileWriter.EXPORT_DIR).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith("$exportId.") })
        }
    }

    // --- createAsyncExport tests ---

    @Test
    fun testCreateAsyncExportReturnsPendingJob() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        `when`(idGenerationService.getNextId()).thenReturn(exportId)

        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV
        )

        val result = service.createAsyncExport(studyId, "user-1", request)

        assertEquals(exportId, result.exportId)
        assertEquals(studyId, result.studyId)
        assertEquals(ExportJobStatus.PENDING, result.status)
        assertEquals(ExportFormat.CSV, result.format)
    }

    @Test
    fun testDispatcherRejectionLeavesDurableJobPending() {
        val exportId = UUID.randomUUID()
        `when`(idGenerationService.getNextId()).thenReturn(exportId)
        Mockito.doThrow(RejectedExecutionException("full"))
            .`when`(mockExecutor)
            .execute(Mockito.any(Runnable::class.java))

        val result = service.createAsyncExport(
            UUID.randomUUID(),
            "user-1",
            ExportRequest(
                dataTypes = setOf(ParticipantDataType.UsageEvents),
                format = ExportFormat.CSV,
            ),
        )

        assertEquals(ExportJobStatus.PENDING, result.status)
        verify(mockPs, Mockito.times(1)).executeUpdate()
    }

    @Test
    fun testCreateAsyncExportWithJsonFormat() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.Preprocessed),
            format = ExportFormat.JSON
        )

        val result = service.createAsyncExport(UUID.randomUUID(), "user-1", request)

        assertEquals(ExportFormat.JSON, result.format)
    }

    @Test
    fun testCreateAsyncExportWithExcelFormat() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.AppUsageSurvey),
            format = ExportFormat.EXCEL
        )

        val result = service.createAsyncExport(UUID.randomUUID(), "user-1", request)

        assertEquals(ExportFormat.EXCEL, result.format)
    }

    @Test
    fun testCreateAsyncExportWithMultipleDataTypes() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ExportRequest(
            dataTypes = setOf(
                ParticipantDataType.UsageEvents,
                ParticipantDataType.Preprocessed,
                ParticipantDataType.AppUsageSurvey
            ),
            format = ExportFormat.CSV
        )

        val result = service.createAsyncExport(UUID.randomUUID(), "user-1", request)

        assertEquals(ExportJobStatus.PENDING, result.status)
    }

    @Test
    fun testCreateAsyncExportWithDateRange() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV,
            startDate = now.minusDays(30),
            endDate = now
        )

        val result = service.createAsyncExport(UUID.randomUUID(), "user-1", request)

        assertNotNull(result)
        assertEquals(ExportJobStatus.PENDING, result.status)
    }

    @Test
    fun testCreateAsyncExportWithParticipantIds() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            participantIds = setOf("p1", "p2", "p3"),
            format = ExportFormat.CSV
        )

        val result = service.createAsyncExport(UUID.randomUUID(), "user-1", request)

        assertNotNull(result.exportId)
    }

    // --- getExportStatus tests ---

    @Test
    fun testGetExportStatusReturnsJobInfo() {
        val exportId = UUID.randomUUID()
        val studyId = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getObject("export_id", UUID::class.java)).thenReturn(exportId)
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(studyId)
        `when`(mockRs.getString("status")).thenReturn(ExportJobStatus.COMPLETED.name)
        `when`(mockRs.getString("format")).thenReturn(ExportFormat.CSV.name)
        `when`(mockRs.getObject("created_at", OffsetDateTime::class.java)).thenReturn(now)
        `when`(mockRs.getObject("completed_at", OffsetDateTime::class.java)).thenReturn(now)
        `when`(mockRs.getString("download_token")).thenReturn("abc123")
        `when`(mockRs.getLong("row_count")).thenReturn(100L)
        `when`(mockRs.getString("error_message")).thenReturn(null)
        `when`(mockRs.getString("file_path")).thenReturn("/tmp/export.csv")

        val result = service.getExportStatus(studyId, exportId)

        assertEquals(exportId, result.exportId)
        assertEquals(ExportJobStatus.COMPLETED, result.status)
        assertEquals(100L, result.rowCount)
        assertNull(result.downloadToken)
    }

    @Test(expected = IllegalStateException::class)
    fun testGetExportStatusThrowsWhenNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        service.getExportStatus(UUID.randomUUID(), UUID.randomUUID())
    }

    // --- getExportStatusForDownload tests ---

    @Test(expected = IllegalStateException::class)
    fun testGetExportStatusForDownloadThrowsWhenNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        service.getExportStatusForDownload(UUID.randomUUID(), UUID.randomUUID(), "user-1")
    }

    @Test(expected = IllegalStateException::class)
    fun testGetExportStatusForDownloadThrowsWhenWrongUser() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString("created_by")).thenReturn("other-user")

        service.getExportStatusForDownload(UUID.randomUUID(), UUID.randomUUID(), "requesting-user")
    }

    // --- listExports tests ---

    @Test
    fun testListExportsReturnsEmptyList() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.listExports(UUID.randomUUID())

        assertTrue(result.isEmpty())
    }

    @Test
    fun testListExportsSetsStudyIdParameter() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        service.listExports(studyId)

        verify(mockPs).setObject(1, studyId)
    }

    // --- streamExportFile tests ---

    @Test(expected = IllegalStateException::class)
    fun testStreamExportFileThrowsWhenJobNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        val os = java.io.ByteArrayOutputStream()
        service.streamExportFile(UUID.randomUUID(), UUID.randomUUID(), "user-1", os)
    }
}
