package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.sql.Connection
import java.util.UUID

class RLSRequestContextTest : RLSConnectionFixture() {

    @Test
    fun `trusted worker scope reaches JDBC restores nested scopes and is cleared on return`() {
        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        val wrapped = RLSDataSources.wrapUncachedRequestScoped(hds)
        RLSRequestContext.withContext(RLSConnectionContext(RLSConnectionCustomizer.DELETION_WORKER_ID, emptySet(), true)) {
            wrapped.connection.use { connection -> assertFalse(databaseIsWorker(connection)) }
        }
        RLSRequestContext.withDeletionWorkerContext {
            assertTrue(requireNotNull(RLSRequestContext.current()).isDeletionWorker)
            assertSystemRequestHasNoWorker(wrapped)
            wrapped.connection.use(::assertTrustedWorkerScopes)
        }
        hds.connection.use { connection ->
            assertEquals("false", currentSetting(connection, "app.is_deletion_worker"))
            setWorkerFlag(connection, "true")
            assertFalse(RLSConnectionCustomizer.validateContextCleared(connection))
            RLSConnectionCustomizer.clearContext(connection)
            assertTrue(RLSConnectionCustomizer.validateContextCleared(connection))
        }
    }

    @Test
    fun `unset empty and malformed worker flags fail closed without boolean cast errors`() {
        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        hds.connection.use { connection ->
            assertFalse(databaseIsWorker(connection))
            RLSConnectionSql.applyContext(connection, RLSConnectionContext("ordinary-admin", emptySet(), true))
            for (value in listOf("", "false", "malformed")) {
                setWorkerFlag(connection, value)
                assertFalse(databaseIsWorker(connection))
            }
            RLSConnectionSql.applyContext(connection, RLSConnectionContext("ordinary-user", emptySet(), false))
            setWorkerFlag(connection, "true")
            assertFalse("Worker privileges also require the admin context", databaseIsWorker(connection))
            RLSConnectionSql.clear(connection)
            assertTrue(RLSConnectionCustomizer.validateContextCleared(connection))
        }
    }

    private fun assertSystemRequestHasNoWorker(wrapped: HikariDataSource) {
        RLSRequestContext.withSystemContext {
            assertFalse(requireNotNull(RLSRequestContext.current()).isDeletionWorker)
            wrapped.connection.use { connection -> assertFalse(databaseIsWorker(connection)) }
        }
        assertTrue(requireNotNull(RLSRequestContext.current()).isDeletionWorker)
    }

    private fun assertTrustedWorkerScopes(connection: Connection) {
        assertEquals("true", currentSetting(connection, "app.is_deletion_worker"))
        assertTrue(databaseIsWorker(connection))
        connection.autoCommit = false
        RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
            assertFalse(databaseIsWorker(connection))
        }
        assertTrue(databaseIsWorker(connection))
        RLSConnectionCustomizer.withWithdrawalEnrollmentContext(connection) {
            assertTrue(databaseIsWorker(connection))
        }
        assertTrue(databaseIsWorker(connection))
        connection.rollback()
        connection.autoCommit = true
    }

    private fun databaseIsWorker(connection: Connection): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT public.chronicle_is_deletion_worker()").use { rows ->
            check(rows.next()); rows.getBoolean(1)
        }
    }

    private fun setWorkerFlag(connection: Connection, value: String) {
        connection.prepareStatement("SELECT set_config('app.is_deletion_worker', ?, false)").use { statement ->
            statement.setString(1, value); statement.execute()
        }
    }

    @Test
    fun `researcher organization permissions reach JDBC and all reset paths clear them`() {
        val organization = UUID.randomUUID()
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(authorization.getAuthorizedObjectsOfType(
            org.mockito.kotlin.any<Set<com.openlattice.chronicle.authorization.Principal>>(),
            org.mockito.kotlin.any<com.openlattice.chronicle.authorization.SecurableObjectType>(),
            org.mockito.kotlin.any<java.util.EnumSet<com.openlattice.chronicle.authorization.Permission>>(),
        )).thenAnswer { invocation ->
            if (invocation.getArgument<com.openlattice.chronicle.authorization.SecurableObjectType>(1) ==
                com.openlattice.chronicle.authorization.SecurableObjectType.Organization) {
                java.util.stream.Stream.of(com.openlattice.chronicle.authorization.AclKey(organization))
            } else java.util.stream.Stream.empty<com.openlattice.chronicle.authorization.AclKey>()
        }
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext(subject = "organization-reader", admin = false)
        RLSRequestContext.set(RLSContextManager(authorization).getCurrentUserContext())
        RLSDataSources.wrapUncachedRequestScoped(hds).connection.use { connection ->
            assertEquals(organization.toString(), currentSetting(connection, "app.authorized_organizations"))
            connection.autoCommit = false
            RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
                assertEquals("", currentSetting(connection, "app.authorized_organizations"))
            }
            assertEquals(organization.toString(), currentSetting(connection, "app.authorized_organizations"))
            connection.rollback()
            connection.autoCommit = true
        }
        hds.connection.use { connection ->
            assertEquals("", currentSetting(connection, "app.authorized_organizations"))
            connection.prepareStatement("SELECT set_config('app.authorized_organizations', ?, false)").use {
                it.setString(1, organization.toString())
                it.execute()
            }
            assertFalse(RLSConnectionCustomizer.validateContextCleared(connection))
            RLSConnectionCustomizer.clearContext(connection)
            assertTrue(RLSConnectionCustomizer.validateContextCleared(connection))
        }
    }

    @Test
    fun `organization lookup failure fails the whole researcher context closed`() {
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(authorization.getAuthorizedObjectsOfType(
            org.mockito.kotlin.any<Set<com.openlattice.chronicle.authorization.Principal>>(),
            org.mockito.kotlin.any<com.openlattice.chronicle.authorization.SecurableObjectType>(),
            org.mockito.kotlin.any<java.util.EnumSet<com.openlattice.chronicle.authorization.Permission>>(),
        )).thenAnswer { invocation ->
            if (invocation.getArgument<com.openlattice.chronicle.authorization.SecurableObjectType>(1) ==
                com.openlattice.chronicle.authorization.SecurableObjectType.Organization) error("organization lookup unavailable")
            java.util.stream.Stream.empty<com.openlattice.chronicle.authorization.AclKey>()
        }
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext(subject = "organization-failure", admin = false)
        assertEquals("Failed to resolve authorized organization ids", assertThrows(RLSContextException::class.java) {
            RLSContextManager(authorization).getCurrentUserContext()
        }.message)
    }

    @Test
    fun `captured stats guard joins a one slot transaction and restores caller authorization`() {
        hds.connectionTimeout = 300L
        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        val wrapped = RLSDataSources.wrapIfRequestScoped(hds)
        val guard = com.openlattice.chronicle.mapstores.stats.ParticipantStatsDeletionGuard(
            RLSDataSources.wrapWithSystemContext(wrapped),
        )
        val studyId = UUID.randomUUID()
        RLSRequestContext.set(RLSConnectionContext("participant-request", setOf(studyId), false))
        wrapped.connection.use { owner ->
            owner.autoCommit = false
            com.openlattice.chronicle.storage.PinnedPlatformConnection.pinning(wrapped, owner) {
                assertFalse(guard.isBlocked(com.openlattice.chronicle.mapstores.stats.ParticipantKey(studyId, "participant")))
                assertEquals("participant-request", currentSetting(owner, "app.current_user_id"))
                assertEquals(studyId.toString(), currentSetting(owner, "app.authorized_studies"))
                assertEquals("false", currentSetting(owner, "app.is_admin"))
                assertFalse(owner.autoCommit)
            }
            owner.rollback()
            owner.autoCommit = true
        }
    }

    @Test
    fun `both anonymous borrow overloads drop privileges and deny protected rows`() {
        hds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS anonymous_rls_probe (study_id uuid)")
                statement.execute("TRUNCATE anonymous_rls_probe")
                statement.execute("INSERT INTO anonymous_rls_probe VALUES ('00000000-0000-0000-0000-000000000001')")
                statement.execute("ALTER TABLE anonymous_rls_probe ENABLE ROW LEVEL SECURITY")
                statement.execute("GRANT SELECT ON anonymous_rls_probe TO chronicle_app")
                statement.execute("DROP POLICY IF EXISTS probe_scope ON anonymous_rls_probe")
                statement.execute("""
                    CREATE POLICY probe_scope ON anonymous_rls_probe
                    USING (study_id::text = current_setting('app.authorized_studies', true))
                """.trimIndent())
            }
        }
        val delegate = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(delegate.connection).thenAnswer { hds.connection }
        Mockito.`when`(delegate.getConnection(hds.username, hds.password)).thenAnswer { hds.connection }
        val wrapped = RLSDataSources.wrapIfRequestScoped(delegate)
        assertSame(wrapped, RLSDataSources.wrapIfRequestScoped(delegate))
        for (credentialed in listOf(false, true)) {
            (if (credentialed) wrapped.getConnection(hds.username, hds.password) else wrapped.connection).use { connection ->
                assertAnonymousConnection(connection)
            }
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
            randomDeviceId = null,
            scope = ApiKeyScope.WRITE,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY"))
        )
        SecurityContextHolder.getContext().authentication = auth

        val manager = RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
        val context = manager.getCurrentUserContext()

        assertEquals("apikey:test-key", context.principalId)
        assertEquals(ReceiptOwnerKind.API_KEY, context.receiptOwnerKind)
        assertEquals(setOf(studyId), context.authorizedStudyIds)
        assertTrue(context.authorizedOrganizationIds.isEmpty())
        assertFalse(context.isAdmin)
    }

    @Test
    fun `verified mobile enrollment HMAC maps RLS context to exactly the signed path study`() {
        val studyId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = MobileApiHmacAuthenticationToken(studyId)

        val manager = RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
        val context = manager.getCurrentUserContext()

        assertEquals("mobile-hmac-bootstrap", context.principalId)
        assertEquals(null, context.receiptOwnerKind)
        assertEquals(setOf(studyId), context.authorizedStudyIds)
        assertTrue(context.authorizedOrganizationIds.isEmpty())
        assertFalse(context.isAdmin)
    }

    @Test
    fun `one time mobile enrollment maps RLS context to exactly the code study`() {
        val studyId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = MobileEnrollmentAuthenticationToken(studyId)

        val manager = RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
        val context = manager.getCurrentUserContext()

        assertEquals("mobile-enrollment-bootstrap", context.principalId)
        assertEquals(null, context.receiptOwnerKind)
        assertEquals(setOf(studyId), context.authorizedStudyIds)
        assertTrue(context.authorizedOrganizationIds.isEmpty())
        assertFalse(context.isAdmin)
    }

    @Test
    fun `withdrawal enrollment scope restores every caller setting after success and failure`() {
        val context = RLSConnectionContext("researcher", setOf(UUID.randomUUID()), false,
            authorizedOrganizationIds = setOf(UUID.randomUUID()), receiptOwnerKind = ReceiptOwnerKind.USER)
        hds.connection.use { connection ->
            connection.autoCommit = false
            try {
                RLSConnectionSql.applyContext(connection, context)
                val settings = listOf(
                    "app.current_user_id", "app.authorized_studies", "app.authorized_organizations",
                    "app.is_admin", "app.is_deletion_worker", "app.receipt_owner_kind",
                )
                val original = settings.map { currentSetting(connection, it) }
                for (fail in listOf(false, true)) {
                    val action = {
                        RLSConnectionCustomizer.withWithdrawalEnrollmentContext(connection) {
                            assertEquals(RLSConnectionCustomizer.DELETION_WORKER_ID, currentSetting(connection, "app.current_user_id"))
                            assertEquals("true", currentSetting(connection, "app.is_admin"))
                            assertEquals("true", currentSetting(connection, "app.is_deletion_worker"))
                            if (fail) throw IllegalArgumentException("caller failure")
                            "result"
                        }
                    }
                    if (fail) assertThrows(IllegalArgumentException::class.java) { action() }
                    else assertEquals("result", action())
                    assertEquals(original, settings.map { currentSetting(connection, it) })
                    assertFalse(connection.autoCommit)
                }
            } finally {
                connection.rollback()
                connection.autoCommit = true
                RLSConnectionSql.clear(connection)
            }
        }
    }

    /**
     * Postgres restarts while a request borrows a connection, so applying the request's RLS context fails. The
     * request sees that failure, and the half-configured physical connection is closed and evicted from the pool
     * instead of being handed out without its context.
     */
    @Test
    fun `connection whose RLS context cannot be applied is closed evicted and never returned`() {
        val outage = java.sql.SQLException("terminating connection due to administrator command", "57P01")
        val physical = Mockito.mock(Connection::class.java)
        Mockito.`when`(physical.prepareStatement(Mockito.anyString())).thenThrow(outage)
        val delegate = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(delegate.connection).thenReturn(physical)
        val wrapped = RLSDataSources.wrapUncachedRequestScoped(delegate)

        assertSame(outage, assertThrows(java.sql.SQLException::class.java) { wrapped.connection })
        Mockito.verify(physical).close()
        Mockito.verify(delegate).evictConnection(physical)
    }

    /**
     * MapstoresPod and ParticipantStatsCache wrap the platform pool, which is already request scoped, in a system
     * wrapper. Applying the system context fails after the request context succeeded, so the outer wrapper evicts
     * through the inner wrapper. The physical connection must reach the real pool's evictConnection; evicting the
     * inner RLS proxy is a no-op in Hikari, which only evicts its own proxy connections.
     */
    @Test
    fun `nested system wrapper whose context cannot be applied evicts the physical connection from the pool`() {
        val outage = java.sql.SQLException("terminating connection due to administrator command", "57P01")
        val statement = Mockito.mock(java.sql.PreparedStatement::class.java)
        val physical = Mockito.mock(Connection::class.java)
        Mockito.`when`(physical.createStatement()).thenReturn(Mockito.mock(java.sql.Statement::class.java))
        // Call 1 sets the request context (succeeds), call 2 the system context (fails), later calls succeed.
        Mockito.`when`(physical.prepareStatement(Mockito.anyString()))
            .thenReturn(statement)
            .thenThrow(outage)
            .thenReturn(statement)
        val pool = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(pool.connection).thenReturn(physical)
        val nested = RLSDataSources.wrapWithSystemContext(RLSDataSources.wrapUncachedRequestScoped(pool))

        assertSame(outage, assertThrows(java.sql.SQLException::class.java) { nested.connection })
        Mockito.verify(pool).evictConnection(physical)
        Mockito.verify(physical).close()
    }

    /**
     * A MapStore borrows through the nested system wrapper and leaves the session in an aborted SQL transaction the
     * JDBC auto-commit flag does not know about, so the RLS reset on close fails (25P02) while the socket is healthy
     * and Hikari does not evict it on its own. The connection must leave the one-slot pool. Before the fix the inner
     * proxy was never closed and the slot stayed borrowed, so every later borrow timed out.
     */
    @Test
    fun `nested system wrapper returns the pool slot when the RLS reset fails on close`() {
        hds.connectionTimeout = 300L
        val nested = RLSDataSources.wrapWithSystemContext(RLSDataSources.wrapUncachedRequestScoped(hds))
        val connection = nested.connection
        connection.createStatement().use { statement ->
            statement.execute("BEGIN")
            assertThrows(java.sql.SQLException::class.java) { statement.execute("SELECT 1 / 0") }
        }

        val failure = assertThrows(java.sql.SQLException::class.java) { connection.close() }
        assertEquals("25P02", failure.sqlState)
        assertEquals(0, hds.hikariPoolMXBean.activeConnections)
        hds.connection.use { replacement -> assertTrue(replacement.isValid(1)) }
    }

    /**
     * A researcher's request arrives while the permission store is unreachable. Their study list cannot be
     * resolved, so no context is built (RLSContextFilter answers 403) instead of one that lists no studies.
     */
    @Test
    fun `researcher whose study permissions cannot be read gets no RLS context`() {
        val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            org.mockito.kotlin.any<Set<com.openlattice.chronicle.authorization.Principal>>(),
            org.mockito.kotlin.any<com.openlattice.chronicle.authorization.SecurableObjectType>(),
            org.mockito.kotlin.any<java.util.EnumSet<com.openlattice.chronicle.authorization.Permission>>(),
        )).thenThrow(IllegalStateException("hazelcast partition lost"))
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext(subject = "researcher-rls", admin = false)
        try {
            val failure = assertThrows(RLSContextException::class.java) {
                RLSContextManager(authorizationManager).getCurrentUserContext()
            }
            assertEquals("Failed to resolve authorized study ids", failure.message)
            assertEquals("hazelcast partition lost", failure.cause?.message)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun assertAnonymousConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user
            """.trimIndent()).use { rows ->
                assertTrue(rows.next())
                assertEquals("chronicle_app", rows.getString(1))
                assertFalse(rows.getBoolean(2))
                assertFalse(rows.getBoolean(3))
            }
            statement.executeQuery("SELECT count(*) FROM anonymous_rls_probe").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }
        assertEquals("false", currentSetting(connection, "app.is_admin"))
        assertEquals("false", currentSetting(connection, "app.is_deletion_worker"))
        assertEquals("", currentSetting(connection, "app.current_user_id"))
    }
}
