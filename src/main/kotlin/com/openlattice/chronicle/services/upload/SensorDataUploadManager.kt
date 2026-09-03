package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.sensorkit.SensorDataSample
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public interface SensorDataUploadManager {
    public fun upload(
            studyId: UUID,
            participantId: String,
            deviceId: UUID,
            data: List<SensorDataSample>
    ): Int
}
