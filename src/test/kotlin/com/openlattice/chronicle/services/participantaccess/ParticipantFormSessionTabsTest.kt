package com.openlattice.chronicle.services.participantaccess

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID

/** Two form tabs share the host-wide session cookie; each must still submit as itself. */
class ParticipantFormSessionTabsTest {
    companion object {
        private val postgres = ChronicleContractTestSchema.sharedPostgres
        private val hds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        })
        private val service = ParticipantFormAccessService(object : StorageResolver(
            Mockito.mock(DataSourceManager::class.java),
            Mockito.mock(ChronicleStorageConfiguration::class.java),
        ) {
            override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource = hds
        })

        @AfterClass @JvmStatic fun close() = hds.close()
    }

    private fun participant(studyId: UUID, participantId: String) {
        postgres.createConnection("").use { connection ->
            connection.createStatement().use {
                it.execute("INSERT INTO studies (study_id, title) VALUES ('$studyId', 'tabs') ON CONFLICT DO NOTHING")
                it.execute(
                    "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) " +
                        "VALUES ('$studyId', '$participantId', '${UUID.randomUUID()}', 'ENROLLED')",
                )
            }
        }
    }

    private fun open(studyId: UUID, participantId: String, questionnaireId: UUID): ExchangedParticipantFormSession {
        val code = service.createAccessCode(
            studyId, participantId, ParticipantFormKind.QUESTIONNAIRE, questionnaireId, null, null,
            ParticipantAccessCodeIssuerType.RESEARCHER, "tabs-test",
        )
        return checkNotNull(service.exchangeAccessCode(code.accessCode))
    }

    @Test
    fun `the first tab still submits after a second tab replaced the cookie`() {
        val studyId = UUID.randomUUID()
        participant(studyId, "p-1")
        val questionnaireA = UUID.randomUUID()
        val tabA = open(studyId, "p-1", questionnaireA)
        val tabB = open(studyId, "p-1", UUID.randomUUID())
        val cookie = tabB.rawSessionToken // the browser keeps only the newest cookie

        val scopeA = service.resolveSession(cookie, tabA.response.csrfToken, requireCsrf = true)
        assertNotNull(scopeA)
        assertEquals(questionnaireA, scopeA!!.resourceId)
        assertNotNull(service.resolveSession(cookie, tabB.response.csrfToken, requireCsrf = true))
        // A GET from tab A also reads tab A's own scope.
        assertEquals(questionnaireA, service.resolveSession(cookie, tabA.response.csrfToken, false)?.resourceId)
    }

    @Test
    fun `a CSRF token never crosses to another participant or stands in for the cookie`() {
        val studyId = UUID.randomUUID()
        participant(studyId, "p-1")
        participant(studyId, "p-2")
        val mine = open(studyId, "p-1", UUID.randomUUID())
        val theirs = open(studyId, "p-2", UUID.randomUUID())

        assertNull(service.resolveSession(theirs.rawSessionToken, mine.response.csrfToken, requireCsrf = true))
        assertNull(service.resolveSession("x".repeat(64), mine.response.csrfToken, requireCsrf = true))
        assertNull(service.resolveSession(mine.rawSessionToken, "y".repeat(64), requireCsrf = true))
    }
    @Test
    fun pausedAndCompletedParticipantsCannotExchangeCodesOrSubmitExistingSessions() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext()
        listOf("PAUSED", "COLLECTION_COMPLETED", "NOT_ENROLLED").forEach { status ->
            val studyId = UUID.randomUUID()
            participant(studyId, "p-status")
            val session = open(studyId, "p-status", UUID.randomUUID())
            val outstanding = service.createAccessCode(studyId, "p-status", ParticipantFormKind.APP_USAGE,
                null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "pause-test")
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("UPDATE study_participants SET participation_status = ? WHERE study_id = ?").use {
                    it.setString(1, status); it.setObject(2, studyId); it.executeUpdate()
                }
            }
            assertNull(service.exchangeAccessCode(outstanding.accessCode))
            assertNull(service.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
            val request = org.springframework.mock.web.MockHttpServletRequest("POST",
                "/chronicle/v3/survey/$studyId/participant/p-status/questionnaire/${session.response.resourceId}").apply {
                setCookies(jakarta.servlet.http.Cookie(com.openlattice.chronicle.filters.ParticipantFormAccessFilter.SESSION_COOKIE,
                    session.rawSessionToken))
                addHeader(com.openlattice.chronicle.filters.ParticipantFormAccessFilter.CSRF_HEADER, session.response.csrfToken)
            }
            val response = org.springframework.mock.web.MockHttpServletResponse()
            com.openlattice.chronicle.filters.ParticipantFormAccessFilter(service)
                .doFilter(request, response, org.springframework.mock.web.MockFilterChain())
            assertEquals(status, 401, response.status)
        }
    }

    @Test
    fun diarySubmissionsThroughBothTransactionOwnersFitOneConnection() {
        val studyId = UUID.randomUUID()
        val participantId = "single-slot-diary"
        participant(studyId, participantId)
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes()
        try {
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password
                maximumPoolSize = 1; minimumIdle = 0; connectionTimeout = 250
            }).use { pool ->
                val manager = org.mockito.kotlin.mock<DataSourceManager>()
                org.mockito.kotlin.whenever(manager.getDataSource(org.mockito.kotlin.any())).thenReturn(pool)
                org.mockito.kotlin.whenever(manager.getFlavor(org.mockito.kotlin.any())).thenReturn(PostgresFlavor.VANILLA)
                val storage = StorageResolver(manager, ChronicleStorageConfiguration(defaultEventStorage = "default"))
                val member = org.mockito.kotlin.mock<com.hazelcast.core.HazelcastInstance>()
                val stats = org.mockito.kotlin.mock<com.hazelcast.map.IMap<com.openlattice.chronicle.mapstores.stats.ParticipantKey,
                    com.openlattice.chronicle.participants.ParticipantStats>>()
                org.mockito.kotlin.whenever(member.getMap<com.openlattice.chronicle.mapstores.stats.ParticipantKey,
                    com.openlattice.chronicle.participants.ParticipantStats>("PARTICIPANT_STATS")).thenReturn(stats)
                org.mockito.kotlin.whenever(member.getMap<UUID, com.openlattice.chronicle.study.Study>("STUDIES")).thenReturn(org.mockito.kotlin.mock(
                    ))
                val ids = org.mockito.kotlin.mock<com.openlattice.chronicle.ids.HazelcastIdGenerationService>()
                org.mockito.kotlin.whenever(ids.getNextId()).thenAnswer { UUID.randomUUID() }
                val authorization = org.mockito.kotlin.mock<com.openlattice.chronicle.authorization.AuthorizationManager>()
                org.mockito.kotlin.whenever(authorization.checkIfHasPermissions(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                    org.mockito.kotlin.any())).thenReturn(true)
                val audit = org.mockito.kotlin.mock<com.openlattice.chronicle.auditing.AuditingManager>()
                val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(storage, ids, org.mockito.kotlin.mock())
                val studies = com.openlattice.chronicle.services.studies.StudyService(storage, authorization, org.mockito.kotlin.mock(), enrollment,
                    org.mockito.kotlin.mock(), ids, org.mockito.kotlin.mock(), audit, member,
                    com.openlattice.chronicle.mapstores.stats.HazelcastParticipantStatsCache(storage, member), org.mockito.kotlin.mock())
                val diary = com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService(storage, ids, studies)
                val mapper = com.geekbeast.mappers.mappers.ObjectMappers.getJsonMapper()
                val forms = ParticipantFormAccessService(storage)
                val code = forms.createAccessCode(studyId, participantId, ParticipantFormKind.TIME_USE_DIARY, null, null, null,
                    ParticipantAccessCodeIssuerType.RESEARCHER, "one-slot")
                val session = checkNotNull(forms.exchangeAccessCode(code.accessCode))
                val scope = checkNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
                val receipts = ParticipantFormSubmissionReceiptService(storage, mapper)
                receipts.executeWithSubmissionId(scope, ParticipantFormKind.TIME_USE_DIARY, "diary", UUID.randomUUID(), emptyList<String>()) {
                    diary.submitTimeUseDiary(it, studyId, participantId, emptyList())
                }
                val controller = com.openlattice.chronicle.controllers.TimeUseDiaryController(authorization, audit, storage, ids,
                    diary, studies, org.mockito.kotlin.mock(), enrollment, receipts)
                controller.submitTimeUseDiary(studyId, participantId, emptyList())
                assertEquals(0, pool.hikariPoolMXBean.activeConnections)
                pool.connection.use { connection ->
                    connection.prepareStatement("SELECT count(*) FROM time_use_diary_submissions WHERE study_id = ?").use {
                        it.setObject(1, studyId)
                        it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(2L, rows.getLong(1)) }
                    }
                }
                Mockito.verify(stats, Mockito.never()).get(org.mockito.kotlin.any())
            }
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

}
