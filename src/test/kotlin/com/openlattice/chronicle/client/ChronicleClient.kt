package com.openlattice.chronicle.client

import com.openlattice.chronicle.ChronicleStudyApi
import com.openlattice.chronicle.admin.AdminApi
import com.openlattice.chronicle.api.ChronicleApi
import com.openlattice.chronicle.authorization.AuthorizationsApi
import com.openlattice.chronicle.authorization.PermissionsApi
import com.openlattice.chronicle.candidates.CandidateApi
import com.openlattice.chronicle.export.ExportApi
import com.openlattice.chronicle.notifications.NotificationApi
import com.openlattice.chronicle.organizations.OrganizationApi
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyComplianceApi
import com.openlattice.chronicle.study.StudyLimitsApi
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.users.PrincipalApi

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class ChronicleClient(jwt: () -> String) {
    private val retrofit = RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE, jwt)
    val studyApi: StudyApi = retrofit.create(StudyApi::class.java)
    val exportApi: ExportApi = retrofit.create(ExportApi::class.java)
    val timeUseDiaryApi: TimeUseDiaryApi = retrofit.create(TimeUseDiaryApi::class.java)
    val candidateApi: CandidateApi = retrofit.create(CandidateApi::class.java)
    val organizationApi: OrganizationApi = retrofit.create(OrganizationApi::class.java)
    val surveyApi: SurveyApi = retrofit.create(SurveyApi::class.java)
    val principalApi: PrincipalApi = retrofit.create(PrincipalApi::class.java)
    val legacyChronicleStudyApi: ChronicleStudyApi = retrofit.create(ChronicleStudyApi::class.java)
    val adminApi: AdminApi = retrofit.create(AdminApi::class.java)
    val authorizationsApi: AuthorizationsApi = retrofit.create(AuthorizationsApi::class.java)
    val permissionsApi: PermissionsApi = retrofit.create(PermissionsApi::class.java)
    val notificationApi: NotificationApi = retrofit.create(NotificationApi::class.java)
    val studyComplianceApi: StudyComplianceApi = retrofit.create(StudyComplianceApi::class.java)
    val studyLimitsApi: StudyLimitsApi = retrofit.create(StudyLimitsApi::class.java)

    // Test-only APIs that fix Retrofit void-return and Kotlin wildcard issues
    val testStudyApi: TestStudyApi = retrofit.create(TestStudyApi::class.java)
    val testStudyLimitsApi: TestStudyLimitsApi = retrofit.create(TestStudyLimitsApi::class.java)
    val testAdminApi: TestAdminApi = retrofit.create(TestAdminApi::class.java)
    val testOrganizationApi: TestOrganizationApi = retrofit.create(TestOrganizationApi::class.java)
    val testNotificationApi: TestNotificationApi = retrofit.create(TestNotificationApi::class.java)
    val testStudyComplianceApi: TestStudyComplianceApi = retrofit.create(TestStudyComplianceApi::class.java)
    val testAuthorizationsApi: TestAuthorizationsApi = retrofit.create(TestAuthorizationsApi::class.java)
    val testPermissionsApi: TestPermissionsApi = retrofit.create(TestPermissionsApi::class.java)
    val testTimeUseDiaryApi: TestTimeUseDiaryApi = retrofit.create(TestTimeUseDiaryApi::class.java)
    val testPrincipalApi: TestPrincipalApi = retrofit.create(TestPrincipalApi::class.java)
    val testExportApi: TestExportApi = retrofit.create(TestExportApi::class.java)

    @Deprecated("This API is being deprecated.", level = DeprecationLevel.WARNING)
    val chronicleApi: ChronicleApi = retrofit.create(ChronicleApi::class.java)
}
