package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.mockito.Mockito
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.util.UUID

class RLSRequestContextTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")
    }

    private lateinit var hds: HikariDataSource

    @Before
    fun setUp() {
        val config = HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
            minimumIdle = 1
        }
        hds = HikariDataSource(config)
        // The request-path wrapper now drops to chronicle_app via SET ROLE; the role must
        // exist in this bare container (mirrors docker/init-db-roles.sql).
        hds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "DO ${'$'}${'$'} BEGIN " +
                        "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'chronicle_app') THEN " +
                        "CREATE ROLE chronicle_app NOSUPERUSER NOBYPASSRLS; END IF; END ${'$'}${'$'};"
                )
            }
        }
    }

    @After
    fun tearDown() {
        RLSRequestContext.clear()
        SecurityContextHolder.clearContext()
        if (::hds.isInitialized) {
            hds.close()
        }
    }

    @Test
    fun `no request context returns stable wrapper that delegates unscoped connections`() {
        val wrapped = RLSDataSources.wrapIfRequestScoped(hds)
        assertSame(wrapped, RLSDataSources.wrapIfRequestScoped(hds))

        wrapped.connection.use { connection ->
            assertUnset(connection, "app.current_user_id")
            assertUnset(connection, "app.authorized_studies")
            assertUnset(connection, "app.is_admin")
        }
    }

    @Test
    fun `cached datasource wrapper applies context at connection borrow time and clears before pool return`() {
        val wrapped = RLSDataSources.wrapIfRequestScoped(hds)
        val studyId = UUID.randomUUID()
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "user-123",
                authorizedStudyIds = setOf(studyId),
                isAdmin = false
            )
        )

        wrapped.connection.use { connection ->
            assertEquals("user-123", currentSetting(connection, "app.current_user_id"))
            assertEquals(studyId.toString(), currentSetting(connection, "app.authorized_studies"))
            assertEquals("false", currentSetting(connection, "app.is_admin"))
        }

        hds.connection.use { connection ->
            assertEquals("", currentSetting(connection, "app.current_user_id"))
            assertEquals("", currentSetting(connection, "app.authorized_studies"))
            assertEquals("false", currentSetting(connection, "app.is_admin"))
        }
    }

    @Test
    fun `admin context helper clears session state after privileged block`() {
        hds.connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                assertEquals("true", currentSetting(connection, "app.is_admin"))
            }

            assertTrue(RLSConnectionCustomizer.validateContextCleared(connection))
            assertEquals("false", currentSetting(connection, "app.is_admin"))
        }
    }

    @Test
    fun `transaction admin context rejects autocommit connections`() {
        hds.connection.use { connection ->
            assertThrows(IllegalStateException::class.java) {
                RLSConnectionCustomizer.withAdminTransactionContext(connection) { Unit }
            }
        }
    }

    @Test
    fun `transaction admin context remains caller owned and clears at commit or rollback`() {
        hds.connection.use { connection ->
            connection.autoCommit = false
            try {
                RLSConnectionCustomizer.withAdminTransactionContext(connection) {
                    assertEquals("true", currentSetting(connection, "app.is_admin"))
                    assertFalse(connection.autoCommit)
                }
                assertEquals("true", currentSetting(connection, "app.is_admin"))

                connection.commit()
                assertFalse(currentSetting(connection, "app.is_admin") == "true")

                RLSConnectionCustomizer.withAdminTransactionContext(connection) {
                    assertEquals("true", currentSetting(connection, "app.is_admin"))
                }
                connection.rollback()
                assertFalse(currentSetting(connection, "app.is_admin") == "true")
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    @Test
    fun `api key authentication maps RLS context to exactly the key study`() {
        val studyId = UUID.randomUUID()
        val auth = ApiKeyAuthenticationToken(
            principal = "apikey:test-key",
            keyId = UUID.randomUUID(),
            studyId = studyId,
            participantId = null,
            deviceId = null,
            scope = ApiKeyScope.WRITE,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY"))
        )
        SecurityContextHolder.getContext().authentication = auth

        val manager = RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
        val context = manager.getCurrentUserContext()

        assertEquals("apikey:test-key", context.principalId)
        assertEquals(setOf(studyId), context.authorizedStudyIds)
        assertFalse(context.isAdmin)
    }

    @Test
    fun `verified mobile enrollment HMAC maps RLS context to exactly the signed path study`() {
        val studyId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = MobileApiHmacAuthenticationToken(studyId)

        val manager = RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
        val context = manager.getCurrentUserContext()

        assertEquals("mobile-hmac-bootstrap", context.principalId)
        assertEquals(setOf(studyId), context.authorizedStudyIds)
        assertFalse(context.isAdmin)
        assertTrue(context.authorizedOrganizationIds.isEmpty())
    }

    @Test
    fun `one time mobile enrollment maps RLS context to exactly the code study`() {
        val studyId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = MobileEnrollmentAuthenticationToken(studyId)

        val manager = RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
        val context = manager.getCurrentUserContext()

        assertEquals("mobile-enrollment-bootstrap", context.principalId)
        assertEquals(setOf(studyId), context.authorizedStudyIds)
        assertFalse(context.isAdmin)
        assertTrue(context.authorizedOrganizationIds.isEmpty())
    }

    private fun currentSetting(connection: Connection, setting: String): String? {
        connection.prepareStatement("SELECT current_setting(?, true)").use { statement ->
            statement.setString(1, setting)
            statement.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getString(1)
            }
        }
    }

    private fun assertUnset(connection: Connection, setting: String) {
        val actual = currentSetting(connection, setting)
        assertTrue("Expected $setting to be unset, got '$actual'", actual == null || actual == "")
    }
}
