package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.mapstores.MapstoresPod
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import java.sql.Connection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class RLSWorkerContextTest : RLSConnectionFixture() {
    @Test
    fun `trusted mapstore storage has an explicit system scope without changing requests`() {
        val wrapped = RLSDataSources.wrapIfRequestScoped(hds)
        val resolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(resolver.getPlatformStorage()).thenReturn(wrapped)
        val pod = MapstoresPod(resolver)
        val caller = RLSConnectionContext("mapstore-caller", setOf(UUID.randomUUID()), false)
        RLSRequestContext.set(caller)
        pod.studyMapstore().hds.connection.use { connection ->
            assertEquals("true", currentSetting(connection, "app.is_admin"))
            assertEquals(RLSRequestContext.systemContext.principalId, currentSetting(connection, "app.current_user_id"))
        }
        assertSame(caller, RLSRequestContext.current())
        wrapped.connection.use { connection ->
            assertEquals("false", currentSetting(connection, "app.is_admin"))
            assertEquals(caller.principalId, currentSetting(connection, "app.current_user_id"))
        }
    }

    @Test
    fun `user directory mapstore can cold load a collaborator while request SQL stays self scoped`() {
        com.openlattice.chronicle.contract.ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        hds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""
                    INSERT INTO users (user_id, user_data)
                    VALUES ('directory-collaborator', '{"id":"directory-collaborator","name":"Collaborator"}'::jsonb)
                """.trimIndent())
            }
        }
        val wrapped = RLSDataSources.wrapUncachedRequestScoped(hds)
        val resolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(resolver.getPlatformStorage()).thenReturn(wrapped)
        val pod = MapstoresPod(resolver)
        val caller = RLSConnectionContext("directory-requester", emptySet(), false)
        RLSRequestContext.withContext(caller) {
            assertEquals("Collaborator", pod.userMapstore().load("directory-collaborator")?.name)
            assertSame(caller, RLSRequestContext.current())
            wrapped.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM users WHERE user_id = 'directory-collaborator'").use { rows ->
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `bootstrap sharing retains one raw owner connection and restores request borrowing`() {
        hds.connectionTimeout = 300L
        val wrapped = RLSDataSources.wrapIfRequestScoped(hds)
        val caller = RLSConnectionContext("bootstrap-caller", emptySet(), false)
        RLSRequestContext.set(caller)
        RLSDataSources.bootstrapStorage(wrapped).connection.use { owner ->
            owner.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA IF NOT EXISTS bootstrap_owner_probe")
                statement.execute("REVOKE ALL ON SCHEMA bootstrap_owner_probe FROM PUBLIC, chronicle_app")
                statement.execute("CREATE TABLE IF NOT EXISTS bootstrap_owner_probe.checkpoint (id integer)")
            }
            PinnedPlatformConnection.bootstrapSharing(wrapped, owner, listOf(wrapped)) {
                RLSDataSources.bootstrapStorage(wrapped).connection.use { nested ->
                    assertBootstrapAccess(nested)
                    nested.autoCommit = false
                    nested.rollback()
                    nested.autoCommit = true
                }
                assertFalse(owner.isClosed)
            }
        }
        assertSame(caller, RLSRequestContext.current())
        wrapped.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_user").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("chronicle_app", rows.getString(1))
                }
                assertThrows(java.sql.SQLException::class.java) {
                    statement.executeQuery("SELECT * FROM bootstrap_owner_probe.checkpoint")
                }
            }
        }
    }

    @Test
    fun anonymousRequestCannotReadANamedOwnersCreateReceipt() {
        com.openlattice.chronicle.contract.ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        val owner = "chronicle-anonymous"
        assertEquals(owner, com.openlattice.chronicle.configuration.ChronicleAuthUser(id = owner).toChronicleUserProfile().id)
        hds.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO researcher_create_receipts (principal_id, route, idempotency_key, request_hash, owner_kind)
                VALUES (?, '/receipt-owner-collision', 'receipt-owner-collision', ?, 'USER')
            """.trimIndent()).use { statement ->
                statement.setString(1, owner); statement.setBytes(2, ByteArray(32)); statement.executeUpdate()
                // A legacy invalid blank owner must never become an anonymous request's identity.
                statement.setString(1, ""); statement.executeUpdate()
            }
        }
        val authorization = org.mockito.Mockito.mock(com.openlattice.chronicle.authorization.AuthorizationManager::class.java)
        org.mockito.Mockito.`when`(authorization.getAuthorizedObjectsOfType(
            org.mockito.kotlin.any<Set<com.openlattice.chronicle.authorization.Principal>>(),
            org.mockito.kotlin.any<com.openlattice.chronicle.authorization.SecurableObjectType>(),
            org.mockito.kotlin.any<java.util.EnumSet<com.openlattice.chronicle.authorization.Permission>>(),
        )).thenAnswer { java.util.stream.Stream.empty<com.openlattice.chronicle.authorization.AclKey>() }
        val filter = com.openlattice.chronicle.filters.RLSContextFilter(RLSContextManager(authorization))
        val wrapped = RLSDataSources.wrapUncachedRequestScoped(hds)
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext(subject = owner, admin = false)
        try {
            assertReceiptRequest(filter, wrapped, owner, 1)
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
            assertReceiptRequest(filter, wrapped, owner, 0)
            assertReceiptRequest(filter, wrapped, "", 0)
        } finally { com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext() }
    }

    private fun assertReceiptRequest(
        filter: com.openlattice.chronicle.filters.RLSContextFilter,
        wrapped: com.zaxxer.hikari.HikariDataSource,
        owner: String,
        expected: Int,
    ) {
        val request = org.springframework.mock.web.MockHttpServletRequest("GET", "/chronicle/v3/principals")
        val response = org.springframework.mock.web.MockHttpServletResponse()
        var delivered = false
        filter.doFilter(request, response, jakarta.servlet.FilterChain { _, _ ->
            delivered = true
            wrapped.connection.use { connection -> assertReceiptRows(connection, owner, expected) }
        })
        assertTrue("Production RLSContextFilter must deliver this request", delivered)
        assertEquals(200, response.status)
        assertEquals(null, RLSRequestContext.current())
    }

    private fun assertReceiptRows(connection: Connection, owner: String, expected: Int) {
        connection.prepareStatement("""
            SELECT current_user, current_setting('app.is_admin', true),
                current_setting('app.is_deletion_worker', true), current_setting('app.current_user_id', true),
                (SELECT count(*) FROM researcher_create_receipts WHERE principal_id = ?)
        """.trimIndent()).use { statement ->
            statement.setString(1, owner)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals("chronicle_app", rows.getString(1))
                assertEquals("false", rows.getString(2))
                assertEquals("false", rows.getString(3))
                assertEquals("Request receipt ownership; SQL principal=${rows.getString(4)}", expected, rows.getInt(5))
            }
        }
    }

    private fun assertBootstrapAccess(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_user, count(*) FROM bootstrap_owner_probe.checkpoint").use { rows ->
                assertTrue(rows.next())
                assertEquals(postgres.username, rows.getString(1))
            }
        }
    }

}
