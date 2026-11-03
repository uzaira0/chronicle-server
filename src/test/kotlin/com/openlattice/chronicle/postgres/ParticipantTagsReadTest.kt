package com.openlattice.chronicle.postgres

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.PostgresColumns
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

class ParticipantTagsReadTest {
    @Test
    fun persistedTagReadFailuresPropagateInsteadOfReturningAnAuthoritativeEmptySet() {
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(rows.getString(PostgresColumns.PARTICIPANT_ID.name)).thenReturn("tag-read")
        Mockito.`when`(rows.getString(PostgresColumns.PARTICIPATION_STATUS.name)).thenReturn(ParticipationStatus.ENROLLED.name)
        Mockito.`when`(rows.getObject(PostgresColumns.CANDIDATE_ID.name, UUID::class.java)).thenReturn(UUID.randomUUID())
        val failure = SQLException("Stored tag array could not be read")
        Mockito.`when`(rows.getArray(PostgresColumns.PARTICIPANT_TAGS.name)).thenThrow(failure)
        assertSame(failure, assertThrows(SQLException::class.java) { ResultSetAdapters.participant(rows) })
    }
}
