package com.openlattice.chronicle.services.upload

import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.util.DeviceIdUtils
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

// upload() enforces the 10,000-entry limit
// nosemgrep: chronicle-upload-unbounded-batch
internal fun AppDataUploadManager.uploadLegacy(
    studies: StudyManager,
    studyId: UUID,
    participantId: String,
    datasourceId: String,
    data: List<SetMultimap<UUID, Any>>,
): Int {
    val realStudyId = checkNotNull(studies.getStudyId(studyId)) { "invalid study id" }
    val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, datasourceId)
    return upload(realStudyId, participantId, deviceId, data)
}
