package com.openlattice.chronicle.postgres

import java.sql.PreparedStatement
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

internal fun PreparedStatement.bindCollectionEvent(
    studyId: UUID,
    participantId: String,
    eventId: String,
    timestamp: OffsetDateTime,
    timezone: String,
) {
    setObject(1, studyId)
    setString(2, participantId)
    setString(3, eventId)
    setObject(4, timestamp)
    setString(5, timezone)
}

internal fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
    if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
}

internal fun PreparedStatement.setLongOrNull(index: Int, value: Long?) {
    if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
}

internal fun PreparedStatement.setBooleanOrNull(index: Int, value: Boolean?) {
    if (value != null) setBoolean(index, value) else setNull(index, Types.BOOLEAN)
}

internal fun PreparedStatement.setDoubleOrNull(index: Int, value: Double?) {
    if (value != null) setDouble(index, value) else setNull(index, Types.DOUBLE)
}

internal fun String.postgresText(): String = replace("\u0000", "")
