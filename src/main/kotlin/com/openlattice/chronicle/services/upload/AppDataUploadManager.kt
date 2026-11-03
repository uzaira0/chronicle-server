package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.ChronicleUsageEvent
import java.time.OffsetDateTime
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public interface AppDataUploadManager {
    /** @return client event IDs of the events stored; every other event in [data] was dropped. */
    public fun uploadAndroidUsageEvents(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        data: List<ChronicleUsageEvent>,
        uploadedAt: OffsetDateTime = OffsetDateTime.now(),
    ): Set<UUID>

    public fun moveToEventStorage()
}
