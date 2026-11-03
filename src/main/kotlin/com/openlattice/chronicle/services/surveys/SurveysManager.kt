package com.openlattice.chronicle.services.surveys

import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.*


/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
// reason: public API surface — the complete surveys/questionnaire contract; splitting would break implementors
@Suppress("TooManyFunctions")
public interface SurveysManager {

    /**
     * The participant's ended survey days with their answer state, or null when the survey does not admit them; the
     * reminder manifest and the picker read it.
     */
    public fun getAppUsageDays(studyId: UUID, participantId: String, rules: AppUsageDayRules): AppUsageSurveyDays?

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
    public fun setAppsFilteredForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>, expectedRevision: Long): Long
    public fun filterAppForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>)
    public fun allowAppForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>)
    public fun initializeFilterdApps(connection: Connection, studyId: UUID)
    public fun getDeviceUsageData(
        realStudyId: UUID,
        participantId: String,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): DeviceUsage

}
