package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.storage.ParticipantPurgeCutoff
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.mockito.Mockito
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime

internal fun stubUploadReceiptTime(connection: Connection, receivedAt: OffsetDateTime = OffsetDateTime.now()) {
    val rows = Mockito.mock(ResultSet::class.java)
    val statement = Mockito.mock(PreparedStatement::class.java)
    Mockito.`when`(rows.next()).thenReturn(true)
    Mockito.`when`(rows.getObject(1, OffsetDateTime::class.java)).thenReturn(receivedAt)
    Mockito.`when`(statement.executeQuery()).thenReturn(rows)
    Mockito.`when`(connection.prepareStatement(ParticipantStatsMapstore.RECEIPT_TIME_SQL)).thenReturn(statement)
    Mockito.`when`(connection.createArrayOf(Mockito.anyString(), org.mockito.kotlin.any()))
        .thenReturn(Mockito.mock(java.sql.Array::class.java))
}

internal fun stubOpenCollection(connection: Connection) {
    val rows = Mockito.mock(ResultSet::class.java)
    val statement = Mockito.mock(PreparedStatement::class.java)
    Mockito.`when`(rows.next()).thenReturn(true)
    Mockito.`when`(rows.getString("lifecycle_status")).thenReturn(StudyLifecycleStatus.ACTIVE.name)
    Mockito.`when`(rows.getString("participation_status")).thenReturn(ParticipationStatus.ENROLLED.name)
    stubNoClosedCaptureWindows(rows)
    Mockito.`when`(statement.executeQuery()).thenReturn(rows)
    Mockito.`when`(connection.prepareStatement(ParticipantCollectionGate.WINDOW_QUERY)).thenReturn(statement)
}

/** The gate row of a study and participant that were never paused or archived. */
internal fun stubNoClosedCaptureWindows(rows: ResultSet) {
    for (column in listOf("closed_from", "closed_until")) {
        val empty = Mockito.mock(java.sql.Array::class.java)
        Mockito.`when`(empty.array).thenReturn(emptyArray<java.sql.Timestamp>())
        Mockito.`when`(rows.getArray(column)).thenReturn(empty)
    }
}

internal fun stubNoPurgeCutoff(connection: Connection) {
    val rows = Mockito.mock(ResultSet::class.java)
    val statement = Mockito.mock(PreparedStatement::class.java)
    Mockito.`when`(statement.executeQuery()).thenReturn(rows)
    Mockito.`when`(connection.prepareStatement(ParticipantPurgeCutoff.LOAD_QUERY)).thenReturn(statement)
}
