package com.openlattice.chronicle.services.export

import com.openlattice.chronicle.export.ExportFormat
import org.apache.poi.ss.usermodel.WorkbookFactory
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExportFileWriterTest {

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
}
