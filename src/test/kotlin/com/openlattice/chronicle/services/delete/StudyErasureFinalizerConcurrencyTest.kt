package com.openlattice.chronicle.services.delete

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real erasure workers share inventory locks with other workers and queued ingestion writers. */
class StudyErasureFinalizerConcurrencyTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun start() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_finalizer_concurrency")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `study finalization permits a queued independent sensor writer to finish`() {
        val erasedStudyId = UUID.randomUUID()
        val controlStudyId = UUID.randomUUID()
        val participantId = "control-participant-${UUID.randomUUID()}"
        val sampleId = UUID.randomUUID()
        val finalizerName = "finalizer-source-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        pool(finalizerName).use { finalizerPool ->
            pool("finalizer-control-writer-${UUID.randomUUID()}").use { writerPool ->
                seedStudy(erasedStudyId)
                seedStudy(controlStudyId)
                val clock = Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC)
                val finalizer = DataDeletionOrchestrator(resolver(finalizerPool), mock<AuditingManager>(), clock)
                val operationId = finalizer.quarantineStudy(erasedStudyId, "finalizer-test", UUID.randomUUID())
                writerPool.connection.use { writer ->
                    writer.autoCommit = false
                    writer.createStatement().use { it.execute("LOCK TABLE sensor_data IN ROW EXCLUSIVE MODE") }
                    val worker = executor.submit<Int> { finalizer.processDueOperations(1) }
                    var insertStarted = false
                    try {
                        postgres.createConnection("").use { observer ->
                            awaitSensorInventoryLock(observer, finalizerName)
                        }
                        // The finalizer is queued behind this transaction's source lock. INSERT
                        // now needs RowExclusive on the identity ledger from its AFTER trigger.
                        insertStarted = true
                        val insertion = executor.submit<Int> {
                            try {
                                val inserted = writer.prepareStatement("""
                                    INSERT INTO sensor_data (study_id, participant_id, sample_id, sensor_type,
                                        sample_duration, device_version, device_name, device_model, device_system_name)
                                    VALUES (?, ?, ?, 'phoneUsage', 60, '26', 'fixture', 'phone', 'iOS')
                                """.trimIndent()).use { statement ->
                                    statement.setString(1, controlStudyId.toString())
                                    statement.setString(2, participantId)
                                    statement.setString(3, sampleId.toString())
                                    statement.executeUpdate()
                                }
                                writer.commit()
                                inserted
                            } catch (failure: Throwable) {
                                writer.rollback()
                                throw failure
                            }
                        }
                        assertEquals("control-study ingestion failed behind the finalizer", 1, insertion.get(20, TimeUnit.SECONDS))
                        assertEquals("finalizer failed while the control-study source writer was queued", 1, worker.get(20, TimeUnit.SECONDS))
                        assertEquals("COMPLETED", finalizer.getOperation(operationId).status)
                        postgres.createConnection("").use { observer ->
                            observer.prepareStatement("""
                                SELECT (SELECT count(*) FROM sensor_data WHERE study_id = ?),
                                    (SELECT count(*) FROM data_deletion_tombstones WHERE operation_id = ?)
                            """.trimIndent()).use { statement ->
                                statement.setString(1, controlStudyId.toString())
                                statement.setObject(2, operationId)
                                statement.executeQuery().use { rows ->
                                    assertTrue(rows.next())
                                    assertEquals(1L, rows.getLong(1))
                                    assertEquals(1L, rows.getLong(2))

                                }
                            }
                        }
                    } finally {
                        if (!insertStarted) writer.rollback()
                        executor.shutdownNow()
                        assertTrue("writer and finalizer did not stop", executor.awaitTermination(20, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test
    fun `queued study finalizer and another study drain follow one table lock order`() {
        val erasedStudy = UUID.randomUUID()
        val bufferedStudy = UUID.randomUUID()
        val participant = "buffer-lock-order"
        val barrierKey = UUID.randomUUID().mostSignificantBits
        val finalizerName = "buffer-finalizer-${UUID.randomUUID()}"
        val drainName = "buffer-drain-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        pool(finalizerName).use { finalizerPool -> pool(drainName).use { drainPool ->
            seedStudy(erasedStudy); seedStudy(bufferedStudy)
            val finalizer = DataDeletionOrchestrator(resolver(finalizerPool), mock<AuditingManager>(),
                Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC))
            val operation = finalizer.quarantineStudy(erasedStudy, "order-test", UUID.randomUUID())
            val drain = com.openlattice.chronicle.services.upload.AppDataUploadService(resolver(drainPool), mock(), mock())
            val event = com.openlattice.chronicle.android.ChronicleUsageEvent(studyId = bufferedStudy,
                participantId = participant, appPackageName = "com.example.order", interactionType = "Move to Foreground",
                timestamp = java.time.OffsetDateTime.now(), timezone = "UTC", user = "", applicationLabel = "Order")
            assertEquals(1, drain.uploadAndroidUsageEvents(bufferedStudy, participant, UUID.randomUUID(), listOf(event)))
            postgres.createConnection("").use { observer ->
                observer.createStatement().use { statement ->
                    statement.execute("""CREATE FUNCTION hold_buffer_drain() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                        BEGIN IF current_setting('application_name') = '$drainName' THEN
                            PERFORM pg_advisory_xact_lock($barrierKey);
                        END IF; RETURN NULL; END ${'$'}${'$'}""")
                    statement.execute("CREATE TRIGGER hold_buffer_drain AFTER DELETE ON upload_buffer FOR EACH STATEMENT EXECUTE FUNCTION hold_buffer_drain()")
                    statement.execute("SELECT pg_advisory_lock($barrierKey)")
                }
                val draining = executor.submit {
                    com.openlattice.chronicle.storage.rls.RLSRequestContext.withSystemContext {
                        drain.moveToEventStorage(bufferedStudy, participant)
                    }
                }
                var worker: java.util.concurrent.Future<Int>? = null
                try {
                    awaitQueryLock(observer, drainName, "%DELETE FROM upload_buffer%")
                    worker = executor.submit<Int> { finalizer.processDueOperations(1) }
                    awaitQueryLock(observer, finalizerName, "%chronicle_lock_study_table_for_erasure%")
                    observer.createStatement().use { it.execute("SELECT pg_advisory_unlock($barrierKey)") }
                    draining.get(10, TimeUnit.SECONDS)
                    assertEquals(1, worker.get(10, TimeUnit.SECONDS))
                    assertEquals("COMPLETED", finalizer.getOperation(operation).status)
                    observer.createStatement().use { it.executeQuery("SELECT count(*) FROM chronicle_usage_events WHERE study_id = '$bufferedStudy'").use { rows ->
                        assertTrue(rows.next()); assertEquals(1L, rows.getLong(1))
                    } }
                } finally {
                    observer.createStatement().use { it.execute("SELECT pg_advisory_unlock($barrierKey)") }
                    try { draining.get(20, TimeUnit.SECONDS) } catch (_: Exception) { }
                    try { worker?.get(20, TimeUnit.SECONDS) } catch (_: Exception) { }
                    observer.createStatement().use {
                        it.execute("DROP TRIGGER hold_buffer_drain ON upload_buffer")
                        it.execute("DROP FUNCTION hold_buffer_drain()")
                    }
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
                }
            }
        } }
    }

    @Test
    fun `a study arriving after the fence census waits for the next drain`() {
        val first = UUID.randomUUID()
        val arrived = UUID.randomUUID()
        seedStudy(first)
        seedStudy(arrived)
        fun enqueue(study: UUID) = postgres.createConnection("").use { writer ->
            writer.prepareStatement("""INSERT INTO upload_buffer
                (study_id, participant_id, upload_type, data, uploaded_at, device_id)
                VALUES (?, 'census-test', 'Android', '[]'::jsonb, now(), ?)""").use {
                it.setObject(1, study)
                it.setObject(2, UUID.randomUUID())
                it.executeUpdate()
            }
        }
        enqueue(first)
        postgres.createConnection("").use { owner ->
            owner.autoCommit = false
            val fenced = com.openlattice.chronicle.storage.DeletionTableLockOrder.lockDrain(owner, "chronicle_usage_events")
            enqueue(arrived)
            fun claim(studies: List<UUID>): List<UUID> = owner.createStatement().use { statement ->
                statement.executeQuery(com.openlattice.chronicle.storage.ChroniclePostgresTables.getMoveSql(
                    128, com.openlattice.chronicle.services.upload.UploadType.Android, studies,
                )).use { rows -> buildList { while (rows.next()) add(rows.getObject("study_id", UUID::class.java)) } }
            }
            val firstClaim = claim(fenced)
            assertTrue(first in firstClaim)
            assertFalse(arrived in firstClaim)
            owner.commit()
            val nextFenced = com.openlattice.chronicle.storage.DeletionTableLockOrder.lockDrain(owner, "chronicle_usage_events")
            assertTrue(arrived in claim(nextFenced))
            owner.commit()
        }
    }

    private fun awaitQueryLock(connection: Connection, applicationName: String, queryPattern: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock' AND query LIKE ?)").use {
            it.setString(1, applicationName); it.setString(2, queryPattern)
            while (System.nanoTime() < deadline) {
                it.executeQuery().use { rows -> if (rows.next() && rows.getBoolean(1)) return }
                Thread.sleep(10)
            }
        }
        throw AssertionError("Expected queued lock for $applicationName")
    }

    @Test
    fun `two study finalizers acquire table inventory before export writes without a lock upgrade deadlock`() {
        val firstStudyId = UUID.randomUUID()
        val secondStudyId = generateSequence(UUID::randomUUID).first {
            Math.floorMod(it.hashCode(), 256) != Math.floorMod(firstStudyId.hashCode(), 256)
        }
        val firstName = "finalizer-first-${UUID.randomUUID()}"
        val secondName = "finalizer-second-${UUID.randomUUID()}"
        val barrierKey = UUID.randomUUID().mostSignificantBits
        val executor = Executors.newFixedThreadPool(2)
        pool(firstName).use { firstPool ->
            pool(secondName).use { secondPool ->
                val clock = Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC)
                val first = DataDeletionOrchestrator(resolver(firstPool), mock<AuditingManager>(), clock)
                val second = DataDeletionOrchestrator(resolver(secondPool), mock<AuditingManager>(), clock)
                seedStudy(firstStudyId)
                seedStudy(secondStudyId)
                val firstOperationId = first.quarantineStudy(firstStudyId, "finalizer-test", UUID.randomUUID())
                val secondOperationId = second.quarantineStudy(secondStudyId, "finalizer-test", UUID.randomUUID())
                postgres.createConnection("").use { barrier ->
                    installFinalizationBarrier(barrier, barrierKey)
                    barrier.prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
                        statement.setLong(1, barrierKey)
                        statement.execute()
                    }
                    try {
                        val firstWorker = executor.submit<Int> { first.processDueOperations(1) }
                        awaitFinalizationBarrier(barrier, firstName)
                        val secondWorker = executor.submit<Int> { second.processDueOperations(1) }
                        awaitFinalizationBarrier(barrier, secondName)
                        val queuedLocks = exportLocksAtBarrier(barrier, firstName, secondName)
                        // Both are now in completeOperation. The original order holds one
                        // RowExclusive(export_jobs) per worker, creating a table-lock upgrade cycle.
                        barrier.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                            statement.setLong(1, barrierKey)
                            statement.execute()
                        }
                        assertEquals("first finalizer failed; queued export locks: $queuedLocks", 1, firstWorker.get(20, TimeUnit.SECONDS))
                        assertEquals("second finalizer failed; queued export locks: $queuedLocks", 1, secondWorker.get(20, TimeUnit.SECONDS))
                        assertEquals("COMPLETED", first.getOperation(firstOperationId).status)
                        assertEquals("COMPLETED", second.getOperation(secondOperationId).status)
                        barrier.prepareStatement("SELECT count(*) FROM data_deletion_tombstones WHERE operation_id = ANY(?)").use { statement ->
                            statement.setArray(1, barrier.createArrayOf("uuid", arrayOf(firstOperationId, secondOperationId)))
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                assertEquals(2L, rows.getLong(1))
                            }
                        }
                    } finally {
                        barrier.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                            statement.setLong(1, barrierKey)
                            statement.execute()
                        }
                        executor.shutdownNow()
                        assertTrue("finalizers did not stop", executor.awaitTermination(20, TimeUnit.SECONDS))
                        restoreLockFunction(barrier)
                    }
                }
            }
        }
    }

    private fun pool(name: String): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 3
        minimumIdle = 0
        addDataSourceProperty("ApplicationName", name)
        connectionInitSql = "SET statement_timeout = '15s'; SET deadlock_timeout = '100ms'"
    })

    private fun resolver(pool: HikariDataSource): StorageResolver {
        val manager = mock<DataSourceManager>()
        whenever(manager.getDataSource(any())).thenReturn(pool)
        whenever(manager.getFlavor(any())).thenReturn(PostgresFlavor.VANILLA)
        return StorageResolver(manager, ChronicleStorageConfiguration(defaultEventStorage = "default"))
    }

    private fun seedStudy(studyId: UUID) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'finalizer concurrency')").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO export_jobs (export_id, study_id, status, format, request, created_by)
                VALUES (?, ?, 'PENDING', 'CSV', '{}'::jsonb, 'finalizer-test')
            """.trimIndent()).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, studyId)
                statement.executeUpdate()
            }
        }
    }

    private fun installFinalizationBarrier(connection: Connection, barrierKey: Long) {
        connection.createStatement().use { statement ->
            statement.execute("ALTER FUNCTION chronicle_lock_study_table_for_erasure(text, uuid) RENAME TO finalizer_original_study_table_lock")
            statement.execute("""
                CREATE FUNCTION chronicle_lock_study_table_for_erasure(target_table text, target_study_id uuid)
                RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS ${'$'}${'$'}
                BEGIN
                    PERFORM pg_advisory_xact_lock_shared($barrierKey);
                    PERFORM public.finalizer_original_study_table_lock(target_table, target_study_id);
                END;
                ${'$'}${'$'}
            """.trimIndent())
            statement.execute("REVOKE ALL ON FUNCTION chronicle_lock_study_table_for_erasure(text, uuid) FROM PUBLIC")
            statement.execute("GRANT EXECUTE ON FUNCTION chronicle_lock_study_table_for_erasure(text, uuid) TO chronicle_app")
        }
    }

    private fun restoreLockFunction(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("DROP FUNCTION chronicle_lock_study_table_for_erasure(text, uuid)")
            statement.execute("ALTER FUNCTION finalizer_original_study_table_lock(text, uuid) RENAME TO chronicle_lock_study_table_for_erasure")
        }
    }

    private fun awaitFinalizationBarrier(connection: Connection, applicationName: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM pg_stat_activity
                WHERE application_name = ? AND wait_event_type = 'Lock'
                  AND query LIKE 'SELECT chronicle_lock_study_table_for_erasure%'
            )
        """.trimIndent()).use { statement ->
            statement.setString(1, applicationName)
            while (System.nanoTime() < deadline) {
                statement.executeQuery().use { rows ->
                    if (rows.next() && rows.getBoolean(1)) return
                }
                Thread.sleep(10)
            }
        }
        throw AssertionError("Expected $applicationName queued at the final study-table inventory lock")
    }

    private fun awaitSensorInventoryLock(connection: Connection, applicationName: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM pg_locks locks JOIN pg_stat_activity activity ON activity.pid = locks.pid
                WHERE activity.application_name = ? AND activity.wait_event_type = 'Lock'
                  AND activity.query LIKE 'SELECT chronicle_lock_study_table_for_erasure%'
                  AND locks.relation = 'sensor_data'::regclass
                  AND locks.mode = 'ShareRowExclusiveLock' AND NOT locks.granted
            )
        """.trimIndent()).use { statement ->
            statement.setString(1, applicationName)
            while (System.nanoTime() < deadline) {
                statement.executeQuery().use { rows ->
                    if (rows.next() && rows.getBoolean(1)) return
                }
                Thread.sleep(10)
            }
        }
        throw AssertionError("Expected $applicationName queued at the sensor source-table inventory lock")
    }

    private fun exportLocksAtBarrier(connection: Connection, firstName: String, secondName: String): List<String> =
        connection.prepareStatement("""
            SELECT activity.application_name || ':' || locks.mode
            FROM pg_locks locks JOIN pg_stat_activity activity ON activity.pid = locks.pid
            WHERE activity.application_name IN (?, ?) AND locks.granted
              AND locks.relation = 'export_jobs'::regclass
            ORDER BY activity.application_name, locks.mode
        """.trimIndent()).use { statement ->
            statement.setString(1, firstName)
            statement.setString(2, secondName)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
}
