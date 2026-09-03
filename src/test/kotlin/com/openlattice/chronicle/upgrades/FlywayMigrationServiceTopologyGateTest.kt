package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FlywayMigrationServiceTopologyGateTest {

    @Test
    fun `topology failure aborts before platform storage or migration is touched`() {
        val storageResolver = mock<StorageResolver>()
        val topologyFailure = IllegalStateException("split storage is unsupported")
        doThrow(topologyFailure)
            .whenever(storageResolver)
            .requireDefaultDeletionStorageColocated()

        val failure = assertThrows(IllegalStateException::class.java) {
            FlywayMigrationService(storageResolver).runUpgrade()
        }

        assertSame(topologyFailure, failure)
        verify(storageResolver).requireDefaultDeletionStorageColocated()
        verify(storageResolver, never()).getPlatformStorage()
    }
}
