package com.openlattice.chronicle.storage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

/**
 * The self-host bundle, the docker compose stack and the k8s base config all declare exactly two
 * datasources — `default` (from the `postgres:` block) and `chronicle`. None of them declares
 * `platform_read`, which is the read-replica endpoint
 * [ChronicleStorageConfiguration.platformReadStorage] points at by default. Every full-study
 * export and every Time Use Diary read therefore died inside `DataSourceManager.getDataSource`
 * with `NoSuchElementException: Key platform_read is missing in the map`.
 */
class PlatformReadStorageFallbackTest {

    private val platformDataSource = Mockito.mock(HikariDataSource::class.java)
    private val replicaDataSource = Mockito.mock(HikariDataSource::class.java)

    private fun resolverFor(vararg registered: Pair<String, HikariDataSource>): StorageResolver {
        val dataSources = registered.toMap()
        val dataSourceManager = Mockito.mock(DataSourceManager::class.java)
        Mockito.`when`(dataSourceManager.dataSources).thenReturn(dataSources)
        // Mirrors DataSourceManager.getDataSource/getFlavor, which both go through Map.getValue
        // and therefore throw NoSuchElementException for an unregistered name.
        Mockito.`when`(dataSourceManager.getDataSource(Mockito.anyString())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            dataSources[name] ?: throw NoSuchElementException("Key $name is missing in the map")
        }
        Mockito.`when`(dataSourceManager.getFlavor(Mockito.anyString())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            if (dataSources.containsKey(name)) {
                PostgresFlavor.VANILLA
            } else {
                throw NoSuchElementException("Key $name is missing in the map")
            }
        }
        return StorageResolver(dataSourceManager, ChronicleStorageConfiguration())
    }

    @Test
    fun `single database deployment without a platform_read datasource falls back to platform storage`() {
        val resolver = resolverFor(
            ChronicleStorage.PLATFORM.id to platformDataSource,
            ChronicleStorage.CHRONICLE.id to platformDataSource,
        )

        val (flavor, hds) = resolver.getDefaultPlatformReadStorage()

        assertEquals(PostgresFlavor.VANILLA, flavor)
        // The resolved datasource is the RLS wrapper around the platform pool, which is exactly
        // what getDefaultPlatformStorage hands out, so reads run under the same chronicle_app role.
        assertSame(resolver.getDefaultPlatformStorage().second, hds)
    }

    @Test
    fun `a declared platform_read datasource still wins over the fallback`() {
        val resolver = resolverFor(
            ChronicleStorage.PLATFORM.id to platformDataSource,
            ChronicleStorage.PLATFORM_READ.id to replicaDataSource,
        )

        val (_, hds) = resolver.getDefaultPlatformReadStorage()

        assertNotSame(resolver.getDefaultPlatformStorage().second, hds)
    }

    @Test
    fun `a deployment with neither datasource fails loudly instead of silently`() {
        val resolver = resolverFor(ChronicleStorage.CHRONICLE.id to platformDataSource)

        val failure = assertThrows(IllegalStateException::class.java) {
            resolver.getDefaultPlatformReadStorage()
        }

        assertEquals(
            "Neither the configured platform read storage 'platform_read' nor the platform " +
                "storage fallback 'default' is a registered datasource",
            failure.message,
        )
    }
}
