package com.openlattice.chronicle.organizations.initializers

import com.openlattice.chronicle.storage.ResolvedStorage

import com.openlattice.chronicle.configuration.ChronicleConfiguration
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.sql.Connection
import java.util.Optional

class OrganizationsInitializationTaskTest {
    @Test
    fun aFailedGlobalOrganizationLookupPreventsStartupAndIsCounted() {
        val failure = IllegalStateException("lookup failed")
        val organizations = mock<ChronicleOrganizationService> {
            on { maybeGetOrganization(any()) } doThrow failure
        }
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels("organization_bootstrap")
        val before = failures.get()

        val thrown = assertThrows(IllegalStateException::class.java) {
            OrganizationsInitializationTask().initialize(
                OrganizationsInitializationDependencies(mock(), organizations, mock(), mock()),
            )
        }

        assertSame(failure, thrown)
        assertEquals(before + 1, failures.get(), 0.0)
    }

    @Test
    fun aFailedGlobalOrganizationCreateIsRolledBack() {
        val failure = IllegalStateException("create failed")
        val connection = mock<Connection>()
        val pool = mock<HikariDataSource> { on { this.connection } doReturn connection }
        val storageResolver = mock<StorageResolver> {
            on { getDefaultPlatformStorage() } doReturn (ResolvedStorage(PostgresFlavor.VANILLA, pool))
        }
        val organizations = mock<ChronicleOrganizationService> {
            on { maybeGetOrganization(any()) } doReturn Optional.empty()
            on { createOrganization(any<Connection>(), any(), any()) } doThrow failure
        }

        val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels("organization_bootstrap")
        val before = failures.get()
        val thrown = assertThrows(IllegalStateException::class.java) {
            OrganizationsInitializationTask().initialize(
                OrganizationsInitializationDependencies(storageResolver, organizations, mock<SecurePrincipalsManager>(),
                    mock<ChronicleConfiguration>()),
            )
        }
        assertSame(failure, thrown)
        assertEquals(before + 1, failures.get(), 0.0)

        inOrder(connection) {
            verify(connection).autoCommit = false
            verify(connection).rollback()
            verify(connection).close()
        }
        verify(connection, never()).commit()
    }
}
