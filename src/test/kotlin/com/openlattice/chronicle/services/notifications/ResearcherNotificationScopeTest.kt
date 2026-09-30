package com.openlattice.chronicle.services.notifications

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.sql.SQLException
import java.util.EnumSet
import java.util.UUID

class ResearcherNotificationScopeTest {
    @Test
    fun `researcher email and SMS create study scoped jobs that deserialize for delivery`() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val studyId = UUID.randomUUID()
        val ids = mock<HazelcastIdGenerationService>()
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        val storageResolver = mock<StorageResolver>()
        val auditing = mock<AuditingManager>()
        val jobs = JobService(ids, storageResolver, auditing)
        val service = NotificationService(
            storageResolver, mock<AuthorizationManager>(), mock<EnrollmentManager>(),
            mock<CandidateService>(), mock<StudyService>(), jobs, ids, mock<TwilioService>(), auditing,
        )
        try {
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'researcher scope')").use {
                    it.setObject(1, studyId)
                    it.executeUpdate()
                }
                val queued = service.sendResearcherNotifications(
                    connection, studyId, listOf(ResearcherNotification(
                        emails = setOf("researcher@example.org"), phoneNumbers = setOf("+15555550100"),
                        notificationType = NotificationType.PASSIVE_DATA_COLLECTION_COMPLIANCE,
                        deliveryType = EnumSet.of(DeliveryType.EMAIL, DeliveryType.SMS),
                        subject = "Study status", message = "Collection needs attention",
                    )), false, Principal(PrincipalType.USER, "researcher-scope-test"),
                )
                assertEquals(2, queued)
                connection.prepareStatement("SELECT definition, participant_ids FROM jobs WHERE study_id = ?").use {
                    it.setObject(1, studyId)
                    it.executeQuery().use { rows ->
                        var count = 0
                        val mapper = ObjectMappers.newJsonMapper()
                        while (rows.next()) {
                            count++
                            val definition = mapper.readTree(rows.getString("definition"))
                            assertFalse(definition.has("participantId"))
                            assertEquals(0, (rows.getArray("participant_ids").array as Array<*>).size)
                            val notification = mapper.readValue(rows.getString("definition"), Notification::class.java)
                            assertEquals("", notification.participantId)
                            assertEquals(studyId, notification.studyId)
                        }
                        assertEquals(2, count)
                    }
                }
            }
        } finally {
            jobs.shutdown()
        }
    }

    @Test
    fun `participant identity in a researcher message is scoped deleted and fenced on withdrawal`() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val study = UUID.randomUUID()
        val participant = "researcher-erasure-subject"
        val pool = com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password
            maximumPoolSize = 2; minimumIdle = 0
        })
        val manager = mock<com.geekbeast.jdbc.DataSourceManager>()
        Mockito.`when`(manager.getDataSource(org.mockito.kotlin.any())).thenReturn(pool)
        Mockito.`when`(manager.getFlavor(org.mockito.kotlin.any())).thenReturn(com.geekbeast.configuration.postgres.PostgresFlavor.VANILLA)
        val storage = StorageResolver(manager, com.openlattice.chronicle.configuration.ChronicleStorageConfiguration(defaultEventStorage = "default"))
        val ids = mock<HazelcastIdGenerationService>()
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        val jobs = JobService(ids, storage, mock())
        val service = NotificationService(storage, mock(), mock(), mock(), mock(), jobs, ids, mock(), mock())
        try {
            postgres.createConnection("").use { connection -> connection.createStatement().use {
                it.execute("INSERT INTO studies (study_id, title) VALUES ('$study', 'identity scope')")
                it.execute("INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES ('$study', '$participant', '${UUID.randomUUID()}', 'ENROLLED')")
            } }
            val notice = ResearcherNotification(emptySet(), setOf("+15555550100"),
                NotificationType.PASSIVE_DATA_COLLECTION_COMPLIANCE, EnumSet.of(DeliveryType.SMS),
                "Compliance", "Participant $participant needs attention", participantId = participant)
            fun enqueue() = storage.getPlatformStorage().connection.use { connection ->
                service.sendResearcherNotifications(connection, study, listOf(notice), false,
                    Principal(PrincipalType.USER, "scope-test"))
            }
            assertEquals(1, enqueue())
            postgres.createConnection("").use { connection -> connection.createStatement().use {
                it.executeQuery("SELECT participant_id FROM notifications WHERE study_id = '$study'").use { rows ->
                    org.junit.Assert.assertTrue(rows.next()); assertEquals(participant, rows.getString(1))
                }
                it.executeQuery("SELECT participant_ids FROM jobs WHERE study_id = '$study'").use { rows ->
                    org.junit.Assert.assertTrue(rows.next()); assertEquals(listOf(participant), (rows.getArray(1).array as Array<*>).toList())
                }
            } }
            val orchestrator = com.openlattice.chronicle.services.delete.DataDeletionOrchestrator(storage, mock())
            val operation = orchestrator.quarantineParticipant(study, participant,
                com.openlattice.chronicle.services.delete.DataDeletionMode.WITHDRAW_AND_ERASE, "scope-test", UUID.randomUUID())
            postgres.createConnection("").use { connection -> connection.createStatement().use {
                it.execute("UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = '$operation'")
            } }
            assertEquals(1, orchestrator.processDueOperations(1))
            postgres.createConnection("").use { connection -> connection.createStatement().use {
                for (table in listOf("notifications", "jobs")) it.executeQuery("SELECT count(*) FROM $table WHERE study_id = '$study'").use { rows ->
                    org.junit.Assert.assertTrue(rows.next()); assertEquals(0L, rows.getLong(1))
                }
            } }
            assertEquals("55000", assertThrows(SQLException::class.java) { enqueue() }.sqlState)
        } finally { jobs.shutdown(); pool.close() }
    }

    @Test
    fun `participant scope validation remains strict for malformed participant jobs`() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        postgres.createConnection("").use { connection ->
            val failure = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    "INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id, status, " +
                        "contact, definition, message, deleted_rows) VALUES (?, ?, 'USER', 'scope-test', " +
                        "'PENDING', '', ?::jsonb, '', 0)",
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, UUID.randomUUID())
                    it.setString(3, """{"@type":"DeleteParticipantUsageData","participantId":""}""")
                    it.executeUpdate()
                }
            }
            assertEquals("23514", failure.sqlState)
        }
    }
}
