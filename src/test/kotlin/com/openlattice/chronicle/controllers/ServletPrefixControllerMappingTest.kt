package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.organizations.OrganizationApi
import com.openlattice.chronicle.organizations.OrganizationMemberApi
import com.openlattice.chronicle.study.StudyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.Method

class ServletPrefixControllerMappingTest {

    @Test
    fun `full controller bases preserve direct and servlet-stripped registrations`() {
        val studyMappings = setOf(StudyApi.BASE, StudyApi.CONTROLLER)
        val expectedMappings = mapOf(
            AnonymizationController::class.java to studyMappings,
            DashboardController::class.java to studyMappings,
            DataDeletionController::class.java to studyMappings,
            DataQualityController::class.java to studyMappings,
            ParticipantPurgeController::class.java to studyMappings,
            PipelineController::class.java to studyMappings,
            RoleController::class.java to studyMappings,
            StudyLifecycleController::class.java to studyMappings,
            WebhookController::class.java to studyMappings,
            ApiKeyController::class.java to studyMappings,
            StudyLimitsController::class.java to setOf("/limits", "/v3/limits"),
            OrganizationMemberController::class.java to setOf(
                OrganizationMemberApi.ORGANIZATION_BASE,
                OrganizationApi.CONTROLLER,
            ),
            MobileEnrollmentController::class.java to setOf(
                "/chronicle/v4/mobile/enrollments",
                "/v4/mobile/enrollments",
            ),
        )

        expectedMappings.forEach { (controller, expected) ->
            assertEquals(
                "${controller.simpleName} must retain both routing forms",
                expected,
                requestMappingPaths(controller.getAnnotation(RequestMapping::class.java)),
            )
        }
    }

    @Test
    fun `every non-exempt chronicle mapping has a servlet-stripped twin`() {
        val controllers = discoverControllers()
        assertTrue(
            "Controller scan must include the observed E2E failure paths",
            controllers.containsAll(
                setOf(
                    StudyLifecycleController::class.java,
                    WebhookController::class.java,
                    MobileEnrollmentController::class.java,
                    ParticipantFormAccessController::class.java,
                )
            ),
        )
        assertTrue(
            "Controller scan must include REST controllers outside the controllers subpackage",
            controllers.any { controller -> controller.simpleName == "EncryptionHealthService" },
        )
        val handlerMapping = InspectableRequestMappingHandlerMapping()

        controllers.forEach { controller ->
            val controllerPaths = requestMappingPaths(
                controller.getAnnotation(RequestMapping::class.java),
            )
            val knownException = knownControllerMappingExceptions[controller]
            if (knownException == null) {
                assertServletRelativeTwins(
                    controllerPaths,
                    controller.simpleName,
                )
            } else {
                assertEquals(
                    "${controller.simpleName} mapping exception must remain exact",
                    knownException,
                    controllerPaths,
                )
                return@forEach
            }
            controller.methods.forEach { method ->
                handlerMapping.mappingFor(method, controller)?.let { mapping ->
                    assertServletRelativeTwins(
                        mapping.patternValues,
                        "${controller.simpleName}.${method.name}",
                    )
                }
            }
        }
    }

    private fun discoverControllers(): Set<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        return scanner.findCandidateComponents(APPLICATION_PACKAGE)
            .mapTo(mutableSetOf()) { candidate ->
                Class.forName(requireNotNull(candidate.beanClassName))
            }
    }

    private fun assertServletRelativeTwins(paths: Set<String>, location: String) {
        paths.asSequence()
            .filter { path -> path == SERVLET_PREFIX || path.startsWith("$SERVLET_PREFIX/") }
            .forEach { fullPath ->
                val servletRelativePath = fullPath.removePrefix(SERVLET_PREFIX).ifEmpty { "/" }
                assertTrue(
                    "$location maps $fullPath but does not register $servletRelativePath",
                    servletRelativePath in paths,
                )
            }
    }

    private fun requestMappingPaths(mapping: RequestMapping?): Set<String> = buildSet {
        mapping?.let {
            addAll(it.value)
            addAll(it.path)
        }
    }

    private class InspectableRequestMappingHandlerMapping : RequestMappingHandlerMapping() {
        fun mappingFor(method: Method, controller: Class<*>): RequestMappingInfo? {
            return getMappingForMethod(method, controller)
        }
    }

    private companion object {
        const val APPLICATION_PACKAGE = "com.openlattice.chronicle"
        const val SERVLET_PREFIX = "/chronicle"

        val knownControllerMappingExceptions = emptyMap<Class<*>, Set<String>>()
    }
}
