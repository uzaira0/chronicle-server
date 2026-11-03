package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.contract.seedCandidate

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
import com.openlattice.chronicle.storage.rls.RLSRequestContext
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
    fun `researcher alerts queue only supported SMS jobs that deserialize for delivery`() {
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
                assertEquals(1, queued)
                val jobRows = queuedJobs(connection, studyId)
                assertEquals(1, jobRows.size)
                val mapper = ObjectMappers.newJsonMapper()
                jobRows.forEach { (definition, participantIds) ->
                    assertFalse(mapper.readTree(definition).has("participantId"))
                    assertEquals(0, participantIds)
                    val notification = mapper.readValue(definition, Notification::class.java)
                    assertEquals(DeliveryType.SMS, notification.deliveryType)
                    assertEquals("", notification.participantId)
                    assertEquals(studyId, notification.studyId)
                }
            }
        } finally {
            jobs.shutdown()
        }
    }

    /** Each queued job's definition JSON and the number of participant IDs it names. */
    private fun queuedJobs(connection: java.sql.Connection, studyId: UUID): List<Pair<String, Int>> =
        connection.prepareStatement("SELECT definition, participant_ids FROM jobs WHERE study_id = ?").use {
            it.setObject(1, studyId)
            val rows = it.executeQuery() // closed with its statement
            buildList {
                while (rows.next()) add(rows.getString("definition") to (rows.getArray("participant_ids").array as Array<*>).size)
            }
        }

    /** [read] applied to the first row [sql] returns for [studyId], its only bind parameter. */
    private fun <T> firstStudyRow(studyId: UUID, sql: String, read: (java.sql.ResultSet) -> T): T =
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.prepareStatement(sql).use {
                it.setObject(1, studyId)
                it.executeQuery().use { rows -> org.junit.Assert.assertTrue(rows.next()); read(rows) }
            }
        }

    @Test
    fun `participant identity in a researcher message is scoped deleted and fenced on withdrawal`() = RLSRequestContext.withSystemContext {
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
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'identity scope')").use {
                    it.setObject(1, study); it.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) " +
                        "VALUES (?, ?, ?, 'ENROLLED')",
                ).use { it.setObject(1, study); it.setString(2, participant); it.setObject(3, seedCandidate(connection)); it.executeUpdate() }
            }
            val notice = ResearcherNotification(emptySet(), setOf("+15555550100"),
                NotificationType.PASSIVE_DATA_COLLECTION_COMPLIANCE, EnumSet.of(DeliveryType.SMS),
                "Compliance", "Participant $participant needs attention", participantId = participant)
            fun enqueue() = storage.getPlatformStorage().connection.use { connection ->
                service.sendResearcherNotifications(connection, study, listOf(notice), false,
                    Principal(PrincipalType.USER, "scope-test"))
            }
            assertEquals(1, enqueue())
            assertEquals(participant, firstStudyRow(study, "SELECT participant_id FROM notifications WHERE study_id = ?") {
                it.getString(1)
            })
            assertEquals(listOf(participant), firstStudyRow(study, "SELECT participant_ids FROM jobs WHERE study_id = ?") {
                (it.getArray(1).array as Array<*>).toList()
            })
            val orchestrator = com.openlattice.chronicle.services.delete.DataDeletionOrchestrator(storage, mock())
            val operation = orchestrator.quarantineParticipant(study, participant,
                com.openlattice.chronicle.services.delete.DataDeletionMode.WITHDRAW_AND_ERASE, "scope-test", UUID.randomUUID())
            assertEquals("COMPLETED", orchestrator.eraseNow(operation, study, "test-owner").status)
            assertEquals(0L, firstStudyRow(study, "SELECT count(*) FROM notifications WHERE study_id = ?") { it.getLong(1) })
            assertEquals(0L, firstStudyRow(study, "SELECT count(*) FROM jobs WHERE study_id = ?") { it.getLong(1) })
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
                    it.setString(3, """{"@type":"Notification","participantId":""}""")
                    it.executeUpdate()
                }
            }
            assertEquals("23514", failure.sqlState)
        }
    }
}
