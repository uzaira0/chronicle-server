package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.storage.ResolvedStorage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JwtBlocklistRlsTest : RLSConnectionFixture() {
    @Test
    fun authenticationStateUsesAnExplicitSystemContextAndRestoresTheCaller() {
        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        val hazelcast = mock<HazelcastInstance>()
        val map = mock<IMap<String, Long>>()
        whenever(hazelcast.getMap<String, Long>(any())).thenReturn(map)
        whenever(map.name).thenReturn("JWT_BLOCKLIST")
        val wrapped = RLSDataSources.wrapUncachedRequestScoped(hds)
        val storage = mock<StorageResolver>()
        whenever(storage.getDefaultPlatformStorage()).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, wrapped))
        val blocklist = JwtBlocklist(hazelcast, storage)
        val caller = RLSConnectionContext("jwt-request", emptySet(), false)
        RLSRequestContext.withContext(caller) {
            val token = "rls-token-${UUID.randomUUID()}"
            assertTrue(blocklist.blockToken(token, Instant.now().plusSeconds(3600)))
            assertSame(caller, RLSRequestContext.current())
            assertTrue(blocklist.isBlocked(token))
            assertSame(caller, RLSRequestContext.current())
            wrapped.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM jwt_session_state").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("Request SQL cannot inspect authentication state", 0, rows.getInt(1))
                    }
                }
            }
        }
        hds.connection.use { connection -> assertTrue(RLSConnectionCustomizer.validateContextCleared(connection)) }
    }
}
