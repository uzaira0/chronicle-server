package com.openlattice.chronicle.services.surveys

import com.openlattice.chronicle.data.LegacyChronicleQuestionnaire
import com.openlattice.chronicle.survey.AppUsage
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*


/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
// reason: public API surface — the complete surveys/questionnaire contract; splitting would break implementors
@Suppress("TooManyFunctions")
public interface SurveysManager {

    public fun getLegacyQuestionnaire(
        organizationId: UUID,
        studyId: UUID,
        questionnaireEKID: UUID,
    ): LegacyChronicleQuestionnaire

    public fun getLegacyStudyQuestionnaires(organizationId: UUID, studyId: UUID): Map<UUID, Map<FullQualifiedName, Set<Any>>>
    public fun submitLegacyQuestionnaire(
        organizationId: UUID,
        studyId: UUID,
        participantId: String,
        questionnaireResponses: Map<UUID, Map<FullQualifiedName, Set<Any>>>,
    )

    public fun submitAppUsageSurvey(
        studyId: UUID,
        participantId: String,
        surveyResponses: List<AppUsage>,
    )

    public fun getAndroidAppUsageData(
        studyId: UUID,
        participantId: String,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): List<AppUsage>

    public fun createQuestionnaire(
        studyId: UUID,
        questionnaire: Questionnaire,
    ): UUID

    public fun getQuestionnaire(
        studyId: UUID,
        questionnaireId: UUID,
    ): Questionnaire

    public fun deleteQuestionnaire(
        studyId: UUID,
        questionnaireId: UUID,
    )

    public fun updateQuestionnaire(
        studyId: UUID,
        questionnaireId: UUID,
        update: QuestionnaireUpdate,
    )

    public fun getStudyQuestionnaires(
        studyId: UUID,
    ): List<Questionnaire>

    public fun submitQuestionnaireResponses(
        studyId: UUID,
        participantId: String,
        questionnaireId: UUID,
        responses: List<QuestionnaireResponse>,
    )

    public fun getAppsFilteredForStudyAppUsageSurvey(studyId: UUID): Collection<String>
    public fun setAppsFilteredForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>)
    public fun filterAppForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>)
    public fun allowAppForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>)
    public fun initializeFilterdApps(connection: Connection, studyId: UUID)
    public fun getDeviceUsageData(
        realStudyId: UUID,
        participantId: String,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): DeviceUsage

    public fun computeAggregateUsage(
        startDateTime: OffsetDateTime = OffsetDateTime.now()
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC.normalized())
            .toOffsetDateTime(),
        appUsage: List<AppUsage>,
    ): Map<String, Double>
}
