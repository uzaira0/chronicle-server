package com.openlattice.chronicle.services.upload

import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.android.ChronicleUsageEvent
import java.time.OffsetDateTime
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public interface AppDataUploadManager {
    public fun upload(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<SetMultimap<UUID, Any>>,
        uploadedAt: OffsetDateTime = OffsetDateTime.now(),
    ): Int

    public fun uploadAndroidUsageEvents(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<ChronicleUsageEvent>,
        uploadedAt: OffsetDateTime = OffsetDateTime.now(),
    ): Int

    public fun moveToEventStorage()
}
