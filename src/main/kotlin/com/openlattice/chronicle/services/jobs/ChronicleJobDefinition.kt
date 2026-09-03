package com.openlattice.chronicle.services.jobs

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.openlattice.chronicle.deletion.DeleteParticipantAndroidSensorData
import com.openlattice.chronicle.deletion.DeleteParticipantAmbientAudioData
import com.openlattice.chronicle.deletion.DeleteParticipantAppAudioActivityData
import com.openlattice.chronicle.deletion.DeleteParticipantAppAudioContentData
import com.openlattice.chronicle.deletion.DeleteParticipantAppUsageSurveyData
import com.openlattice.chronicle.deletion.DeleteParticipantBatteryTelemetryData
import com.openlattice.chronicle.deletion.DeleteParticipantInteractionEventsData
import com.openlattice.chronicle.deletion.DeleteParticipantNotificationActivityData
import com.openlattice.chronicle.deletion.DeleteParticipantPreprocessedUsageData
import com.openlattice.chronicle.deletion.DeleteParticipantQuestionnaireSubmissionData
import com.openlattice.chronicle.deletion.DeleteParticipantRegisteredAssetData
import com.openlattice.chronicle.deletion.DeleteParticipantSensorData
import com.openlattice.chronicle.deletion.DeleteParticipantStatsData
import com.openlattice.chronicle.deletion.DeleteParticipantTUDSubmissionData
import com.openlattice.chronicle.deletion.DeleteParticipantUploadBufferData
import com.openlattice.chronicle.deletion.DeleteParticipantUsageData
import com.openlattice.chronicle.deletion.DeleteParticipantUsageStatsData
import com.openlattice.chronicle.deletion.DeleteStudyAppUsageSurveyData
import com.openlattice.chronicle.deletion.DeleteStudyTUDSubmissionData
import com.openlattice.chronicle.deletion.DeleteStudyTableData
import com.openlattice.chronicle.deletion.DeleteStudyUsageData
import com.openlattice.chronicle.pipeline.PipelineJobDefinition
import com.openlattice.chronicle.services.notifications.Notification

/**
 * @author Solomon Tang <solomon@openlattice.com>
 *
 * MIGRATION NOTE: Changed from Id.CLASS to Id.NAME for security (CWE-502).
 * Each subtype has BOTH a short name and its old FQCN as aliases so that
 * existing database rows (serialized with Id.CLASS) continue to deserialize.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    // Study-level jobs
    JsonSubTypes.Type(value = EmptyJobDefinition::class, name = "EmptyJobDefinition"),
    JsonSubTypes.Type(value = EmptyJobDefinition::class, name = "com.openlattice.chronicle.services.jobs.EmptyJobDefinition"),
    JsonSubTypes.Type(value = DeleteStudyUsageData::class, name = "DeleteStudyUsageData"),
    JsonSubTypes.Type(value = DeleteStudyUsageData::class, name = "com.openlattice.chronicle.deletion.DeleteStudyUsageData"),
    JsonSubTypes.Type(value = DeleteStudyTUDSubmissionData::class, name = "DeleteStudyTUDSubmissionData"),
    JsonSubTypes.Type(value = DeleteStudyTUDSubmissionData::class, name = "com.openlattice.chronicle.deletion.DeleteStudyTUDSubmissionData"),
    JsonSubTypes.Type(value = DeleteStudyAppUsageSurveyData::class, name = "DeleteStudyAppUsageSurveyData"),
    JsonSubTypes.Type(value = DeleteStudyAppUsageSurveyData::class, name = "com.openlattice.chronicle.deletion.DeleteStudyAppUsageSurveyData"),
    JsonSubTypes.Type(value = DeleteStudyTableData::class, name = "DeleteStudyTableData"),
    JsonSubTypes.Type(value = DeleteStudyTableData::class, name = "com.openlattice.chronicle.deletion.DeleteStudyTableData"),
    JsonSubTypes.Type(value = PipelineJobDefinition::class, name = "PipelineJobDefinition"),
    JsonSubTypes.Type(value = PipelineJobDefinition::class, name = "com.openlattice.chronicle.pipeline.PipelineJobDefinition"),
    JsonSubTypes.Type(value = Notification::class, name = "Notification"),
    JsonSubTypes.Type(value = Notification::class, name = "com.openlattice.chronicle.services.notifications.Notification"),
    // Participant-level jobs
    JsonSubTypes.Type(value = DeleteParticipantUsageData::class, name = "DeleteParticipantUsageData"),
    JsonSubTypes.Type(value = DeleteParticipantUsageData::class, name = "com.openlattice.chronicle.deletion.DeleteParticipantUsageData"),
    JsonSubTypes.Type(value = DeleteParticipantUsageStatsData::class, name = "DeleteParticipantUsageStatsData"),
    JsonSubTypes.Type(
        value = DeleteParticipantUsageStatsData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantUsageStatsData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantStatsData::class, name = "DeleteParticipantStatsData"),
    JsonSubTypes.Type(
        value = DeleteParticipantStatsData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantStatsData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantTUDSubmissionData::class, name = "DeleteParticipantTUDSubmissionData"),
    JsonSubTypes.Type(
        value = DeleteParticipantTUDSubmissionData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantTUDSubmissionData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantAppUsageSurveyData::class, name = "DeleteParticipantAppUsageSurveyData"),
    JsonSubTypes.Type(
        value = DeleteParticipantAppUsageSurveyData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantAppUsageSurveyData"
    ),
    JsonSubTypes.Type(
        value = DeleteParticipantQuestionnaireSubmissionData::class,
        name = "DeleteParticipantQuestionnaireSubmissionData"
    ),
    JsonSubTypes.Type(
        value = DeleteParticipantQuestionnaireSubmissionData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantQuestionnaireSubmissionData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantPreprocessedUsageData::class, name = "DeleteParticipantPreprocessedUsageData"),
    JsonSubTypes.Type(
        value = DeleteParticipantPreprocessedUsageData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantPreprocessedUsageData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantSensorData::class, name = "DeleteParticipantSensorData"),
    JsonSubTypes.Type(
        value = DeleteParticipantSensorData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantSensorData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantAndroidSensorData::class, name = "DeleteParticipantAndroidSensorData"),
    JsonSubTypes.Type(
        value = DeleteParticipantAndroidSensorData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantAndroidSensorData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantBatteryTelemetryData::class, name = "DeleteParticipantBatteryTelemetryData"),
    JsonSubTypes.Type(
        value = DeleteParticipantBatteryTelemetryData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantBatteryTelemetryData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantInteractionEventsData::class, name = "DeleteParticipantInteractionEventsData"),
    JsonSubTypes.Type(
        value = DeleteParticipantInteractionEventsData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantInteractionEventsData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantAppAudioActivityData::class, name = "DeleteParticipantAppAudioActivityData"),
    JsonSubTypes.Type(
        value = DeleteParticipantAppAudioActivityData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantAppAudioActivityData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantAppAudioContentData::class, name = "DeleteParticipantAppAudioContentData"),
    JsonSubTypes.Type(
        value = DeleteParticipantAppAudioContentData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantAppAudioContentData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantAmbientAudioData::class, name = "DeleteParticipantAmbientAudioData"),
    JsonSubTypes.Type(
        value = DeleteParticipantAmbientAudioData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantAmbientAudioData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantNotificationActivityData::class, name = "DeleteParticipantNotificationActivityData"),
    JsonSubTypes.Type(
        value = DeleteParticipantNotificationActivityData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantNotificationActivityData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantUploadBufferData::class, name = "DeleteParticipantUploadBufferData"),
    JsonSubTypes.Type(
        value = DeleteParticipantUploadBufferData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantUploadBufferData"
    ),
    JsonSubTypes.Type(value = DeleteParticipantRegisteredAssetData::class, name = "DeleteParticipantRegisteredAssetData"),
    JsonSubTypes.Type(
        value = DeleteParticipantRegisteredAssetData::class,
        name = "com.openlattice.chronicle.deletion.DeleteParticipantRegisteredAssetData"
    ),
)
public interface ChronicleJobDefinition
