package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.data.ParticipationStatus
import org.springframework.web.context.request.RequestContextHolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.mapstores.stats.HazelcastParticipantStatsCache
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.controllers.TimeUseDiaryController
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockFilterChain
import jakarta.servlet.http.Cookie
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.exceptions.CollectionUnavailableException

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.mapstores.stats.ParticipantKey
import com.openlattice.chronicle.participants.ParticipantStats
import com.hazelcast.map.IMap
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/** Two form tabs share the host-wide session cookie; each must still submit as itself. */
class ParticipantFormSessionTabsTest {
    companion object {
        private val SHARED_DEVICES = ObjectMappers.newJsonMapper().writeValueAsString(StudySettings(mapOf(
            StudySettingType.DataCollection to AndroidDataCollectionSetting(
                mapOf(CollectionModuleId.USER_IDENTIFICATION to CollectionModuleSetting(enabled = true)),
            ),
        )))
        private const val INACTIVE_ACCESS_MESSAGE = "Study access is unavailable. Ask your study team to check your participation."
        private val postgres = ChronicleContractTestSchema.sharedPostgres
        private val hds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        })
        private val service = participantFormAccessServiceForTest(object : StorageResolver(
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
                // A shared-device study (user identification on), so the app-usage survey runs too.
                it.execute("INSERT INTO studies (study_id, title, settings, modules) VALUES ('$studyId', 'tabs', '$SHARED_DEVICES'::jsonb, " +
                    "jsonb_build_object('${StudyFeature.CHRONICLE_DATA_COLLECTION.name}', '{}'::jsonb)) ON CONFLICT DO NOTHING")
                val candidate = com.openlattice.chronicle.contract.seedCandidate(connection)
                it.execute(
                    "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) " +
                        "VALUES ('$studyId', '$participantId', '$candidate', '${ParticipationStatus.ENROLLED.name}')",
                )
            }
        }
    }

    private fun open(
        studyId: UUID,
        participantId: String,
        questionnaireId: UUID?,
        formKind: ParticipantFormKind = ParticipantFormKind.QUESTIONNAIRE,
    ): ExchangedParticipantFormSession {
        val code = issue(studyId, participantId, questionnaireId, formKind)
        return checkNotNull(service.exchangeAccessCode(code.accessCode))
    }

    private fun issue(studyId: UUID, participantId: String, questionnaireId: UUID?, formKind: ParticipantFormKind) =
        service.createAccessCode(
            studyId, participantId, formKind, questionnaireId, null, null,
            ParticipantAccessCodeIssuerType.RESEARCHER, "tabs-test",
        )

    @Test
    fun researcherDiaryLinksForTodayAndYesterdayStayValidTogether() {
        val studyId = UUID.randomUUID()
        val participantId = "diary-days"
        participant(studyId, participantId)
        val today = LocalDate.now()
        fun diary(day: LocalDate) = service.createAccessCode(
            studyId, participantId, ParticipantFormKind.TIME_USE_DIARY, null, day, null,
            ParticipantAccessCodeIssuerType.RESEARCHER, "tabs-test",
        )
        val todayCode = diary(today)
        val yesterdayCode = diary(today.minusDays(1))
        val reissuedToday = diary(today)

        assertNotNull("another day's link must not revoke this one", service.exchangeAccessCode(yesterdayCode.accessCode))
        assertNotNull(service.exchangeAccessCode(reissuedToday.accessCode))
        assertNull("reissuing the same day still replaces the earlier link", service.exchangeAccessCode(todayCode.accessCode))
    }

    @Test
    fun anExchangeRetryIsBoundedWithoutRevokingTheEstablishedSession() {
        val studyId = UUID.randomUUID()
        val participantId = "bounded-exchange"
        participant(studyId, participantId)
        val code = issue(studyId, participantId, UUID.randomUUID(), ParticipantFormKind.QUESTIONNAIRE)
        val attempt = UUID.randomUUID()
        val first = checkNotNull(service.exchangeAccessCode(code.accessCode, attempt))
        val predictable = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ParticipantSessionSecrets.sha256("session\u0000${code.accessCode}\u0000$attempt"),
        )
        org.junit.Assert.assertNotEquals("The invitation and attempt must not reveal the session credential", predictable, first.rawSessionToken)
        assertEquals(first.rawSessionToken, checkNotNull(service.exchangeAccessCode(code.accessCode, attempt)).rawSessionToken)
        hds.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE participant_form_access_codes SET exchanged_at = now() - (? * interval '1 second') WHERE token_hash = ?",
            ).use {
                it.setLong(1, ParticipantFormAccessService.FORM_EXCHANGE_REPLAY_WINDOW.seconds + 1)
                it.setBytes(2, ParticipantSessionSecrets.sha256(code.accessCode))
                it.executeUpdate()
            }
        }
        assertNull(service.exchangeAccessCode(code.accessCode, attempt))
        assertNotNull(service.resolveSession(first.rawSessionToken, null, false))
    }

    @Test
    fun resolverRejectsExpiredSessionsAndDeletedIssuingCodes() {
        for (field in listOf("absoluteExpiresAt", "accessCodeId")) {
            val studyId = UUID.randomUUID()
            participant(studyId, field)
            val session = open(studyId, field, UUID.randomUUID())
            val scope = checkNotNull(service.resolveSession(session.rawSessionToken, null, false))
            postgres.createConnection("").use { connection ->
                val sql = if (field == "absoluteExpiresAt") {
                    "UPDATE participant_form_sessions SET absolute_expires_at = now() - interval '1 second' WHERE access_code_id = ?"
                } else "DELETE FROM participant_form_access_codes WHERE access_code_id = ?"
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, scope.accessCodeId)
                    assertTrue(statement.executeUpdate() > 0)
                }
            }
            assertNull(field, service.resolveSession(session.rawSessionToken, null, false))
        }
    }

    @Test
    fun anonymousRequestPoolCanResolveOnlyThePresentedParticipantCredential() {
        val studyId = UUID.randomUUID()
        val participantId = "anonymous-pool-session"
        participant(studyId, participantId)
        val session = open(studyId, participantId, UUID.randomUUID())
        val scoped = participantFormAccessServiceForTest(object : StorageResolver(
            Mockito.mock(DataSourceManager::class.java),
            Mockito.mock(ChronicleStorageConfiguration::class.java),
        ) {
            override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource =
                RLSDataSources.wrapIfRequestScoped(hds)
        })
        RLSRequestContext.clear()
        assertNotNull(scoped.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
        assertNull(scoped.resolveSession("x".repeat(64), null, false))
        assertNull(RLSRequestContext.current())
    }

    @Test
    fun exactReplayRecoversTheCommittedSessionAndCookieWithoutAnotherWrite() {
        val studyId = UUID.randomUUID()
        val participantId = "lost-exchange"
        participant(studyId, participantId)
        val code = service.createAccessCode(studyId, participantId, ParticipantFormKind.APP_USAGE, null, null, null,
            ParticipantAccessCodeIssuerType.RESEARCHER, "lost-response")
        val attemptId = UUID.randomUUID()
        val committed = checkNotNull(service.exchangeAccessCode(code.accessCode, attemptId))
        val replay = checkNotNull(service.exchangeAccessCode(code.accessCode, attemptId))
        assertEquals(committed.rawSessionToken, replay.rawSessionToken)
        assertEquals(committed.response.csrfToken, replay.response.csrfToken)
        assertEquals(committed.response.expiresAt.toInstant(), replay.response.expiresAt.toInstant())
        assertNull(service.exchangeAccessCode(code.accessCode, UUID.randomUUID()))
        val recovered = checkNotNull(service.recoverSession(committed.rawSessionToken))
        assertEquals(committed.response.csrfToken, recovered.csrfToken)
        assertNotNull(service.resolveSession(committed.rawSessionToken, recovered.csrfToken, true))
        assertNull(service.recoverSession("x".repeat(64)))
    }

    @Test
    fun recurringQuestionnaireKeepsOneReceiptPerAuthorizedDate() {
        val studyId = UUID.randomUUID()
        val participantId = "recurring-questionnaire"
        participant(studyId, participantId)
        val session = open(studyId, participantId, UUID.randomUUID())
        val scope = checkNotNull(service.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
        val storage = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenReturn(hds)
        val receipts = ParticipantFormSubmissionReceiptService(storage, ObjectMappers.newJsonMapper())
        val payload = mapOf("answer" to "unchanged")
        var writes = 0
        val dates = listOf(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-18"))
        for (day in dates) {
            val occurrence = SubmissionReceiptScope(
                scope.copy(logicalDate = day), scope.formKind, "questionnaire:${scope.resourceId}", UUID.randomUUID(),
            )
            val first = receipts.executeWithSubmissionId(occurrence, payload) { writes++; UUID.randomUUID() }
            assertTrue("Each occurrence must persist", !first.replayed)
            val replay = receipts.executeWithSubmissionId(occurrence.copy(idempotencyKey = UUID.randomUUID()), payload) {
                error("Retry must replay its own occurrence")
            }
            assertTrue(replay.replayed)
            assertEquals(first.value, replay.value)
        }
        assertEquals(dates.size, writes)
    }

    @Test
    fun freshClientKeysCannotDuplicateAnyCompletedFormResource() {
        val studyId = UUID.randomUUID()
        val participantId = "fresh-client"
        participant(studyId, participantId)
        val storage = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenReturn(hds)
        val receipts = ParticipantFormSubmissionReceiptService(storage, ObjectMappers.newJsonMapper())
        for (kind in ParticipantFormKind.entries.filter { it != ParticipantFormKind.ENROLLMENT && it != ParticipantFormKind.PORTAL }) {
            val resourceId = if (kind == ParticipantFormKind.QUESTIONNAIRE) UUID.randomUUID() else null
            val session = open(studyId, participantId, resourceId, kind)
            val scope = checkNotNull(service.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
                .copy(logicalDate = LocalDate.now())
            val payload = mapOf("answer" to "first")
            val first = receipts.executeWithSubmissionId(
                SubmissionReceiptScope(scope, kind, kind.name, UUID.randomUUID()), payload,
            ) { UUID.randomUUID() }
            val reopened = open(studyId, participantId, resourceId, kind)
            val reopenedScope = checkNotNull(service.resolveSession(reopened.rawSessionToken, reopened.response.csrfToken, true))
                .copy(logicalDate = scope.logicalDate)
            val replay = receipts.executeWithSubmissionId(
                SubmissionReceiptScope(reopenedScope, kind, kind.name, UUID.randomUUID()), payload,
            ) { UUID.randomUUID() }
            assertTrue(replay.replayed)
            assertEquals(first.value, replay.value)
            assertThrows(ParticipantSubmissionConflictException::class.java) {
                receipts.executeWithSubmissionId(
                    SubmissionReceiptScope(reopenedScope, kind, kind.name, UUID.randomUUID()), mapOf("answer" to "changed"),
                ) {
                    error("A changed completed resource cannot be stored again")
                }
            }
        }
    }

    @Test
    fun researcherReissueReplacesEveryFormKindAcrossIssuers() {
        val studyId = UUID.randomUUID()
        val participantId = "reissue"
        participant(studyId, participantId)
        for (kind in ParticipantFormKind.entries) {
            val resourceId = if (kind == ParticipantFormKind.QUESTIONNAIRE) UUID.randomUUID() else null
            val first = service.createAccessCode(studyId, participantId, kind, resourceId, null, null,
                ParticipantAccessCodeIssuerType.RESEARCHER, "researcher-one")
            service.createAccessCode(studyId, participantId, kind, resourceId, null, null,
                ParticipantAccessCodeIssuerType.RESEARCHER, "researcher-two")
            assertSingleLiveResearcherCode(studyId, kind)
            assertNull(service.exchangeAccessCode(first.accessCode))
        }
    }

    private fun assertSingleLiveResearcherCode(studyId: UUID, kind: ParticipantFormKind) {
        postgres.createConnection("").use { connection ->
                connection.prepareStatement("""
                    SELECT count(*) FROM participant_form_access_codes
                    WHERE study_id = ? AND form_kind = ? AND revoked_at IS NULL AND exchanged_at IS NULL
                """.trimIndent()).use {
                    it.setObject(1, studyId); it.setString(2, kind.name)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(kind.name, 1L, rows.getLong(1)) }
                }
            }
    }

    private fun setStudyLifecycle(studyId: UUID, status: String) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("UPDATE studies SET lifecycle_status = ? WHERE study_id = ?").use {
                it.setString(1, status); it.setObject(2, studyId); it.executeUpdate()
            }
        }
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
        SecurityContextHolder.clearContext()
        listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED, ParticipationStatus.NOT_ENROLLED)
            .map { it.name }.forEach { status ->
            val studyId = UUID.randomUUID()
            participant(studyId, "p-status")
            val session = open(studyId, "p-status", UUID.randomUUID())
            val outstanding = service.createAccessCode(studyId, "p-status", ParticipantFormKind.TIME_USE_DIARY,
                null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "pause-test")
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("UPDATE study_participants SET participation_status = ? WHERE study_id = ?").use {
                    it.setString(1, status); it.setObject(2, studyId); it.executeUpdate()
                }
            }
            val error = assertThrows(ResponseStatusException::class.java) {
                service.exchangeAccessCode(outstanding.accessCode)
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertEquals(INACTIVE_ACCESS_MESSAGE, error.reason)
            assertNull(service.exchangeAccessCode("x".repeat(64)))
            assertNull(service.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
            val request = MockHttpServletRequest("POST",
                "/chronicle/v3/survey/$studyId/participant/p-status/questionnaire/${session.response.resourceId}").apply {
                setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE,
                    session.rawSessionToken))
                addHeader(ParticipantFormAccessFilter.CSRF_HEADER, session.response.csrfToken)
            }
            val response = MockHttpServletResponse()
            ParticipantFormAccessFilter(service)
                .doFilter(request, response, MockFilterChain())
            assertEquals(status, 401, response.status)
        }
    }

    @Test
    fun archivedStudyRejectsLiveCodeWithoutConsumingItOrDisclosingInvalidCodes() {
        val studyId = UUID.randomUUID()
        participant(studyId, "p-archive")
        val used = service.createAccessCode(studyId, "p-archive", ParticipantFormKind.TIME_USE_DIARY,
            null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "used-test")
        assertNotNull(service.exchangeAccessCode(used.accessCode))
        val live = service.createAccessCode(studyId, "p-archive", ParticipantFormKind.TIME_USE_DIARY,
            null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "archive-test")
        setStudyLifecycle(studyId, "ARCHIVED")
        val error = assertThrows(ResponseStatusException::class.java) { service.exchangeAccessCode(live.accessCode) }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals(INACTIVE_ACCESS_MESSAGE, error.reason)
        assertNull(service.exchangeAccessCode("x".repeat(64)))
        assertNull(service.exchangeAccessCode(used.accessCode))
        setStudyLifecycle(studyId, "ACTIVE")
        assertNotNull(service.exchangeAccessCode(live.accessCode))
    }

    @Test
    fun studyOnlyRegistrationGateNamesTheInactiveStudy() {
        val studyId = UUID.randomUUID()
        participant(studyId, "p-gate")
        setStudyLifecycle(studyId, "ARCHIVED")
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            val error = assertThrows(CollectionUnavailableException::class.java) {
                ParticipantCollectionGate(connection).requireActive(studyId)
            }
            assertEquals(CollectionUnavailableException.Scope.STUDY, error.scope)
            val participantError = assertThrows(CollectionUnavailableException::class.java) {
                ParticipantCollectionGate(connection).requireActive(studyId, "p-gate")
            }
            assertEquals(CollectionUnavailableException.Scope.PARTICIPANT, participantError.scope)
            connection.rollback()
        }
    }

    @Test
    fun diarySubmissionsThroughBothTransactionOwnersFitOneConnection() {
        val studyId = UUID.randomUUID()
        val participantId = "single-slot-diary"
        participant(studyId, participantId)
        TestSecurityUtils.setupSecurityContext()
        RequestContextHolder.resetRequestAttributes()
        try {
            RLSRequestContext.withContext(RLSConnectionContext("single-slot-researcher", setOf(studyId), isAdmin = true)) {
                HikariDataSource(HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password
                    maximumPoolSize = 1; minimumIdle = 0; connectionTimeout = 250
                }).use { pool ->
                    val manager = mock<DataSourceManager>()
                    whenever(manager.getDataSource(any())).thenReturn(pool)
                    whenever(manager.getFlavor(any())).thenReturn(PostgresFlavor.VANILLA)
                    val storage = StorageResolver(manager, ChronicleStorageConfiguration(defaultEventStorage = "default"))
                    val member = mock<HazelcastInstance>()
                    val stats = mock<IMap<ParticipantKey, ParticipantStats>>()
                    whenever(member.getMap<ParticipantKey, ParticipantStats>("PARTICIPANT_STATS"))
                        .thenReturn(stats)
                    whenever(member.getMap<UUID, Study>("STUDIES")).thenReturn(mock())
                    val ids = mock<HazelcastIdGenerationService>()
                    whenever(ids.getNextId()).thenAnswer { UUID.randomUUID() }
                    val authorization = mock<AuthorizationManager>()
                    whenever(authorization.checkIfHasPermissions(any(), any(),
                        any())).thenReturn(true)
                    val audit = mock<AuditingManager>()
                    val enrollment = EnrollmentService(storage, ids, mock())
                    val studies = StudyService(
                        storage, authorization, mock(), enrollment,
                        mock(), ids, mock(), audit, member,
                        HazelcastParticipantStatsCache(storage, member),
                        mock())
                    val diary = TimeUseDiaryService(storage, ids, studies)
                    val mapper = ObjectMappers.getJsonMapper()
                    val forms = participantFormAccessServiceForTest(storage)
                    val code = forms.createAccessCode(studyId, participantId, ParticipantFormKind.TIME_USE_DIARY, null, null, null,
                        ParticipantAccessCodeIssuerType.RESEARCHER, "one-slot")
                    val session = checkNotNull(forms.exchangeAccessCode(code.accessCode))
                    val scope = checkNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
                    val receipts = ParticipantFormSubmissionReceiptService(storage, mapper)
                    receipts.executeWithSubmissionId(
                        SubmissionReceiptScope(scope, scope.formKind, "diary", UUID.randomUUID()), emptyList<String>(),
                    ) {
                        diary.submitTimeUseDiary(it, studyId, participantId, emptyList())
                    }
                    val controller = TimeUseDiaryController(authorization, audit, storage, ids,
                        diary, studies, mock(), enrollment, receipts)
                    controller.submitTimeUseDiary(studyId, participantId, emptyList())
                    assertDiaryOwnersReleased(pool, studyId, stats)
                }
            }
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }


    private fun assertDiaryOwnersReleased(pool: HikariDataSource, study: UUID, stats: IMap<ParticipantKey, ParticipantStats>) {
        assertEquals(0, pool.hikariPoolMXBean.activeConnections)
        pool.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM time_use_diary_submissions WHERE study_id = ?").use {
                it.setObject(1, study)
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(2L, rows.getLong(1)) }
            }
        }
        Mockito.verify(stats, Mockito.never()).get(any())
    }

}
