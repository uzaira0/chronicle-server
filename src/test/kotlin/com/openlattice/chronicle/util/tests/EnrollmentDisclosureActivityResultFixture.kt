package com.openlattice.chronicle.util.tests

import com.openlattice.chronicle.services.participantaccess.EnrollmentDisclosureActivity
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Array as SqlArray
import java.sql.ResultSet

internal object EnrollmentDisclosureActivityResultFixture {
    fun resultSet(activity: EnrollmentDisclosureActivity): ResultSet {
        val pendingParticipantIds = mock<SqlArray>()
        whenever(pendingParticipantIds.array).thenReturn(activity.pendingParticipantIds.toTypedArray())
        return mock<ResultSet>().also { rows ->
            whenever(rows.next()).thenReturn(true)
            whenever(rows.getBoolean(1)).thenReturn(activity.hasActiveEnrollment)
            whenever(rows.getArray(2)).thenReturn(pendingParticipantIds)
        }
    }
}
