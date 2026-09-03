package com.openlattice.chronicle.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ANDROID_SENSOR_DATA
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SAMPLE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SAMPLE_TIMESTAMP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_ACCURACY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_TIMEZONE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_VALUES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_W
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_X
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_Y
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_Z
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.UUID

/** Shared idempotent writer used by direct ingestion and the legacy upload-buffer drain. */
internal object AndroidSensorDataWriter {
    private val mapper: ObjectMapper = ObjectMappers.newJsonMapper()

    internal val insertSql: String = """
        INSERT INTO ${ANDROID_SENSOR_DATA.name} (
            ${STUDY_ID.name},
            ${PARTICIPANT_ID.name},
            ${SAMPLE_ID.name},
            ${SENSOR_TYPE.name},
            ${SAMPLE_TIMESTAMP.name},
            ${SENSOR_TIMEZONE.name},
            ${DEVICE_ID.name},
            ${SENSOR_X.name},
            ${SENSOR_Y.name},
            ${SENSOR_Z.name},
            ${SENSOR_W.name},
            ${SENSOR_VALUES.name},
            ${SENSOR_ACCURACY.name}
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
        ON CONFLICT (${SAMPLE_ID.name}) DO NOTHING
    """.trimIndent()

    internal fun write(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        samples: List<AndroidSensorSample>,
    ): Int = connection.prepareStatement(insertSql).use { ps ->
        write(ps, studyId, participantId, deviceId, samples)
    }

    internal fun write(
        ps: PreparedStatement,
        studyId: UUID?,
        participantId: String,
        deviceId: UUID?,
        samples: List<AndroidSensorSample>,
    ): Int {
        samples.forEach { sample -> bind(ps, studyId, participantId, deviceId, sample) }
        val results = ps.executeBatch()
        ps.clearBatch()
        return results.sumOf { result ->
            when {
                result >= 0 -> result
                result == Statement.SUCCESS_NO_INFO -> 1
                result == Statement.EXECUTE_FAILED -> throw SQLException("Android sensor batch insert failed")
                else -> throw SQLException("Unexpected Android sensor batch result: $result")
            }
        }
    }

    private fun bind(
        ps: PreparedStatement,
        studyId: UUID?,
        participantId: String,
        deviceId: UUID?,
        sample: AndroidSensorSample,
    ) {
        ps.setObject(1, studyId)
        ps.setString(2, participantId)
        ps.setObject(3, sample.id)
        ps.setString(4, sample.sensor.name)
        ps.setObject(5, sample.timestamp)
        ps.setString(6, sample.timezone)
        ps.setObject(7, deviceId)
        bindNullableFloat(ps, 8, sample.x)
        bindNullableFloat(ps, 9, sample.y)
        bindNullableFloat(ps, 10, sample.z)
        bindNullableFloat(ps, 11, sample.w)
        ps.setString(12, mapper.writeValueAsString(sample.values))
        val accuracy = sample.accuracy
        if (accuracy != null) ps.setInt(13, accuracy) else ps.setNull(13, Types.INTEGER)
        ps.addBatch()
    }

    private fun bindNullableFloat(ps: PreparedStatement, index: Int, value: Float?) {
        if (value != null) ps.setFloat(index, value) else ps.setNull(index, Types.REAL)
    }
}
