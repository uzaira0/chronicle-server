package com.openlattice.chronicle.storage

import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.mock
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

class StorageResolverTopologyTest {

    companion object {
        private lateinit var platformPostgres: PostgreSQLContainer<*>
        private lateinit var separatePostgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            platformPostgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_topology")
            separatePostgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_topology")
            platformPostgres.start()
            separatePostgres.start()
            ChronicleContractTestSchema.waitForQueryReady(platformPostgres)
            ChronicleContractTestSchema.waitForQueryReady(separatePostgres)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::separatePostgres.isInitialized) {
                separatePostgres.stop()
            }
            if (::platformPostgres.isInitialized) {
                platformPostgres.stop()
            }
        }
    }

    private val resolver = StorageResolver(
        mock<DataSourceManager>(),
        ChronicleStorageConfiguration(),
    )

    @Test
    fun `same database schema and advisory lock domain passes`() {
        platformPostgres.createConnection("").use { platformConnection ->
            platformPostgres.createConnection("").use { eventConnection ->
                resolver.requireSamePostgresLockDomain(
                    platformConnection,
                    eventConnection,
                    UUID.randomUUID().mostSignificantBits,
                )

                assertTrue(platformConnection.autoCommit)
                assertTrue(eventConnection.autoCommit)
            }
        }
    }

    @Test
    fun `different effective schema fails closed`() {
        platformPostgres.createConnection("").use { setupConnection ->
            setupConnection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA IF NOT EXISTS topology_event")
            }
        }

        platformPostgres.createConnection("").use { platformConnection ->
            platformPostgres.createConnection("").use { eventConnection ->
                eventConnection.createStatement().use { statement ->
                    statement.execute("SET search_path TO topology_event")
                }

                val failure = assertThrows(IllegalStateException::class.java) {
                    resolver.requireSamePostgresLockDomain(
                        platformConnection,
                        eventConnection,
                        UUID.randomUUID().mostSignificantBits,
                    )
                }

                assertTrue(failure.message.orEmpty().contains("same PostgreSQL database and schema search path"))
                assertTrue(platformConnection.autoCommit)
                assertTrue(eventConnection.autoCommit)
            }
        }
    }

    @Test
    fun `identical database and schema names on separate servers fail lock-domain proof`() {
        platformPostgres.createConnection("").use { platformConnection ->
            separatePostgres.createConnection("").use { eventConnection ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    resolver.requireSamePostgresLockDomain(
                        platformConnection,
                        eventConnection,
                        UUID.randomUUID().mostSignificantBits,
                    )
                }

                assertEquals(
                    "Verified deletion requires platform and event storage to share one PostgreSQL advisory-lock domain",
                    failure.message,
                )
                assertTrue(platformConnection.autoCommit)
                assertTrue(eventConnection.autoCommit)
            }
        }
    }
}
