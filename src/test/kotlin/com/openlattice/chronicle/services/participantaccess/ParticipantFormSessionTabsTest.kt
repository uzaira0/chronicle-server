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
}
