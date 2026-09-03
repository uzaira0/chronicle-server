package com.openlattice.chronicle.services.export

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.observability.ChronicleMetrics
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

internal data class ExportWriteResult(
    val path: Path,
    val rowCount: Long,
)

internal data class ExportLimits(
    val maxRows: Long,
    val maxBytes: Long,
    val maxRuntime: Duration,
) {
    init {
        require(maxRows > 0) { "Export maxRows must be positive" }
        require(maxBytes > 0) { "Export maxBytes must be positive" }
        require(!maxRuntime.isZero && !maxRuntime.isNegative) { "Export maxRuntime must be positive" }
    }
}

internal open class ExportResourceLimitException(message: String) : IllegalStateException(message)

public class ExportFileWriter private constructor() {

    // reason: cohesive namespace of export writers/helpers (csv/json/excel + spreadsheet-injection
    // guards); splitting would fragment closely related serialization logic
    @Suppress("TooManyFunctions")
    internal companion object {
        private val logger = LoggerFactory.getLogger(ExportFileWriter::class.java)
        private val csvMapper = CsvMapper().apply {
            registerModule(JavaTimeModule())
            registerModule(kotlinModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
        private val jsonMapper = ObjectMappers.newJsonMapper()
        private val EXPORT_DIRECTORY_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rwx------")
        private val EXPORT_FILE_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")

        public val EXPORT_DIR: Path = initializeExportDirectory()
        private val DEFAULT_LIMITS = ExportLimits(
            maxRows = positiveLongSetting("chronicle.export.maxRows", "CHRONICLE_EXPORT_MAX_ROWS", 1_000_000L),
            maxBytes = positiveLongSetting(
                "chronicle.export.maxBytes",
                "CHRONICLE_EXPORT_MAX_BYTES",
                512L * 1024L * 1024L,
            ),
            maxRuntime = Duration.ofSeconds(
                positiveLongSetting(
                    "chronicle.export.maxRuntimeSeconds",
                    "CHRONICLE_EXPORT_MAX_RUNTIME_SECONDS",
                    1_800L,
                ),
            ),
        )
        private val MAX_MANAGED_ARTIFACT_BYTES = positiveLongSetting(
            "chronicle.export.maxTotalBytes",
            "CHRONICLE_EXPORT_MAX_TOTAL_BYTES",
            8L * 1024L * 1024L * 1024L,
        )
        private val MIN_FREE_BYTES = positiveLongSetting(
            "chronicle.export.minFreeBytes",
            "CHRONICLE_EXPORT_MIN_FREE_BYTES",
            1L * 1024L * 1024L * 1024L,
        )
        private const val IN_PROCESS_STUDY_LOCK_STRIPES = 256
        private val inProcessStudyLocks =
            Array(IN_PROCESS_STUDY_LOCK_STRIPES) { ReentrantLock() }
        private val capacityProbe = BoundedExportCapacityProbe(
            readUsableBytes = { EXPORT_DIR.toFile().usableSpace },
            readManagedArtifactBytes = ::managedArtifactBytes,
            timeout = Duration.ofSeconds(1),
            cacheTtl = Duration.ofSeconds(30),
            onSuccess = { capacity ->
                ChronicleMetrics.exportArtifactBytes.set(capacity.managedArtifactBytesAtSample.toDouble())
                ChronicleMetrics.exportStorageUsableBytes.set(capacity.usableBytes.toDouble())
            },
        )
        private val capacityReservationGate = ExportCapacityReservationGate(
            maximumManagedArtifactBytes = MAX_MANAGED_ARTIFACT_BYTES,
            minimumFreeBytes = MIN_FREE_BYTES,
            storageCapacity = capacityProbe::freshCapacity,
            onAdmission = ::refreshStorageMetrics,
            onRejection = { reason ->
                ChronicleMetrics.exportStorageAdmissionRejectionsTotal.labels(reason).inc()
            },
        )

        init {
            refreshStorageMetricsFromCache()
            capacityProbe.refreshAsync()
        }

        private const val CSV_DATA_TYPE_COLUMN = "data_type"
        private val spreadsheetFormulaPrefixes = setOf('=', '+', '-', '@')
        private val spreadsheetControlPrefixes = setOf('\t', '\r', '\n')

        internal val defaultCapacityReservationBytes: Long
            get() = DEFAULT_LIMITS.maxBytes

        internal val maximumManagedArtifactBytes: Long
            get() = MAX_MANAGED_ARTIFACT_BYTES

        internal val minimumFreeBytes: Long
            get() = MIN_FREE_BYTES

        internal fun freshStorageCapacityForAdmission(): ExportStorageCapacity = capacityProbe.freshCapacity()

        public fun writeExportFile(
            data: List<Map<String, Any>>,
            format: ExportFormat,
            exportId: java.util.UUID,
            dataTypeName: String
        ): Path {
            val fileName = "${exportId}_${dataTypeName}.${format.extension()}"
            val filePath = EXPORT_DIR.resolve(fileName)
            val budget = ExportBudget(DEFAULT_LIMITS)

            withCapacityReservation(DEFAULT_LIMITS.maxBytes) {
                val attemptId = UUID.randomUUID()
                writeAtomically(filePath, workingPath(filePath, attemptId)) { workingPath ->
                    when (format) {
                        ExportFormat.CSV -> writeCsv(mapOf(dataTypeName to data), workingPath, budget)
                        ExportFormat.JSON -> writeJson(listOf(data), workingPath, budget)
                        ExportFormat.EXCEL -> writeExcel(data, workingPath, budget)
                    }
                }
            }

            logger.info("Wrote {} rows to {} for export {}", data.size, filePath, exportId)
            return filePath
        }

        public fun writeMultiDataTypeExport(
            dataByType: Map<String, Iterable<Map<String, Any>>>,
            format: ExportFormat,
            exportId: java.util.UUID,
            attemptId: java.util.UUID = java.util.UUID.randomUUID(),
            limits: ExportLimits = DEFAULT_LIMITS,
        ): ExportWriteResult {
            return withCapacityReservation(limits.maxBytes) {
                val budget = ExportBudget(limits)
                val filePath = attemptFinalPath(exportId, format, attemptId)
                when (format) {
                    ExportFormat.EXCEL -> {
                        writeAtomically(filePath, attemptWorkingPath(filePath)) { workingPath ->
                            writeMultiSheetExcel(dataByType, workingPath, budget)
                        }
                    }
                    else -> {
                        writeAtomically(filePath, attemptWorkingPath(filePath)) { workingPath ->
                            when (format) {
                                ExportFormat.CSV -> writeCsv(dataByType, workingPath, budget)
                                ExportFormat.JSON -> writeJson(dataByType.values, workingPath, budget)
                                ExportFormat.EXCEL -> error("EXCEL export format is not yet supported")
                            }
                        }
                    }
                }
                ExportWriteResult(filePath, budget.rowCount)
            }
        }

        private fun writeCsv(
            dataByType: Map<String, Iterable<Map<String, Any>>>,
            filePath: Path,
            budget: ExportBudget,
        ) {
            val cursors = mutableListOf<TypedCsvCursor>()
            var primaryFailure: Exception? = null
            try {
                dataByType.toSortedMap().forEach { (dataType, rows) ->
                    require(dataType.isNotBlank()) { "CSV export data type must not be blank" }
                    val cursor = TypedCsvCursor(dataType, rows.iterator())
                    cursors.add(cursor)
                    cursor.prime()
                }
                val dataColumns = sortedSetOf<String>()
                cursors.forEach { cursor ->
                    cursor.expectedColumns?.let(dataColumns::addAll)
                }
                if (cursors.none(TypedCsvCursor::hasNext)) {
                    Files.writeString(filePath, "")
                    return
                }

                val columns = listOf(CSV_DATA_TYPE_COLUMN) + dataColumns
                val schemaBuilder = CsvSchema.builder()
                columns.forEach(schemaBuilder::addColumn)
                val schema = schemaBuilder.setUseHeader(true).build()

                boundedOutput(filePath, budget).use { output ->
                    csvMapper.writer(schema).writeValues(output).use { writer ->
                        cursors.forEach { cursor ->
                            while (cursor.hasNext()) {
                                val row = budget.accept(cursor.next())
                                cursor.validate(row)
                                writer.write(typedCsvRow(cursor.dataType, dataColumns, row))
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                primaryFailure = failure
                throw failure
            } finally {
                closeTypedCsvCursors(cursors, primaryFailure)
            }
        }

        private fun typedCsvRow(
            dataType: String,
            dataColumns: Iterable<String>,
            row: Map<String, Any>,
        ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            put(CSV_DATA_TYPE_COLUMN, spreadsheetSafeString(dataType))
            dataColumns.forEach { column ->
                put(
                    column,
                    when (val value = row[column]) {
                        is String -> spreadsheetSafeString(value)
                        else -> value
                    },
                )
            }
        }

        private fun closeTypedCsvCursors(
            cursors: List<TypedCsvCursor>,
            primaryFailure: Exception?,
        ) {
            var closeFailure: Exception? = null
            cursors.asReversed().forEach { cursor ->
                try {
                    cursor.close()
                } catch (failure: Exception) {
                    if (closeFailure == null) {
                        closeFailure = failure
                    } else {
                        closeFailure.addSuppressed(failure)
                    }
                }
            }
            if (closeFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    throw closeFailure
                }
            }
        }

        private fun writeJson(
            datasets: Iterable<Iterable<Map<String, Any>>>,
            filePath: Path,
            budget: ExportBudget,
        ) {
            val rows = ClosingIterator(datasets)
            try {
                boundedOutput(filePath, budget).use { output ->
                    jsonMapper.factory.createGenerator(output).use { generator ->
                        generator.useDefaultPrettyPrinter()
                        generator.writeStartArray()
                        while (rows.hasNext()) {
                            jsonMapper.writeValue(generator, budget.accept(rows.next()))
                        }
                        generator.writeEndArray()
                    }
                }
            } finally {
                rows.close()
            }
        }

        @Suppress("DEPRECATION")
        private fun writeExcel(
            data: Iterable<Map<String, Any>>,
            filePath: Path,
            budget: ExportBudget,
        ) {
            val rows = ClosingIterator(listOf(data))
            val workbook = SXSSFWorkbook(100)
            try {
                val wb = workbook
                val sheet = wb.createSheet("Export")
                if (!rows.hasNext()) {
                    boundedOutput(filePath, budget).use { wb.write(it) }
                    return
                }
                val first = budget.accept(rows.next())
                val columns = first.keys.toList()
                val headerRow = sheet.createRow(0)
                columns.forEachIndexed { idx, col -> headerRow.createCell(idx).setCellValue(col) }
                writeExcelRow(sheet, 1, columns, first)
                var rowIndex = 2
                while (rows.hasNext()) {
                    writeExcelRow(sheet, rowIndex++, columns, budget.accept(rows.next()))
                }
                boundedOutput(filePath, budget).use { wb.write(it) }
            } finally {
                rows.close()
                try {
                    workbook.close()
                } finally {
                    workbook.dispose()
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun writeMultiSheetExcel(
            dataByType: Map<String, Iterable<Map<String, Any>>>,
            filePath: Path,
            budget: ExportBudget,
        ) {
            val workbook = SXSSFWorkbook(100)
            try {
                val wb = workbook
                for ((sheetName, data) in dataByType) {
                    val sheet = wb.createSheet(sheetName)
                    val rows = ClosingIterator(listOf(data))
                    try {
                        if (rows.hasNext()) {
                            val first = budget.accept(rows.next())
                            val columns = first.keys.toList()
                            val headerRow = sheet.createRow(0)
                            columns.forEachIndexed { idx, col -> headerRow.createCell(idx).setCellValue(col) }
                            writeExcelRow(sheet, 1, columns, first)
                            var rowIndex = 2
                            while (rows.hasNext()) {
                                writeExcelRow(sheet, rowIndex++, columns, budget.accept(rows.next()))
                            }
                        }
                    } finally {
                        rows.close()
                    }
                }
                boundedOutput(filePath, budget).use { wb.write(it) }
            } finally {
                try {
                    workbook.close()
                } finally {
                    workbook.dispose()
                }
            }
        }

        private fun writeExcelRow(
            sheet: org.apache.poi.ss.usermodel.Sheet,
            rowIndex: Int,
            columns: List<String>,
            row: Map<String, Any>,
        ) {
            val excelRow = sheet.createRow(rowIndex)
            columns.forEachIndexed { colIdx, col ->
                val cell = excelRow.createCell(colIdx)
                when (val value = row[col]) {
                    is Number -> cell.setCellValue(value.toDouble())
                    is Boolean -> cell.setCellValue(value)
                    null -> cell.setBlank()
                    else -> cell.setCellValue(spreadsheetSafeString(value.toString()))
                }
            }
        }

        // reason: boundary catch — best-effort file deletion logs and swallows any IO/security failure type
        @Suppress("TooGenericExceptionCaught")
        public fun deleteExportFile(filePath: String) {
            try {
                val target = Paths.get(filePath).toAbsolutePath().normalize()
                val deleted = Files.deleteIfExists(target)
                if (deleted && target.parent == EXPORT_DIR) {
                    forceExportDirectory()
                }
            } catch (ex: Exception) {
                logger.warn("Failed to delete export file: {}", filePath, ex)
            }
        }

        /**
         * Strict, idempotent erasure path. Never accept a database-controlled
         * path outside Chronicle's export directory and never swallow an I/O
         * failure: the deletion operation must remain retryable instead of
         * producing a false proof.
         */
        public fun deleteExportFileForErasure(filePath: String) {
            val target = requireManagedExportPath(filePath)
            deleteManagedPathsDurably(listOf(target)) { "Export artifact still exists after deletion" }
        }

        /**
         * Deletes every deterministic asynchronous-export path for an id. This
         * includes the `.part` working file, so a process crash before the
         * database records file_path cannot escape verified erasure.
         */
        public fun deleteExportArtifactsForErasure(
            exportId: java.util.UUID,
            recordedFilePath: String?,
        ) {
            val id = exportId.toString()
            val candidates = buildSet<Path> {
                if (!recordedFilePath.isNullOrBlank()) {
                    val recorded = requireManagedExportPath(recordedFilePath)
                    require(recorded.fileName.toString().startsWith("$id.")) {
                        "Recorded export artifact does not belong to export $exportId"
                    }
                    add(recorded)
                }
                Files.list(EXPORT_DIR).use { paths ->
                    paths
                        .filter { candidate -> candidate.fileName.toString().startsWith("$id.") }
                        .forEach(::add)
                }
            }
            deleteManagedPathsDurably(candidates) { candidate ->
                "Export artifact still exists after deletion: ${candidate.fileName}"
            }
        }

        /**
         * Cleans only artifacts belonging to one fenced generation attempt.
         * A stale worker must never delete another attempt's canonical file.
         */
        public fun deleteExportAttemptArtifacts(
            exportId: java.util.UUID,
            format: ExportFormat,
            attemptId: java.util.UUID,
            includeCanonical: Boolean,
        ) {
            val legacyFinalPath = EXPORT_DIR.resolve("${exportId}.${format.extension()}")
            val attemptFinalPath = attemptFinalPath(exportId, format, attemptId)
            val paths = buildList {
                add(attemptWorkingPath(attemptFinalPath))
                add(attemptFinalPath)
                add(workingPath(legacyFinalPath, attemptId))
                if (includeCanonical) add(legacyFinalPath)
            }
            deleteManagedPathsDurably(paths) { path ->
                "Export attempt artifact still exists after deletion: ${path.fileName}"
            }
        }

        /**
         * Streams only a regular, non-symlink file directly inside Chronicle's
         * managed export directory. The database path is not trusted.
         */
        public fun copyManagedExportFile(filePath: String, outputStream: OutputStream) {
            val target = verifyManagedExportFile(filePath)
            Files.newByteChannel(
                target,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                Channels.newInputStream(channel).use { input ->
                    input.copyTo(outputStream)
                }
            }
        }

        public fun verifyManagedExportFile(filePath: String): Path {
            val target = requireManagedExportPath(filePath)
            check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "Export file has been cleaned up or is not a managed regular file"
            }
            return target
        }

        private fun requireManagedExportPath(filePath: String): Path {
            val target = Paths.get(filePath).toAbsolutePath().normalize()
            require(target.parent == EXPORT_DIR) {
                "Export artifact path is outside the managed export directory"
            }
            return target
        }

        private fun initializeExportDirectory(): Path {
            val configured = resolveConfiguredExportDirectory(
                System.getProperty("chronicle.export.dir"),
                System.getenv("CHRONICLE_EXPORT_DIR"),
            )
            val path = Paths.get(configured).toAbsolutePath().normalize()
            Files.createDirectories(
                path,
                PosixFilePermissions.asFileAttribute(EXPORT_DIRECTORY_PERMISSIONS),
            )
            require(!Files.isSymbolicLink(path)) { "Chronicle export directory must not be a symbolic link" }
            try {
                Files.getPosixFilePermissions(path)
            } catch (_: UnsupportedOperationException) {
                throw IllegalArgumentException("Chronicle export storage must support POSIX permissions")
            }
            Files.setPosixFilePermissions(path, EXPORT_DIRECTORY_PERMISSIONS)
            return path.toRealPath()
        }

        internal fun resolveConfiguredExportDirectory(
            systemPropertyValue: String?,
            environmentValue: String?,
        ): String = systemPropertyValue?.takeIf { it.isNotBlank() }
            ?: environmentValue?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Chronicle export storage must be explicitly configured with " +
                    "chronicle.export.dir or CHRONICLE_EXPORT_DIR",
            )

        private fun workingPath(finalPath: Path, attemptId: java.util.UUID): Path =
            finalPath.resolveSibling("${finalPath.fileName}.$attemptId.part")

        private fun attemptFinalPath(
            exportId: UUID,
            format: ExportFormat,
            attemptId: UUID,
        ): Path = EXPORT_DIR.resolve("$exportId.${format.extension()}.$attemptId")

        private fun attemptWorkingPath(attemptFinalPath: Path): Path =
            attemptFinalPath.resolveSibling("${attemptFinalPath.fileName}.part")

        private fun writeAtomically(
            finalPath: Path,
            workingPath: Path,
            writer: (Path) -> Unit,
        ) {
            if (Files.deleteIfExists(workingPath)) {
                forceExportDirectory()
            }
            var published = false
            try {
                Files.createFile(
                    workingPath,
                    PosixFilePermissions.asFileAttribute(EXPORT_FILE_PERMISSIONS),
                )
                writer(workingPath)
                FileChannel.open(workingPath, StandardOpenOption.WRITE).use { channel ->
                    channel.force(true)
                }
                try {
                    Files.move(
                        workingPath,
                        finalPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    published = true
                    forceExportDirectory()
                } catch (unsupported: AtomicMoveNotSupportedException) {
                    throw IllegalStateException(
                        "Managed export storage must support atomic rename",
                        unsupported,
                    )
                }
            } catch (failure: Exception) {
                try {
                    var deleted = Files.deleteIfExists(workingPath)
                    if (published) {
                        deleted = Files.deleteIfExists(finalPath) || deleted
                    }
                    if (deleted) {
                        forceExportDirectory()
                    }
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        /**
         * Filesystem fence paired with the PostgreSQL per-study deletion lock.
         *
         * The database lock orders operations and protects durable state. This
         * lock remains held if that database session dies while a file read or
         * write is still executing, so erasure cannot prove completion before
         * the in-flight filesystem operation has stopped and been swept.
         */
        internal fun acquireStudyExportLock(studyId: UUID): AutoCloseable {
            val inProcessLock = inProcessStudyLocks[
                Math.floorMod(studyId.hashCode(), IN_PROCESS_STUDY_LOCK_STRIPES)
            ]
            inProcessLock.lockInterruptibly()
            var channel: FileChannel? = null
            try {
                val lockPath = EXPORT_DIR.resolve(".study-$studyId.lock")
                channel = FileChannel.open(
                    lockPath,
                    setOf(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                    PosixFilePermissions.asFileAttribute(EXPORT_FILE_PERMISSIONS),
                )
                check(Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    "Export study lock path is not a regular file"
                }
                Files.setPosixFilePermissions(lockPath, EXPORT_FILE_PERMISSIONS)
                channel.lock()
                val lockedChannel = channel
                return AutoCloseable {
                    try {
                        lockedChannel.close()
                    } finally {
                        inProcessLock.unlock()
                    }
                }
            } catch (failure: Exception) {
                try {
                    channel?.close()
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                } finally {
                    inProcessLock.unlock()
                }
                throw failure
            }
        }

        private fun deleteManagedPathsDurably(
            paths: Iterable<Path>,
            residualMessage: (Path) -> String,
        ) {
            var deleted = false
            paths.forEach { path ->
                deleted = Files.deleteIfExists(path) || deleted
                check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    residualMessage(path)
                }
            }
            if (deleted) {
                forceExportDirectory()
            }
        }

        private fun forceExportDirectory() {
            FileChannel.open(EXPORT_DIR, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }

        private fun <T> withCapacityReservation(requestedBytes: Long, action: () -> T): T {
            try {
                return capacityReservationGate.withReservation(requestedBytes, action)
            } finally {
                try {
                    refreshStorageMetricsFromCache()
                    capacityProbe.refreshAsync(force = true)
                } catch (_: Exception) {
                    logger.warn("Failed to refresh export storage metrics")
                }
            }
        }

        private fun refreshStorageMetrics(
            managedBytes: Long,
            usableBytes: Long,
        ) {
            ChronicleMetrics.exportArtifactBytes.set(managedBytes.toDouble())
            ChronicleMetrics.exportStorageUsableBytes.set(usableBytes.toDouble())
        }

        private fun refreshStorageMetricsFromCache() {
            capacityProbe.lastSuccessfulCapacity()?.let { capacity ->
                ChronicleMetrics.exportArtifactBytes.set(capacity.managedArtifactBytesAtSample.toDouble())
                ChronicleMetrics.exportStorageUsableBytes.set(capacity.usableBytes.toDouble())
            }
        }

        private fun managedArtifactBytes(): Long =
            Files.list(EXPORT_DIR).use { paths ->
                paths
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .mapToLong { path ->
                        try {
                            Files.size(path)
                        } catch (_: java.nio.file.NoSuchFileException) {
                            0L
                        }
                    }
                    .reduce(0L, ::saturatingAdd)
            }

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

        private fun boundedOutput(filePath: Path, budget: ExportBudget): OutputStream =
            BufferedOutputStream(
                BoundedOutputStream(
                    FileOutputStream(filePath.toFile()),
                    budget,
                ),
            )

        private fun positiveLongSetting(
            systemProperty: String,
            environmentVariable: String,
            defaultValue: Long,
        ): Long {
            val raw = System.getProperty(systemProperty)
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }
                ?: return defaultValue
            return requireNotNull(raw.toLongOrNull()?.takeIf { it > 0 }) {
                "$environmentVariable must be a positive integer"
            }
        }

        private class ExportBudget(private val limits: ExportLimits) {
            private val startedAt = Instant.now()
            var rowCount: Long = 0
                private set

            fun <T> accept(row: T): T {
                checkRuntime()
                rowCount += 1
                if (rowCount > limits.maxRows) {
                    throw ExportResourceLimitException(
                        "Export exceeds the configured row limit (${limits.maxRows})",
                    )
                }
                return row
            }

            fun acceptBytes(bytes: Int) {
                checkRuntime()
                if (bytes < 0) return
                val next = byteCount + bytes
                if (next > limits.maxBytes) {
                    throw ExportResourceLimitException(
                        "Export exceeds the configured byte limit (${limits.maxBytes})",
                    )
                }
                byteCount = next
            }

            private var byteCount: Long = 0

            private fun checkRuntime() {
                if (Duration.between(startedAt, Instant.now()) > limits.maxRuntime) {
                    throw ExportResourceLimitException(
                        "Export exceeds the configured runtime limit (${limits.maxRuntime.seconds}s)",
                    )
                }
            }
        }

        private class BoundedOutputStream(
            delegate: OutputStream,
            private val budget: ExportBudget,
        ) : FilterOutputStream(delegate) {
            override fun write(value: Int) {
                budget.acceptBytes(1)
                out.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                budget.acceptBytes(length)
                out.write(bytes, offset, length)
            }
        }

        private class TypedCsvCursor(
            val dataType: String,
            private val iterator: Iterator<Map<String, Any>>,
        ) : Iterator<Map<String, Any>>, AutoCloseable {
            var expectedColumns: Set<String>? = null
                private set
            private var firstRow: Map<String, Any>? = null
            private var primed = false
            private var firstPending = false
            private var closed = false

            fun prime() {
                check(!primed) { "CSV cursor was already primed" }
                primed = true
                if (iterator.hasNext()) {
                    val row = iterator.next()
                    require(CSV_DATA_TYPE_COLUMN !in row) {
                        "CSV export row uses reserved column $CSV_DATA_TYPE_COLUMN"
                    }
                    expectedColumns = row.keys.toSet()
                    firstRow = row
                    firstPending = true
                }
            }

            fun validate(row: Map<String, Any>) {
                require(row.keys == expectedColumns) {
                    "CSV export rows for data type $dataType do not have a stable schema"
                }
            }

            override fun hasNext(): Boolean {
                check(primed) { "CSV cursor must be primed before use" }
                return firstPending || iterator.hasNext()
            }

            override fun next(): Map<String, Any> {
                check(hasNext()) { "No more CSV export rows" }
                if (firstPending) {
                    firstPending = false
                    return requireNotNull(firstRow).also { firstRow = null }
                }
                return iterator.next()
            }

            override fun close() {
                if (closed) return
                closed = true
                if (iterator is AutoCloseable) {
                    iterator.close()
                }
            }
        }

        private class ClosingIterator<T>(
            private val datasets: Iterable<Iterable<T>>,
        ) : Iterator<T>, AutoCloseable {
            private val datasetIterator = datasets.iterator()
            private var current: Iterator<T>? = null

            override fun hasNext(): Boolean {
                while (true) {
                    val iterator = current
                    if (iterator != null && iterator.hasNext()) return true
                    closeCurrent()
                    if (!datasetIterator.hasNext()) return false
                    current = datasetIterator.next().iterator()
                }
            }

            override fun next(): T {
                check(hasNext()) { "No more export rows" }
                return requireNotNull(current).next()
            }

            override fun close() {
                closeCurrent()
            }

            private fun closeCurrent() {
                val iterator = current
                current = null
                if (iterator is AutoCloseable) {
                    iterator.close()
                }
            }
        }

        private fun ExportFormat.extension(): String = when (this) {
            ExportFormat.CSV -> "csv"
            ExportFormat.JSON -> "json"
            ExportFormat.EXCEL -> "xlsx"
        }

        private fun spreadsheetSafeRow(row: Map<String, Any>): Map<String, Any?> {
            return row.mapValues { (_, value) ->
                when (value) {
                    is String -> spreadsheetSafeString(value)
                    else -> value
                }
            }
        }

        private fun spreadsheetSafeString(value: String): String {
            if (value.isEmpty()) return value
            val first = value.first()
            val firstNonSpace = value.firstOrNull { it != ' ' }
            return if (
                first in spreadsheetControlPrefixes ||
                firstNonSpace in spreadsheetFormulaPrefixes
            ) {
                "'$value"
            } else {
                value
            }
        }
    }
}
