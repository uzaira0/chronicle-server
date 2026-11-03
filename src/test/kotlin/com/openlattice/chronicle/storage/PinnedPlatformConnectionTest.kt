package com.openlattice.chronicle.storage

import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import java.sql.Connection
import java.sql.SQLException

class PinnedPlatformConnectionTest {
    /**
     * Restore recovery at startup shares its coordination connection with helpers that commit their own work.
     * When Postgres drops mid-recovery, a helper's commit or statement fails with the driver's own SQLException
     * (so retry classification by SQL state still works), not a reflection wrapper; closing the borrowed handle
     * never closes the owner's connection.
     */
    @Test
    fun sharedBootstrapConnectionSurfacesTheDriversFailureAndStaysOwnedByTheCoordinator() {
        val lost = SQLException("An I/O error occurred while sending to the backend.", "08006")
        val owner = Mockito.mock(Connection::class.java)
        Mockito.`when`(owner.autoCommit).thenReturn(true)
        Mockito.`when`(owner.commit()).thenThrow(lost)
        Mockito.`when`(owner.prepareStatement(Mockito.anyString())).thenThrow(lost)
        val pool = Mockito.mock(HikariDataSource::class.java)

        PinnedPlatformConnection.bootstrapSharing(pool, owner, emptyList()) {
            val borrowed = PinnedPlatformConnection.resolve(pool).connection
            assertSame(lost, assertThrows(SQLException::class.java) { borrowed.commit() })
            assertSame(lost, assertThrows(SQLException::class.java) { borrowed.prepareStatement("SELECT 1") })
            borrowed.close()
            assertFalse(borrowed.isClosed)
        }
        Mockito.verify(owner, Mockito.never()).close()
        Mockito.verify(pool, Mockito.never()).connection
        Mockito.verify(pool, Mockito.never()).close()
    }
}
