package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.deletion.DeleteParticipantActivityRecognitionEventsData
import com.openlattice.chronicle.deletion.DeleteParticipantAndroidSensorData
import com.openlattice.chronicle.deletion.DeleteParticipantAmbientAudioData
import com.openlattice.chronicle.deletion.DeleteParticipantAppAudioActivityData
import com.openlattice.chronicle.deletion.DeleteParticipantAppAudioContentData
import com.openlattice.chronicle.deletion.DeleteParticipantAppNetworkUsageData
import com.openlattice.chronicle.deletion.DeleteParticipantAppUsageSurveyData
import com.openlattice.chronicle.deletion.DeleteParticipantBatteryTelemetryData
import com.openlattice.chronicle.deletion.DeleteParticipantConnectivityStateEventsData
import com.openlattice.chronicle.deletion.DeleteParticipantDeviceSettingsData
import com.openlattice.chronicle.deletion.DeleteParticipantHealthMetricsData
import com.openlattice.chronicle.deletion.DeleteParticipantInteractionEventsData
import com.openlattice.chronicle.deletion.DeleteParticipantNotificationActivityData
import com.openlattice.chronicle.deletion.DeleteParticipantPreprocessedUsageData
import com.openlattice.chronicle.deletion.DeleteParticipantQuestionnaireSubmissionData
import com.openlattice.chronicle.deletion.DeleteParticipantRegisteredAssetData
import com.openlattice.chronicle.deletion.DeleteParticipantSensorData
import com.openlattice.chronicle.deletion.DeleteParticipantSleepEventsData
import com.openlattice.chronicle.deletion.DeleteParticipantStatsData
import com.openlattice.chronicle.deletion.DeleteParticipantTUDSubmissionData
import com.openlattice.chronicle.deletion.DeleteParticipantUploadBufferData
import com.openlattice.chronicle.deletion.DeleteParticipantUsageData
import com.openlattice.chronicle.deletion.DeleteParticipantUsageStatsData
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.jobs.ChronicleJobDefinition
import java.util.UUID

public object ParticipantDeletionPlan {
    public fun jobs(
        studyId: UUID,
        participantIds: Set<String>,
        contact: String,
        nextJobId: () -> UUID,
    ): List<ChronicleJob> {
        fun job(definition: ChronicleJobDefinition): ChronicleJob = ChronicleJob(
            id = nextJobId(),
            contact = contact,
            definition = definition,
        )

        val dedicated = listOf(
            job(DeleteParticipantUsageData(studyId, participantIds)),
            job(DeleteParticipantUsageStatsData(studyId, participantIds)),
            job(DeleteParticipantPreprocessedUsageData(studyId, participantIds)),
            job(DeleteParticipantSensorData(studyId, participantIds)),
            job(DeleteParticipantAndroidSensorData(studyId, participantIds)),
            job(DeleteParticipantTUDSubmissionData(studyId, participantIds)),
            job(DeleteParticipantAppUsageSurveyData(studyId, participantIds)),
            job(DeleteParticipantQuestionnaireSubmissionData(studyId, participantIds)),
            job(DeleteParticipantStatsData(studyId, participantIds)),
            job(DeleteParticipantUploadBufferData(studyId, participantIds)),
            job(DeleteParticipantBatteryTelemetryData(studyId, participantIds)),
            job(DeleteParticipantInteractionEventsData(studyId, participantIds)),
            job(DeleteParticipantAppAudioActivityData(studyId, participantIds)),
            job(DeleteParticipantAppAudioContentData(studyId, participantIds)),
            job(DeleteParticipantAmbientAudioData(studyId, participantIds)),
            job(DeleteParticipantNotificationActivityData(studyId, participantIds)),
            job(DeleteParticipantSleepEventsData(studyId, participantIds)),
            job(DeleteParticipantActivityRecognitionEventsData(studyId, participantIds)),
            job(DeleteParticipantHealthMetricsData(studyId, participantIds)),
            job(DeleteParticipantConnectivityStateEventsData(studyId, participantIds)),
            job(DeleteParticipantAppNetworkUsageData(studyId, participantIds)),
            job(DeleteParticipantDeviceSettingsData(studyId, participantIds)),
        )
        val registered = ChronicleDataAssetRegistry.participantAssets
            .filterNot { it.handledByDedicatedJob }
            .map { asset ->
                job(DeleteParticipantRegisteredAssetData(studyId, participantIds, asset.id))
            }
        check(dedicated.size + registered.size == ChronicleDataAssetRegistry.participantAssets.size) {
            "Participant deletion plan does not match the canonical data-asset registry"
        }
        return dedicated + registered
    }
}
