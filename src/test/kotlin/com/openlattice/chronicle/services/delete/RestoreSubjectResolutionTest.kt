package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID

class RestoreSubjectResolutionTest {
    @Test
    fun completedPurgesResolveAbsenceBeforeHoldChecksEvenWhenCheckpointRetainsTheId() {
        val storage = mock(StorageResolver::class.java)
        val pool = mock(HikariDataSource::class.java)
        val connection = mock(Connection::class.java)
        val query = mock(PreparedStatement::class.java)
        val rows = mock(ResultSet::class.java)
        `when`(storage.getPlatformStorage()).thenReturn(pool)
        `when`(pool.connection).thenReturn(connection)
        `when`(connection.createStatement()).thenReturn(mock(Statement::class.java))
        `when`(connection.prepareStatement(anyString())).thenReturn(query)
        `when`(query.executeQuery()).thenReturn(rows)
        val study = UUID.randomUUID()
        val participant = "P-1"
        val token = MessageDigest.getInstance("MD5").digest("$study:$participant".toByteArray()).joinToString("") { "%02x".format(it) }
        val completed = RestoredDeletionOperation(UUID.randomUUID(), study, "original-proof-reference", participant, token,
            DataDeletionMode.COLLECTED_DATA_PURGE, "COMPLETED", "fixture", UUID.randomUUID(), null, null, null, true)
        val reconciler = RestoreContinuityReconciler(storage, mock(DataDeletionOrchestrator::class.java))
        val method = RestoreContinuityReconciler::class.java.getDeclaredMethod("withRestoredSubject", RestoredDeletionOperation::class.java).apply {
            isAccessible = true }
        fun resolve(operation: RestoredDeletionOperation) = method.invoke(reconciler, operation) as RestoredDeletionOperation
        assertNull("an absent completion needs proof restoration, not physical replay", resolve(completed).participantId)
        assertEquals("held operations keep their subject", participant, resolve(completed.copy(status = "HELD", hasSourceTombstone =
            false)).participantId)
        `when`(rows.next()).thenReturn(true, false)
        `when`(rows.getString(1)).thenReturn(participant)
        assertEquals("physical replay stays subject-bound", participant, resolve(completed).participantId)
    }
}
