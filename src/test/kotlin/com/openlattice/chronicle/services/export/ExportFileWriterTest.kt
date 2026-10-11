package com.openlattice.chronicle.services.export

import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.StatementHolder
import com.geekbeast.postgres.streams.StatementHolderSupplier
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.openlattice.chronicle.converters.PostgresDownloadWrapper
import com.openlattice.chronicle.converters.downloadCsvMapper
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.study.ParticipantDataType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.mockito.Mockito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.Duration
import java.util.function.Supplier
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExportFileWriterTest {

    @Test
    fun csvDatabaseDatasetsFitOneConnectionAndCloseBeforeOpeningTheNext() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
            connectionTimeout = TimeUnit.SECONDS.toMillis(1)
            connectionInitSql = "SET idle_in_transaction_session_timeout = '60s'"
        }).use { storage ->
            val columns = listOf("value")
            val datasets = listOf("alpha", "zeta").associateWith {
                PostgresDownloadWrapper(BasePostgresIterable<Map<String, Any>>(
                    StatementHolderSupplier(storage, "SELECT generate_series(1, 2001) AS value", fetchSize = 1000),
                ) { row -> mapOf("value" to row.getInt("value")) }).withColumnAdvice(columns)
            }
            val result = ExportFileWriter.writeMultiDataTypeExport(datasets, ExportFormat.CSV, UUID.randomUUID())
            try {
                assertEquals(4002L, result.rowCount)
                assertEquals(0, storage.hikariPoolMXBean.activeConnections)
                val csv = Files.readString(result.path)
                assertTrue(csv.contains("alpha,2001"))
                assertTrue(csv.contains("zeta,2001"))
            } finally {
                Files.deleteIfExists(result.path)
            }
        }
    }

    @Test
    fun csvUsageRowsWithDifferentOptionalFieldsKeepDeclaredSchema() {
        val columns = PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.map { it.name }
        val participant = mapOf<String, Any>(PostgresEventColumns.PARTICIPANT_ID.name to "participant-1")
        val activity = mapOf<String, Any>(PostgresEventColumns.ACTIVITY_CLASS.name to "MainActivity")
        val user = mapOf<String, Any>(PostgresEventColumns.USERNAME.name to "user-1")
        val rows = listOf(participant, participant + activity, participant + user, participant + activity + user)
        val dataType = ParticipantDataType.UsageEvents.name
        val result = ExportFileWriter.writeMultiDataTypeExport(
            mapOf(dataType to postgresRows(columns, rows)), ExportFormat.CSV, UUID.randomUUID(),
        )
        try {
            val csv = Files.readString(result.path)
            assertEquals(
                (listOf(ExportFileWriter.CSV_DATA_TYPE_COLUMN) + columns.sorted()).joinToString(","),
                csv.lineSequence().first(),
            )
            val exported = downloadCsvMapper().readerFor(Map::class.java)
                .with(CsvSchema.emptySchema().withHeader())
                .readValues<Map<String, String>>(csv).use { it.readAll() }
            assertEquals(rows.size.toLong(), result.rowCount)
            assertEquals(rows.size, exported.size)
            exported.forEachIndexed { index, row ->
                assertEquals(dataType, row[ExportFileWriter.CSV_DATA_TYPE_COLUMN])
                columns.forEach { column ->
                    assertEquals("row $index, column $column", rows[index][column]?.toString().orEmpty(), row[column])
                }
            }
        } finally {
            Files.deleteIfExists(result.path)
        }
    }

    @Test
    fun excelSpillBudgetAbortsDuringGenerationAndCleansPartials() {
        val id = UUID.randomUUID()
        var generated = 0
        val random = java.util.Random(42)
        val data = Iterable {
            (0 until 2000).asSequence().map {
                generated++
                val bytes = ByteArray(8192).also(random::nextBytes)
                mapOf<String, Any>("title" to java.util.Base64.getEncoder().encodeToString(bytes))
            }.iterator()
        }
        assertThrows(ExportResourceLimitException::class.java) {
            ExportFileWriter.writeMultiDataTypeExport(mapOf("AudioContent" to data), ExportFormat.EXCEL, id,
                limits = ExportLimits(10_000, 16_384, Duration.ofMinutes(1)))
        }
        assertTrue("spill budget must stop row generation, generated=$generated", generated < 2000)
        Files.list(ExportFileWriter.POI_TEMP_DIR).use { assertEquals(0L, it.count()) }
        Files.list(ExportFileWriter.EXPORT_DIR).use {
            assertEquals(0L, it.filter { path -> path.fileName.toString().startsWith(id.toString()) }.count())
        }
    }

    @Test
    fun testExportDirectoryConfigurationIsRequired() {
        assertThrows(IllegalStateException::class.java) {
            ExportFileWriter.resolveConfiguredExportDirectory(null, " ")
        }
        assertEquals(
            "/durable/exports",
            ExportFileWriter.resolveConfiguredExportDirectory("/durable/exports", "/ignored"),
        )
    }

    @Test
    fun testCsvEscapesSpreadsheetFormulaCells() {
        val file = ExportFileWriter.writeExportFile(
            listOf(
                linkedMapOf(
                    "formula" to "=2+2",
                    "spaced" to "  @malicious",
                    "safe" to "participant-001",
                    "number" to -12
                )
            ),
            ExportFormat.CSV,
            UUID.randomUUID(),
            "usage"
        )

        try {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(ExportFileWriter.EXPORT_DIR),
            )
            val csv = Files.readString(file)
            assertTrue(csv.contains("'=2+2"))
            assertTrue(csv.contains("'  @malicious"))
            assertTrue(csv.contains("participant-001"))
            assertTrue(csv.contains("-12"))
            assertNoWorkingArtifacts(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun testJsonDoesNotApplySpreadsheetEscaping() {
        val file = ExportFileWriter.writeExportFile(
            listOf(linkedMapOf("formula" to "=2+2")),
            ExportFormat.JSON,
            UUID.randomUUID(),
            "usage"
        )

        try {
            val json = Files.readString(file)
            assertTrue(json.contains("\"=2+2\""))
            assertFalse(json.contains("\"'=2+2\""))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun testExcelEscapesSpreadsheetFormulaCells() {
        val file = ExportFileWriter.writeExportFile(
            listOf(linkedMapOf("formula" to "=2+2", "safe" to "participant-001")),
            ExportFormat.EXCEL,
            UUID.randomUUID(),
            "usage"
        )

        try {
            WorkbookFactory.create(file.toFile()).use { workbook ->
                val row = workbook.getSheetAt(0).getRow(1)
                assertEquals("'=2+2", row.getCell(0).stringCellValue)
                assertEquals("participant-001", row.getCell(1).stringCellValue)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun testVerifiedErasureDeletesFinalAndUnrecordedPartialArtifacts() {
        val exportId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val finalPath = ExportFileWriter.EXPORT_DIR.resolve("$exportId.csv")
        val workingPath = finalPath.resolveSibling("${finalPath.fileName}.$attemptId.part")
        Files.writeString(finalPath, "final")
        Files.writeString(workingPath, "partial")

        ExportFileWriter.deleteExportArtifactsForErasure(exportId, null)
        ExportFileWriter.deleteExportArtifactsForErasure(exportId, null)

        assertFalse(Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workingPath, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun testVerifiedErasureRejectsARecordedPathOutsideManagedStorage() {
        val outside = Files.createTempFile("chronicle-export-outside-", ".csv")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ExportFileWriter.deleteExportArtifactsForErasure(UUID.randomUUID(), outside.toString())
            }
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun testDownloadRejectsSymlinkEvenWhenItIsInsideManagedStorage() {
        val outside = Files.createTempFile("chronicle-export-symlink-target-", ".csv")
        val managedLink = ExportFileWriter.EXPORT_DIR.resolve("${UUID.randomUUID()}.csv")
        Files.writeString(outside, "sensitive")
        Files.createSymbolicLink(managedLink, outside)
        try {
            assertThrows(IllegalStateException::class.java) {
                ExportFileWriter.copyManagedExportFile(managedLink.toString(), ByteArrayOutputStream())
            }
        } finally {
            Files.deleteIfExists(managedLink)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun testMultiTypeCsvUsesStableTypedUnionSchemaAndClosesOneShotIterators() {
        val firstAlpha = OneShotRows(
            linkedMapOf("shared" to "alpha-shared", "alpha" to "alpha-only"),
        )
        val firstZeta = OneShotRows(
            linkedMapOf("zeta" to "zeta-only", "shared" to "zeta-shared"),
        )
        val secondAlpha = OneShotRows(
            linkedMapOf("alpha" to "alpha-only", "shared" to "alpha-shared"),
        )
        val secondZeta = OneShotRows(
            linkedMapOf("shared" to "zeta-shared", "zeta" to "zeta-only"),
        )
        val firstAttempt = UUID.randomUUID()
        val secondAttempt = UUID.randomUUID()
        val first = ExportFileWriter.writeMultiDataTypeExport(
            linkedMapOf("zeta" to firstZeta, "alpha" to firstAlpha),
            ExportFormat.CSV,
            UUID.randomUUID(),
            firstAttempt,
        )
        val second = ExportFileWriter.writeMultiDataTypeExport(
            linkedMapOf("alpha" to secondAlpha, "zeta" to secondZeta),
            ExportFormat.CSV,
            UUID.randomUUID(),
            secondAttempt,
        )

        try {
            val firstCsv = Files.readString(first.path)
            val secondCsv = Files.readString(second.path)
            assertEquals(firstCsv, secondCsv)
            assertEquals(
                listOf(
                    "data_type,alpha,shared,zeta",
                    "alpha,alpha-only,alpha-shared,",
                    "zeta,,zeta-shared,zeta-only",
                ),
                firstCsv.lineSequence().filter(String::isNotEmpty).toList(),
            )
            assertEquals(2L, first.rowCount)
            assertEquals(2L, second.rowCount)
            listOf(firstAlpha, firstZeta, secondAlpha, secondZeta).forEach { rows ->
                assertEquals(1, rows.iteratorRequests)
                assertTrue(rows.closed)
            }
            assertFalse(
                Files.exists(
                    first.path.resolveSibling("${first.path.fileName}.part"),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
            assertFalse(
                Files.exists(
                    second.path.resolveSibling("${second.path.fileName}.part"),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
        } finally {
            Files.deleteIfExists(first.path)
            Files.deleteIfExists(second.path)
        }
    }

    @Test
    fun testMultiTypeCsvRejectsSchemaDriftClosesIteratorAndRemovesAttemptFile() {
        val rows = OneShotRows(
            linkedMapOf("stable" to "first"),
            linkedMapOf("different" to "second"),
        )
        val exportId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val finalPath = ExportFileWriter.EXPORT_DIR.resolve("$exportId.csv.$attemptId")
        val workingPath = finalPath.resolveSibling("${finalPath.fileName}.part")

        assertThrows(IllegalArgumentException::class.java) {
            ExportFileWriter.writeMultiDataTypeExport(
                mapOf("usage" to rows),
                ExportFormat.CSV,
                exportId,
                attemptId,
            )
        }

        assertEquals(1, rows.iteratorRequests)
        assertTrue(rows.closed)
        assertFalse(Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workingPath, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun testAttemptScopedPublicationPreventsStaleCleanupFromDeletingWinner() {
        val exportId = UUID.randomUUID()
        val staleAttempt = UUID.randomUUID()
        val winningAttempt = UUID.randomUUID()
        val stale = ExportFileWriter.writeMultiDataTypeExport(
            mapOf("usage" to listOf(mapOf("value" to "stale"))),
            ExportFormat.CSV,
            exportId,
            staleAttempt,
        )
        val winner = ExportFileWriter.writeMultiDataTypeExport(
            mapOf("usage" to listOf(mapOf("value" to "winner"))),
            ExportFormat.CSV,
            exportId,
            winningAttempt,
        )

        try {
            assertNotEquals(stale.path, winner.path)
            ExportFileWriter.deleteExportAttemptArtifacts(
                exportId,
                ExportFormat.CSV,
                staleAttempt,
                includeCanonical = false,
            )
            assertFalse(Files.exists(stale.path, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(winner.path, LinkOption.NOFOLLOW_LINKS))

            ExportFileWriter.deleteExportArtifactsForErasure(exportId, winner.path.toString())
            assertFalse(Files.exists(winner.path, LinkOption.NOFOLLOW_LINKS))
        } finally {
            ExportFileWriter.deleteExportArtifactsForErasure(exportId, null)
        }
    }

    @Test
    fun testStudyStorageLockFencesConcurrentFileOperations() {
        val studyId = UUID.randomUUID()
        val firstLock = ExportFileWriter.acquireStudyExportLock(studyId)
        val contenderStarted = CountDownLatch(1)
        val contenderAcquired = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.execute {
                contenderStarted.countDown()
                ExportFileWriter.acquireStudyExportLock(studyId).use {
                    contenderAcquired.countDown()
                }
            }
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS))
            assertFalse(contenderAcquired.await(200, TimeUnit.MILLISECONDS))
            firstLock.close()
            assertTrue(contenderAcquired.await(5, TimeUnit.SECONDS))
        } finally {
            runCatching { firstLock.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun testMultiTypeJsonStreamsOneShotIterableAndClosesItsIterator() {
        var iteratorRequests = 0
        var closed = false
        val rows = object : Iterable<Map<String, Any>> {
            override fun iterator(): Iterator<Map<String, Any>> {
                check(iteratorRequests++ == 0) { "streaming export requested the iterator twice" }
                val delegate = listOf(
                    mapOf("row" to 1),
                    mapOf("row" to 2),
                ).iterator()
                return object : Iterator<Map<String, Any>> by delegate, AutoCloseable {
                    override fun close() {
                        closed = true
                    }
                }
            }
        }
        val exportId = UUID.randomUUID()
        val result = ExportFileWriter.writeMultiDataTypeExport(
            mapOf("usage" to rows),
            ExportFormat.JSON,
            exportId,
        )
        try {
            assertEquals(2L, result.rowCount)
            assertTrue(closed)
            assertTrue(Files.readString(result.path).contains("\"row\""))
        } finally {
            Files.deleteIfExists(result.path)
        }
    }

    @Test
    fun testRowLimitClosesIteratorAndRemovesAttemptFile() {
        var closed = false
        val rows = object : Iterable<Map<String, Any>> {
            override fun iterator(): Iterator<Map<String, Any>> {
                val delegate = listOf(
                    mapOf("row" to 1),
                    mapOf("row" to 2),
                ).iterator()
                return object : Iterator<Map<String, Any>> by delegate, AutoCloseable {
                    override fun close() {
                        closed = true
                    }
                }
            }
        }
        val exportId = UUID.randomUUID()
        assertThrows(ExportResourceLimitException::class.java) {
            ExportFileWriter.writeMultiDataTypeExport(
                mapOf("usage" to rows),
                ExportFormat.JSON,
                exportId,
                UUID.randomUUID(),
                ExportLimits(
                    maxRows = 1,
                    maxBytes = 1_000_000,
                    maxRuntime = Duration.ofMinutes(1),
                ),
            )
        }

        assertTrue(closed)
        assertFalse(Files.exists(ExportFileWriter.EXPORT_DIR.resolve("$exportId.json")))
        Files.list(ExportFileWriter.EXPORT_DIR).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith("$exportId.json.") })
        }
    }

    @Test
    fun testZeroRowDownloadKeepsAdvisedHeadersAndStreams() {
        // A study whose enabled modules produced no rows yet must still download:
        // header-only CSV/Excel, empty JSON, no 500.
        val advised = listOf("participant_id", "timestamp", "value")
        val exportId = UUID.randomUUID()
        val results = ExportFormat.entries.associateWith { format ->
            ExportFileWriter.writeMultiDataTypeExport(
                mapOf("BatteryTelemetry" to postgresRows(advised)),
                format,
                exportId,
            )
        }
        try {
            results.values.forEach { result ->
                assertEquals(0L, result.rowCount)
                val managed = ExportFileWriter.verifyManagedExportFile(result.path.toString())
                val copied = ByteArrayOutputStream()
                ExportFileWriter.copyManagedExportFile(managed.toString(), copied)
                assertEquals(Files.size(managed), copied.size().toLong())
            }
            assertEquals(
                "data_type,participant_id,timestamp,value\n",
                Files.readString(results.getValue(ExportFormat.CSV).path),
            )
            WorkbookFactory.create(results.getValue(ExportFormat.EXCEL).path.toFile()).use { workbook ->
                val sheet = workbook.getSheet("BatteryTelemetry")
                assertEquals(advised, (0 until 3).map { sheet.getRow(0).getCell(it).stringCellValue })
                assertEquals(0, sheet.lastRowNum)
            }
            assertTrue(Files.readString(results.getValue(ExportFormat.JSON).path).isNotBlank())
        } finally {
            results.values.forEach { Files.deleteIfExists(it.path) }
        }
    }

    @Test
    fun `retained diagnostic and quality alert exports keep schemas in every format including empty results`() {
        val diagnosticColumns = listOf(
            "study_id", "participant_id", "device_id", "event_id", "diagnostic_day", "module_family",
            "issue_code", "occurrence_count", "first_occurred_at", "last_occurred_at", "http_status",
            "error_type", "uploaded_at",
        )
        val alertColumns = listOf("study_id", "participant_id", "alert_id", "alert_type", "score", "created_at")
        val diagnostic = linkedMapOf<String, Any>(
            "study_id" to UUID.randomUUID().toString(),
            "participant_id" to "participant-1",
            "device_id" to UUID.randomUUID().toString(),
            "event_id" to UUID.randomUUID().toString(),
            "diagnostic_day" to java.time.LocalDate.parse("2026-09-25"),
            "module_family" to "LOCAL_STORE",
            "issue_code" to "LOCAL_WRITE_FAILED",
            "occurrence_count" to 3L,
            "first_occurred_at" to java.time.OffsetDateTime.parse("2026-09-25T10:00:00Z"),
            "last_occurred_at" to java.time.OffsetDateTime.parse("2026-09-25T10:05:00Z"),
            "http_status" to "",
            "error_type" to "IOException",
            "uploaded_at" to java.time.OffsetDateTime.parse("2026-09-25T10:06:00Z"),
        )
        val alert = linkedMapOf<String, Any>(
            "study_id" to diagnostic.getValue("study_id"),
            "participant_id" to diagnostic.getValue("participant_id"),
            "alert_id" to UUID.randomUUID().toString(),
            "alert_type" to "LOW_QUALITY",
            "score" to 12.5,
            "created_at" to java.time.OffsetDateTime.parse("2026-09-25T10:07:00Z"),
        )
        val populated = linkedMapOf<String, Iterable<Map<String, Any>>>(
            "UploadDiagnostics" to listOf(diagnostic),
            "DataQualityAlerts" to listOf(alert),
        )
        val populatedFiles = ExportFormat.entries.associateWith { format ->
            ExportFileWriter.writeMultiDataTypeExport(populated, format, UUID.randomUUID())
        }
        val empty = linkedMapOf<String, Iterable<Map<String, Any>>>(
            "UploadDiagnostics" to postgresRows(diagnosticColumns),
            "DataQualityAlerts" to postgresRows(alertColumns),
        )
        val emptyFiles = ExportFormat.entries.associateWith { format ->
            ExportFileWriter.writeMultiDataTypeExport(empty, format, UUID.randomUUID())
        }

        try {
            val expectedCsvColumns = listOf("data_type") + (diagnosticColumns + alertColumns).toSortedSet()
            assertEquals(expectedCsvColumns.joinToString(",") + "\n", Files.readString(emptyFiles.getValue(ExportFormat.CSV).path))
            assertTrue(Files.readString(populatedFiles.getValue(ExportFormat.CSV).path).contains("LOCAL_WRITE_FAILED"))
            assertFalse(Files.readString(populatedFiles.getValue(ExportFormat.CSV).path).contains("error_message"))

            val populatedJson = ObjectMapper().readTree(Files.readString(populatedFiles.getValue(ExportFormat.JSON).path))
            assertEquals(listOf("LOCAL_WRITE_FAILED", "LOW_QUALITY"),
                populatedJson.map { it.path("issue_code").asText(it.path("alert_type").asText()) })
            assertFalse(Files.readString(populatedFiles.getValue(ExportFormat.JSON).path).contains("message"))
            assertEquals(0, ObjectMapper().readTree(Files.readString(emptyFiles.getValue(ExportFormat.JSON).path)).size())

            for (format in ExportFormat.entries) {
                assertEquals(2L, populatedFiles.getValue(format).rowCount)
                assertEquals(0L, emptyFiles.getValue(format).rowCount)
                assertTrue(Files.size(emptyFiles.getValue(format).path) > 0)
            }
            listOf(populatedFiles, emptyFiles).forEach { files ->
                WorkbookFactory.create(files.getValue(ExportFormat.EXCEL).path.toFile()).use { workbook ->
                    assertEquals(listOf("UploadDiagnostics", "DataQualityAlerts"),
                        (0 until workbook.numberOfSheets).map(workbook::getSheetName))
                    assertEquals(diagnosticColumns,
                        (0 until diagnosticColumns.size).map { workbook.getSheet("UploadDiagnostics").getRow(0).getCell(it).stringCellValue })
                    assertEquals(alertColumns,
                        (0 until alertColumns.size).map { workbook.getSheet("DataQualityAlerts").getRow(0).getCell(it).stringCellValue })
                }
            }
        } finally {
            populatedFiles.values.forEach { Files.deleteIfExists(it.path) }
            emptyFiles.values.forEach { Files.deleteIfExists(it.path) }
        }
    }

    private fun postgresRows(
        columns: List<String>,
        rows: List<Map<String, Any>> = emptyList(),
    ): PostgresDownloadWrapper {
        val rowIterator = rows.iterator()
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(resultSet.next()).thenAnswer { rowIterator.hasNext() }
        val iterable = BasePostgresIterable(
            Supplier {
                StatementHolder(
                    Mockito.mock(Connection::class.java),
                    Mockito.mock(Statement::class.java),
                    resultSet,
                )
            },
        ) { rowIterator.next() }
        return PostgresDownloadWrapper(iterable).withColumnAdvice(columns)
    }

    private fun assertNoWorkingArtifacts(finalPath: java.nio.file.Path) {
        Files.list(finalPath.parent).use { paths ->
            assertFalse(
                paths.anyMatch { path ->
                    val name = path.fileName.toString()
                    name.startsWith("${finalPath.fileName}.") && name.endsWith(".part")
                },
            )
        }
    }

    private class OneShotRows(
        vararg rows: Map<String, Any>,
    ) : Iterable<Map<String, Any>> {
        private val rows = rows.toList()
        var iteratorRequests: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun iterator(): Iterator<Map<String, Any>> {
            check(iteratorRequests++ == 0) { "streaming export requested the iterator twice" }
            val delegate = rows.iterator()
            return object : Iterator<Map<String, Any>> by delegate, AutoCloseable {
                override fun close() {
                    closed = true
                }
            }
        }
    }

    // Regression V-24/V-31: SXSSF spilled uncompressed sheet XML into java.io.tmpdir (the 256 MiB
    // scratch tmpfs), so a large Excel export hit ENOSPC far below its row and byte limits.
    @Test
    fun testExcelTempSheetsAreCompressedOnTheExportVolume() {
        ExportFileWriter.newStreamingWorkbook().use { workbook ->
            try {
                assertTrue(workbook.isCompressTempFiles)
            } finally {
                workbook.dispose()
            }
        }
        val probe = org.apache.poi.util.TempFile.createTempFile("probe", ".tmp").toPath()
        try {
            assertEquals(ExportFileWriter.EXPORT_DIR.resolve(".poi"), probe.parent)
        } finally {
            Files.deleteIfExists(probe)
        }
    }

    // Excel spill files take space on the export volume, so capacity accounting must see them.
    @Test
    fun testManagedArtifactBytesCountsPoiSpillFiles() {
        val before = ExportFileWriter.managedArtifactBytes()
        val spill = Files.createTempFile(ExportFileWriter.POI_TEMP_DIR, "spill", ".tmp")
        try {
            Files.write(spill, ByteArray(4096))
            assertEquals(before + 4096, ExportFileWriter.managedArtifactBytes())
        } finally {
            Files.deleteIfExists(spill)
        }
    }
    @Test
    fun concurrentExportReadersShareTheFenceAndErasureWaitsForAllReaders() {
        val studyId = UUID.randomUUID()
        val secondReader = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val erasureStarted = CountDownLatch(1)
        val erasureAcquired = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val first = ExportFileWriter.acquireStudyExportLock(studyId, shared = true)
        try {
            executor.execute {
                ExportFileWriter.acquireStudyExportLock(studyId, shared = true).use {
                    secondReader.countDown()
                    releaseReader.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("parallel download or generation must not wait", secondReader.await(5, TimeUnit.SECONDS))
            executor.execute {
                erasureStarted.countDown()
                ExportFileWriter.acquireStudyExportLock(studyId).use { erasureAcquired.countDown() }
            }
            assertTrue(erasureStarted.await(5, TimeUnit.SECONDS))
            assertFalse(erasureAcquired.await(100, TimeUnit.MILLISECONDS))
            first.close()
            assertFalse("another reader is still active", erasureAcquired.await(100, TimeUnit.MILLISECONDS))
            releaseReader.countDown()
            assertTrue(erasureAcquired.await(5, TimeUnit.SECONDS))
        } finally {
            first.close()
            releaseReader.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun usageExportsPopulateEventTypeAndTransportTimestamps() {
        val rs = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(rs.getString(Mockito.anyString())).thenReturn("fixture")
        Mockito.`when`(rs.getString(PostgresEventColumns.TIMEZONE.name)).thenReturn("UTC")
        Mockito.`when`(rs.getInt(PostgresEventColumns.EVENT_TYPE.name)).thenReturn(3)
        val timestamps = mapOf(
            PostgresEventColumns.TIMESTAMP to java.time.OffsetDateTime.parse("2026-10-01T10:00:00Z"),
            PostgresEventColumns.COLLECTED_AT to java.time.OffsetDateTime.parse("2026-10-01T10:01:00Z"),
            PostgresEventColumns.UPLOADED_AT to java.time.OffsetDateTime.parse("2026-10-02T10:00:00Z"),
        )
        timestamps.forEach { (column, value) ->
            Mockito.`when`(rs.getObject(column.name, java.time.OffsetDateTime::class.java)).thenReturn(value)
        }
        val row = com.openlattice.chronicle.services.download.DataDownloadService.usageEventRow(rs)
        val columns = PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.map { it.name }
        listOf(ExportFormat.CSV, ExportFormat.JSON).forEach { format ->
            val result = ExportFileWriter.writeMultiDataTypeExport(
                mapOf("usage" to postgresRows(columns, listOf(row))), format, UUID.randomUUID(),
            )
            try {
                val exported = if (format == ExportFormat.CSV) {
                    downloadCsvMapper().readerFor(Map::class.java).with(CsvSchema.emptySchema().withHeader())
                        .readValues<Map<String, String>>(Files.readString(result.path)).use { it.readAll().single() }
                } else ObjectMapper().readTree(Files.readString(result.path)).single()
                    .fields().asSequence().associate { it.key to it.value.asText() }
                assertEquals("3", exported[PostgresEventColumns.EVENT_TYPE.name])
                timestamps.forEach { (column, value) -> assertEquals(value, java.time.OffsetDateTime.parse(exported.getValue(column.name))) }
            } finally {
                Files.deleteIfExists(result.path)
            }
        }
    }

}
