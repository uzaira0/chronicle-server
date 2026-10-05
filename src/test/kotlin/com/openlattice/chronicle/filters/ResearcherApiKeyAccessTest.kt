package com.openlattice.chronicle.filters

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.audit.AuditLogEntry
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.aspects.StudyAuthorizationAspect
import com.openlattice.chronicle.controllers.ExportController
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.controllers.kAny
import com.openlattice.chronicle.controllers.kAnyString
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportJobInfo
import com.openlattice.chronicle.export.ExportJobStatus
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.export.ExportService
import com.openlattice.chronicle.study.ParticipantDataType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.OutputStream
import java.lang.reflect.Method
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.UUID

/**
 * W-4: a researcher (non-device) API key authenticated but every study endpoint answered 403, because
 * neither authorization path knew about keys. The key must now work on its own study within its scope,
 * and nowhere else.
 */
class ResearcherApiKeyAccessTest {

    @Test
    fun persistedRoleOnlyCreatorWorksUntilRoleMembershipIsRemoved() {
        val storage = Mockito.mock(com.openlattice.chronicle.storage.StorageResolver::class.java)
        val source = Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java)
        val connection = Mockito.mock(java.sql.Connection::class.java)
        val statement = Mockito.mock(java.sql.PreparedStatement::class.java)
        val rows = Mockito.mock(java.sql.ResultSet::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenReturn(source)
        Mockito.`when`(source.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true, false, false)
        Mockito.`when`(rows.getString(1)).thenReturn("ROLE")
        Mockito.`when`(rows.getString(2)).thenReturn("research-team")
        val role = com.openlattice.chronicle.authorization.Principal(
            com.openlattice.chronicle.authorization.PrincipalType.ROLE, "research-team")
        val resolver = com.openlattice.chronicle.authorization.CreatorPrincipalResolver(storage,
            com.openlattice.chronicle.configuration.ChronicleAuthConfiguration())
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenAnswer {
            val principals = it.getArgument<Set<com.openlattice.chronicle.authorization.Principal>>(1)
            role in principals
        }
        val service = Mockito.mock(ApiKeyService::class.java)
        val info = keyInfo(ApiKeyScope.READ_ONLY).copy(createdBy = "role-only-creator")
        Mockito.`when`(service.authenticateApiKey("role-key")).thenReturn(info)
        val filter = ApiKeyAuthenticationFilter(service, authorizationManager, creatorPrincipals = resolver::resolve)
        for (expected in listOf(200, 403)) {
            SecurityContextHolder.clearContext()
            val request = MockHttpServletRequest("GET", "/chronicle/v3/study/$studyA/participants")
                .apply { addHeader("X-Api-Key", "role-key") }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, FilterChain { _, _ -> })
            assertEquals(expected, response.status)
        }
    }

    private val studyA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val studyB = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)

    @Before
    fun setUp() {
        TestSecurityUtils.initPrincipalsIfNeeded()
        // A key holds no ACEs: the ACL store refuses everything it is asked about.
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)
    }

    @After
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun keyInfo(scope: ApiKeyScope, participantId: String? = null) = ApiKeyInfo(
        keyId = UUID.randomUUID(),
        studyId = studyA,
        prefix = "res00000",
        name = "researcher",
        scope = scope,
        createdAt = OffsetDateTime.now(),
        expiresAt = OffsetDateTime.now().plusDays(30),
        participantId = participantId,
        deviceId = participantId?.let { UUID.randomUUID() },
    )

    private fun authenticate(info: ApiKeyInfo) {
        SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(
            principal = "apikey:${info.keyId}",
            keyId = info.keyId,
            studyId = info.studyId,
            participantId = info.participantId,
            deviceId = info.deviceId,
            scope = info.scope,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
        )
    }

    private val aclComponent = object : AuthorizingComponent {
        override val authorizationManager = this@ResearcherApiKeyAccessTest.authorizationManager
        override val auditingManager = this@ResearcherApiKeyAccessTest.auditingManager
    }

    private fun allowed(check: () -> Unit): Boolean = runCatching(check).fold(
        onSuccess = { true },
        onFailure = { if (it is ForbiddenException) false else throw it },
    )

    @Test
    fun bothAuthorizationPathsHonourTheScopeOnlyOnTheKeysOwnStudy() {
        val studyAuthorization = StudyAuthorizationService(authorizationManager, auditingManager)
        val study = AclKey(studyA)
        val other = AclKey(studyB)
        fun acl(permission: com.openlattice.chronicle.authorization.Permission, key: AclKey = study) =
            allowed { aclComponent.accessCheck(key, EnumSet.of(permission)) }
        fun semantic(permission: StudyPermission, studyId: UUID = studyA) =
            allowed { studyAuthorization.requirePermission(studyId, permission) }
        val read = com.openlattice.chronicle.authorization.Permission.READ
        val write = com.openlattice.chronicle.authorization.Permission.WRITE
        val owner = com.openlattice.chronicle.authorization.Permission.OWNER

        authenticate(keyInfo(ApiKeyScope.READ_ONLY))
        assertTrue(acl(read))
        assertTrue(allowed { aclComponent.ensureReadAccess(study) })
        assertTrue(aclComponent.isAuthorized(read).test(study))
        assertTrue(semantic(StudyPermission.READ_STUDY))
        assertTrue(semantic(StudyPermission.EXPORT_DATA))
        assertTrue(!acl(write) && !semantic(StudyPermission.MODIFY_STUDY) && !semantic(StudyPermission.MANAGE_PARTICIPANTS))

        authenticate(keyInfo(ApiKeyScope.WRITE))
        assertTrue(acl(write) && semantic(StudyPermission.MODIFY_STUDY) && semantic(StudyPermission.MANAGE_SURVEYS))
        assertTrue(!acl(owner) && !semantic(StudyPermission.DELETE_DATA))

        authenticate(keyInfo(ApiKeyScope.ADMIN))
        assertTrue(acl(owner) && semantic(StudyPermission.DELETE_DATA))
        for (never in listOf(StudyPermission.MANAGE_PERMISSIONS, StudyPermission.VIEW_AUDIT_LOG, StudyPermission.ADMIN)) {
            assertTrue("$never is never granted to a key", !semantic(never))
        }
        assertTrue("a key is never a system admin", !aclComponent.isAdmin())

        for (scope in ApiKeyScope.values()) {
            authenticate(keyInfo(scope))
            assertTrue("$scope key on another study", !acl(read, other) && !semantic(StudyPermission.READ_STUDY, studyB))
            assertTrue(!allowed { aclComponent.ensureReadAccess(setOf(study, other)) })
        }

        // Device keys are not researcher keys: they still have no study ACL access at all.
        authenticate(keyInfo(ApiKeyScope.WRITE, participantId = "participant-p"))
        assertTrue(!acl(read) && !semantic(StudyPermission.READ_STUDY))
    }

    @Test
    fun readOnlyKeyCreatesAndDownloadsAnExportThroughFilterAndAspect() {
        val apiKeyService = Mockito.mock(ApiKeyService::class.java)
        val exportService = Mockito.mock(ExportService::class.java)
        val auditService = Mockito.mock(AuditService::class.java)
        val info = keyInfo(ApiKeyScope.READ_ONLY).copy(createdBy = "key-creator")
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_res00000_raw")).thenReturn(info)
        val exportId = UUID.randomUUID()
        val job = ExportJobInfo(exportId, studyA, ExportJobStatus.COMPLETED, ExportFormat.CSV, OffsetDateTime.now())
        Mockito.`when`(exportService.createAsyncExport(kAny(), kAnyString(), kAny())).thenReturn(job)
        Mockito.`when`(exportService.getExportStatusForDownload(kAny(), kAny(), kAnyString())).thenReturn(job)
        val factory = AspectJProxyFactory(ExportController(exportService, auditService))
        factory.isProxyTargetClass = true
        factory.addAspect(StudyAuthorizationAspect(StudyAuthorizationService(authorizationManager, auditingManager)))
        val controller: ExportController = factory.getProxy()
        val request = ExportRequest(dataTypes = setOf(ParticipantDataType.UsageEvents))
        // The key's creator reads the study; the key itself still holds no ACEs (setUp).
        val creatorAccess = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(creatorAccess.checkIfHasPermissions(kAny(), kAny(), kAny())).thenAnswer { call ->
            call.getArgument<EnumSet<Permission>>(2) == EnumSet.of(Permission.READ)
        }
        val filter = ApiKeyAuthenticationFilter(apiKeyService, creatorAccess)

        fun call(method: String, uri: String, handler: (HttpServletResponse) -> Unit): MockHttpServletResponse {
            val servletRequest = MockHttpServletRequest(method, uri).apply {
                requestURI = uri
                addHeader("X-Api-Key", "ck_res00000_raw")
            }
            val response = MockHttpServletResponse()
            filter.doFilter(servletRequest, response, FilterChain { _, res -> handler(res as HttpServletResponse) })
            return response
        }

        val base = "/chronicle/v3/study/$studyA/export"
        assertEquals(200, call("POST", "$base/async") { controller.createAsyncExport(studyA, request) }.status)
        assertEquals(200, call("GET", "$base/$exportId/download") { controller.downloadExportFile(studyA, exportId, it) }.status)
        Mockito.verify(exportService).createAsyncExport(studyA, "apikey:${info.keyId}", request)
        Mockito.verify(exportService).streamExportFile(kAny(), kAny(), kAnyString(), kAny<OutputStream>())
        val audit = argumentCaptor<AuditLogEntry>()
        Mockito.verify(auditService, Mockito.times(2)).log(audit.capture())
        assertTrue("audit rows must name the key", audit.allValues.all { it.userId == info.keyId })

        // The aspect alone still refuses the key on another study.
        assertThrows(ForbiddenException::class.java) { controller.createAsyncExport(studyB, request) }
        Mockito.verify(exportService, Mockito.never()).createAsyncExport(kEq(studyB), kAnyString(), kAny())
    }

    /**
     * Every route in ResearcherApiKeyRoutes reaches exactly the intended handler, and no other handler in the
     * application is reachable with a key: a new endpoint stays closed to keys until it is listed here.
     */
    @Test
    fun routeTableReachesOnlyTheIntendedHandlers() {
        val sample = studyA.toString()
        val handlerMapping = InspectableRequestMappingHandlerMapping()
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        val reachable = sortedMapOf<String, ApiKeyScope>()
        scanner.findCandidateComponents("com.openlattice.chronicle").forEach { candidate ->
            val controller = Class.forName(requireNotNull(candidate.beanClassName))
            controller.methods.forEach { method ->
                val mapping = handlerMapping.mappingFor(method, controller) ?: return@forEach
                val methods = mapping.methodsCondition.methods.ifEmpty { RequestMethod.values().toSet() }
                mapping.patternValues.forEach { pattern ->
                    val uri = (if (pattern.startsWith("/chronicle/")) pattern else "/chronicle$pattern")
                        .replace(Regex("""\{[^}]*\}"""), sample)
                    methods.forEach { httpMethod ->
                        ResearcherApiKeyRoutes.match(httpMethod.name, uri)?.let {
                            reachable["${controller.simpleName}.${method.name} $httpMethod"] = it.scope
                        }
                    }
                }
            }
        }

        val expected = sortedMapOf(
            "StudyController.getStudy GET" to ApiKeyScope.READ_ONLY,
            "StudyController.getStudySettings GET" to ApiKeyScope.READ_ONLY,
            "StudyController.getStudySetting GET" to ApiKeyScope.READ_ONLY,
            "StudyController.getStudyParticipants GET" to ApiKeyScope.READ_ONLY,
            "StudyController.getParticipantStats GET" to ApiKeyScope.READ_ONLY,
            "StudyController.getParticipantsData GET" to ApiKeyScope.READ_ONLY,
            "StudyController.getStudyDevices GET" to ApiKeyScope.READ_ONLY,
            "ExportController.listExports GET" to ApiKeyScope.READ_ONLY,
            "ExportController.createAsyncExport POST" to ApiKeyScope.READ_ONLY,
            "ExportController.getExportStatus GET" to ApiKeyScope.READ_ONLY,
            "ExportController.downloadExportFile GET" to ApiKeyScope.READ_ONLY,
            "SurveyController.getAppsFilteredForStudyAppUsageSurvey GET" to ApiKeyScope.READ_ONLY,
            "SurveyController.getQuestionnaireResponses GET" to ApiKeyScope.READ_ONLY,
            "TimeUseDiaryController.getStudyTUDSubmissions GET" to ApiKeyScope.READ_ONLY,
            "TimeUseDiaryController.getParticipantsTudSubmissions GET" to ApiKeyScope.READ_ONLY,
            "StudyController.registerParticipant POST" to ApiKeyScope.WRITE,
            "StudyController.updateParticipationStatus PATCH" to ApiKeyScope.WRITE,
            "StudyController.updateParticipantAnnotations PATCH" to ApiKeyScope.WRITE,
            "StudyController.updateStudySettings PATCH" to ApiKeyScope.WRITE,
            "StudyController.setChronicleDataCollectionSettings PUT" to ApiKeyScope.WRITE,
            "SurveyController.createQuestionnaire POST" to ApiKeyScope.WRITE,
            "SurveyController.updateQuestionnaire PATCH" to ApiKeyScope.WRITE,
            "SurveyController.setAppsFilteredForStudyAppUsageSurvey PUT" to ApiKeyScope.WRITE,
            "SurveyController.filterAppForStudyAppUsageSurvey PATCH" to ApiKeyScope.WRITE,
            "SurveyController.allowAppForStudyAppUsageSurvey DELETE" to ApiKeyScope.WRITE,
            "StudyController.deleteStudyParticipants DELETE" to ApiKeyScope.ADMIN,
            "ParticipantPurgeController.previewParticipantPurge GET" to ApiKeyScope.ADMIN,
            "ParticipantPurgeController.executeParticipantPurge POST" to ApiKeyScope.ADMIN,
            "DataDeletionController.getOperation GET" to ApiKeyScope.ADMIN,
            "SurveyController.deleteQuestionnaire DELETE" to ApiKeyScope.ADMIN,
        )
        assertEquals(expected, reachable)
    }

    private class InspectableRequestMappingHandlerMapping : RequestMappingHandlerMapping() {
        fun mappingFor(method: Method, controller: Class<*>): RequestMappingInfo? = getMappingForMethod(method, controller)
    }
}
