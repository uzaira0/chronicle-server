package com.openlattice.chronicle.storage

import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.contract.seedEnrolledParticipant

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.NetworkUsageType
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageRead
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.studies.AuthorizedHealthScope
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRecord
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRowKind
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SweepRevisionRegressionTest {
    private val postgres get() = ChronicleContractTestSchema.sharedPostgres
    private val participant = "revision-subject"
    private val old = OffsetDateTime.parse("2026-01-01T00:00:00Z")
    private val fresh = old.plusDays(10)

    private fun pool(name: String = "revision"): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password
        maximumPoolSize = 1; minimumIdle = 0; connectionTimeout = 500
        addDataSourceProperty("ApplicationName", name)
        connectionInitSql = "SET statement_timeout = '3s'"
    })

    private fun resolver(platform: HikariDataSource, event: HikariDataSource = platform): StorageResolver {
        val manager = mock<DataSourceManager>()
        whenever(manager.getDataSource(any())).thenAnswer { if (it.getArgument<String>(0) == "default") event else platform }
        whenever(manager.getFlavor(any())).thenReturn(PostgresFlavor.VANILLA)
        return StorageResolver(manager, ChronicleStorageConfiguration(platformStorage = "platform", defaultEventStorage = "default"))
    }

    private fun seed(): UUID = UUID.randomUUID().also { study ->
        postgres.createConnection("").use { connection ->
            connection.createStatement().use {
                it.execute("INSERT INTO studies (study_id, title, storage) VALUES ('$study', 'revision', 'default')")
            }
            seedEnrolledParticipant(connection, study, participant)
        }
    }

    private fun cutoff(study: UUID) {
        postgres.createConnection("").use { c -> c.createStatement().use {
            it.execute("""
                INSERT INTO participant_purge_cutoffs VALUES
                ('$study', chronicle_participant_block_token('$study'::uuid, '$participant'), '${old.plusDays(1)}')
            """.trimIndent())
        } }
    }

    private fun count(study: UUID, table: String): Int = postgres.createConnection("").use { c ->
        c.createStatement().use { it.executeQuery("SELECT count(*) FROM $table WHERE study_id::text = '$study'").use { r -> r.next(); r.getInt(1) } }
    }

    @Test fun observationWindowsFenceRereadHealthNetworkAndScreenTime() {
        val study = seed(); cutoff(study)
        pool().use { p ->
            val storage = resolver(p)
            RLSRequestContext.withSystemContext {
                val health = AndroidHealthMetricEvent("old-health", fresh, "UTC", HealthMetricType.STEPS, 1.0, "count",
                    old.toInstant().toEpochMilli(), old.toInstant().toEpochMilli())
                HealthMetricsUploadService(storage).upload(
                    AuthorizedHealthScope(study, participant, UUID.randomUUID(), HealthConnectRecordType.entries.toSet()), listOf(health,
                    health.copy(id = "fresh-health", startMillis = fresh.toInstant().toEpochMilli(), endMillis = fresh.toInstant().toEpochMilli())))
                val network = AndroidAppNetworkUsageEvent("old-network", fresh, "UTC", "example.app", NetworkUsageType.WIFI, 1, 2,
                    old.toInstant().toEpochMilli(), old.plusHours(1).toInstant().toEpochMilli())
                AppNetworkUsageUploadService(storage).upload(study, participant, listOf(network,
                    network.copy(id = "fresh-network", bucketStartMillis = fresh.toInstant().toEpochMilli(), bucketEndMillis = fresh.plusHours(
                        1).toInstant().toEpochMilli())))
                // Captured after the cutoff, but the first window was measured before it.
                val screen = screenTime(start = old, capturedAt = fresh)
                ScreenTimeUsageUploadService(storage).upload(study, participant, UUID.randomUUID(), screenTimeEnvelope(study,
                    listOf(screen, screen.copy(id = UUID.randomUUID(), observationStart = fresh, observationEnd = fresh.plusHours(1)))))
            }
        }
        assertEquals(1, count(study, "health_metrics")); assertEquals(1, count(study, "app_network_usage"))
        assertEquals(1, count(study, "ios_screen_time_usage"))
    }

    @Test fun statsMergeHoldsTheAdmissionFence() {
        val study = seed()
        pool().use { p ->
            val storage = resolver(p); val studies = mock<StudyManager>()
            whenever(studies.insertOrUpdateParticipantStats(any())).thenAnswer {
                val owner = PinnedPlatformConnection.owningConnection(storage.getPlatformStorage())
                assertNotNull("Stats merge must join the upload admission transaction", owner)
                assertFalse(owner!!.autoCommit)
                postgres.createConnection("").use { other -> other.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_try_advisory_xact_lock(hashtextextended('chronicle-deletion:$study', 0))").use { r -> r.next();
                        assertFalse(r.getBoolean(1)) }
                } }
                null
            }
            val upload = AppDataUploadService(storage, studies)
            RLSRequestContext.withSystemContext {
                upload.uploadAndroidUsageEvents(study, participant, UUID.randomUUID(), listOf(
                    com.openlattice.chronicle.android.ChronicleUsageEvent(studyId = study, participantId = participant,
                        appPackageName = "example.app", interactionType = "Move to Foreground", timestamp = fresh,
                        timezone = "UTC", user = "", applicationLabel = "Example")))
            }
            verify(studies).insertOrUpdateParticipantStats(any())
        }
    }

    @Test fun sharedRestoreConnectionRoutesColdStudiesWithoutAnotherCheckout() {
        val study = seed()
        pool().use { p ->
            val storage = resolver(p); val member = mock<HazelcastInstance>(); val studies = mock<IMap<UUID, Study>>()
            whenever(member.getMap<UUID, Study>("STUDIES")).thenReturn(studies)
            whenever(studies.executeOnKey(any(), any<StudyStorageRead>())).thenAnswer {
                p.connection.use { "default" }
            }
            storage.setStudyStorage(member)
            val source = storage.getPlatformStorage()
            source.connection.use { owner -> PinnedPlatformConnection.sharing(source, owner) {
                assertEquals("default", storage.resolveDataSourceName(study))
            } }
            verify(studies, never()).executeOnKey(any(), any<StudyStorageRead>())
        }
    }

    @Test fun colocatedDistinctPoolMaterializationPassesAQueuedEraser() {
        val study = seed(); val executor = Executors.newSingleThreadExecutor()
        pool("revision-owner").use { p -> pool("revision-destination").use { e ->
            val storage = resolver(p, e)
            storage.requireDefaultDeletionStorageColocated()
            RLSRequestContext.withSystemContext {
                val source = storage.getPlatformStorage()
                source.connection.use { owner ->
                    owner.autoCommit = false
                    PinnedPlatformConnection.pinning(source, owner) {
                        DeletionStudyFence.shared(owner, study)
                        val eraser = executor.submit { postgres.createConnection("").use { c -> c.createStatement().use {
                            it.execute("SET application_name = 'revision-eraser-$study'")
                            it.execute("SELECT pg_advisory_xact_lock(hashtextextended('chronicle-deletion:$study', 0))")
                        } } }
                        try {
                            awaitBlocked("revision-eraser-$study")
                            storage.getEventStorageWithFlavor().connection.use { destination -> destination.createStatement().use {
                                it.execute("""INSERT INTO chronicle_usage_events (study_id, participant_id, app_package_name, interaction_type,
                                        event_type, event_timestamp, timezone, username, application_label)
                                    VALUES ('$study', '$participant', 'com.example', 'Move to Foreground', 1, '$fresh', 'UTC', '', 'Example')""")
                            } }
                        } finally { owner.commit(); eraser.get(5, TimeUnit.SECONDS) }
                    }
                }
            }
        } }
        executor.shutdownNow()
        assertEquals(1, count(study, "chronicle_usage_events"))
    }

    @Test fun drainCensusFencesAtMostOneBatchOfEligibleStudies() {
        val studies = List(300) { UUID.randomUUID() }
        postgres.createConnection("").use { c -> c.createStatement().use { s -> studies.forEach {
            s.execute(
                "INSERT INTO upload_buffer (study_id, participant_id, data, uploaded_at, upload_type, random_device_id) " +
                    "VALUES ('$it', '$participant', '[]', '2000-01-01', 'Android', '${UUID.randomUUID()}')")
        } } }
        try {
            postgres.createConnection("").use { c -> c.autoCommit = false
                try { assertTrue("Preparatory fences must be bounded by the 128-row claim", DeletionTableLockOrder.lockDrain(c,
                    "chronicle_usage_events").size <= 128) }
                finally { c.rollback() }
            }
        } finally { postgres.createConnection("").use { c -> c.createStatement().use { s -> studies.forEach { s.execute(
            "DELETE FROM upload_buffer WHERE study_id = '$it'") } } } }
    }

    @Test fun screenTimeUploadMergesEveryCaptureDayIntoOneStatsRow() {
        val study = seed()
        pool().use { p ->
            val records = List(100) { index -> screenTime(start = fresh.plusDays(index.toLong()), capturedAt = fresh.plusDays(index.toLong())) }
            RLSRequestContext.withSystemContext {
                ScreenTimeUsageUploadService(resolver(p)).upload(study, participant, UUID.randomUUID(), screenTimeEnvelope(study, records))
            }
        }
        postgres.createConnection("").use { c -> c.createStatement().use {
            it.executeQuery("SELECT cardinality(ios_unique_dates) FROM participant_stats WHERE study_id = '$study'").use { r ->
                assertTrue(r.next()); assertEquals(100, r.getInt(1)); assertFalse("One stats row per participant", r.next())
            }
        } }
    }

    @Test fun notificationEnqueuePassesAQueuedFinalizerInGlobalTableOrder() {
        val study = seed(); val executor = Executors.newFixedThreadPool(2)
        pool("revision-notifier-$study").use { p ->
            val storage = resolver(p)
            val ids = mock<com.openlattice.chronicle.ids.HazelcastIdGenerationService>()
            whenever(ids.getNextId()).thenAnswer { UUID.randomUUID() }
            val jobs = com.openlattice.chronicle.services.jobs.JobService(ids, storage, mock())
            val service = com.openlattice.chronicle.services.notifications.NotificationService(storage, mock(), mock(), mock(), mock(), jobs, ids,
                mock(), mock())
            try {
                postgres.createConnection("").use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use { it.execute("LOCK TABLE notifications IN SHARE ROW EXCLUSIVE MODE") }
                    val notification = executor.submit {
                        p.connection.use { c ->
                            c.autoCommit = false
                            try {
                                service.sendResearcherNotifications(c, study, listOf(
                                    com.openlattice.chronicle.services.notifications.ResearcherNotification(
                                        setOf("test@example.org"), emptySet(),
                                            com.openlattice.chronicle.notifications.NotificationType.PASSIVE_DATA_COLLECTION_COMPLIANCE,
                                        java.util.EnumSet.of(com.openlattice.chronicle.notifications.DeliveryType.EMAIL), "Status", "Body")), false,
                                    com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER,
                                        "revision"))
                                c.commit()
                            } finally { c.rollback() }
                        }
                    }
                    try {
                        awaitBlocked("revision-notifier-$study")
                        val finalizer = executor.submit {
                            postgres.createConnection("").use { c ->
                                c.autoCommit = false
                                c.createStatement().use { it.execute("SET application_name = 'revision-finalizer-$study'")
                                    it.execute("SET statement_timeout = '3s'")
                                    listOf("jobs", "notifications").sortedWith(DeletionTableLockOrder.comparator).forEach { table ->
                                        it.execute("LOCK TABLE $table IN SHARE ROW EXCLUSIVE MODE")
                                    }
                                }
                                c.commit()
                            }
                        }
                        awaitBlocked("revision-finalizer-$study")
                        blocker.commit()
                        notification.get(5, TimeUnit.SECONDS); finalizer.get(5, TimeUnit.SECONDS)
                    } finally { blocker.rollback() }
                }
            } finally { jobs.shutdown(); executor.shutdownNow() }
        }
    }

    @Test fun statsInvalidationOccursOnceAfterTheOwnerCommits() {
        val study = seed()
        pool().use { p ->
            val map = mock<IMap<com.openlattice.chronicle.mapstores.stats.ParticipantKey, ParticipantStats>>()
            val invalidated = java.util.concurrent.CountDownLatch(1)
            whenever(map.evict(any())).thenAnswer {
                assertEquals("Invalidation must observe committed statistics", 1, count(study, "participant_stats"))
                invalidated.countDown(); true
            }
            val cache = com.openlattice.chronicle.mapstores.stats.HazelcastParticipantStatsCache(map, { false }, {}, p)
            p.connection.use { c ->
                c.autoCommit = false
                PinnedPlatformConnection.committing(p, c) {
                    repeat(3) { index -> cache.merge(ParticipantStats(study, participant,
                        iosUniqueDates = setOf(fresh.plusDays(index.toLong()).toLocalDate()))) }
                    assertFalse("No invalidation may run before commit", invalidated.await(250, TimeUnit.MILLISECONDS))
                    verify(map, never()).evict(any())
                }
            }
            assertTrue(invalidated.await(3, TimeUnit.SECONDS))
            verify(map, Mockito.timeout(1000).times(1)).evict(any())
        }
    }

    private fun screenTime(start: OffsetDateTime, capturedAt: OffsetDateTime) = ScreenTimeUsageRecord(
        id = UUID.randomUUID(), rowKind = ScreenTimeUsageRowKind.application,
        capturedAt = capturedAt, observationStart = start, observationEnd = start.plusHours(1), timezoneIdentifier = "UTC",
        appName = "Example", durationSeconds = 60,
    )

    private fun screenTimeEnvelope(study: UUID, records: List<ScreenTimeUsageRecord>) = ScreenTimeUsageEnvelope(
        randomDeviceId = "device", studyId = study.toString(), participantId = participant, generatedAt = fresh, records = records,
    )

    private fun awaitBlocked(name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        postgres.createConnection("").use { c -> c.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')").use { s ->
            s.setString(1, name)
            while (System.nanoTime() < deadline) { s.executeQuery().use { r -> r.next(); if (r.getBoolean(1)) return }; Thread.sleep(10) }
        } }
        fail("Expected queued eraser")
    }
}
