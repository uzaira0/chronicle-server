package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.HealthMetricType
import java.util.UUID

/** A health write carries the exact record-family grant checked in its transaction. */
public class AuthorizedHealthScope internal constructor(
    public val studyId: UUID,
    public val participantId: String,
    private val randomDeviceId: UUID,
    private val recordTypes: Set<HealthConnectRecordType>,
) {
    internal fun requirePermitted(data: List<AndroidHealthMetricEvent>) {
        if (data.any { it.metricType.recordType() !in recordTypes }) {
            throw CollectionHaltedException(studyId, participantId, randomDeviceId)
        }
    }
}

private fun HealthMetricType.recordType(): HealthConnectRecordType? = when (this) {
    HealthMetricType.STEPS -> HealthConnectRecordType.STEPS
    HealthMetricType.DISTANCE -> HealthConnectRecordType.DISTANCE
    HealthMetricType.ACTIVE_CALORIES -> HealthConnectRecordType.ACTIVE_CALORIES_BURNED
    HealthMetricType.TOTAL_CALORIES -> HealthConnectRecordType.TOTAL_CALORIES_BURNED
    HealthMetricType.HEART_RATE -> HealthConnectRecordType.HEART_RATE
    HealthMetricType.RESTING_HEART_RATE -> HealthConnectRecordType.RESTING_HEART_RATE
    HealthMetricType.OXYGEN_SATURATION -> HealthConnectRecordType.OXYGEN_SATURATION
    HealthMetricType.RESPIRATORY_RATE -> HealthConnectRecordType.RESPIRATORY_RATE
    HealthMetricType.EXERCISE_SESSION -> HealthConnectRecordType.EXERCISE
    HealthMetricType.FLOORS_CLIMBED -> HealthConnectRecordType.FLOORS_CLIMBED
    HealthMetricType.HEART_RATE_VARIABILITY -> HealthConnectRecordType.HEART_RATE_VARIABILITY
    HealthMetricType.BODY_TEMPERATURE -> HealthConnectRecordType.BODY_TEMPERATURE
    HealthMetricType.SKIN_TEMPERATURE -> HealthConnectRecordType.SKIN_TEMPERATURE
    HealthMetricType.SLEEP_SESSION, HealthMetricType.SLEEP_STAGE_AWAKE, HealthMetricType.SLEEP_STAGE_LIGHT,
    HealthMetricType.SLEEP_STAGE_DEEP, HealthMetricType.SLEEP_STAGE_REM, HealthMetricType.SLEEP_STAGE_OUT_OF_BED,
    HealthMetricType.SLEEP_STAGE_AWAKE_IN_BED, HealthMetricType.SLEEP_STAGE_UNKNOWN -> HealthConnectRecordType.SLEEP
    HealthMetricType.OTHER -> null
}
