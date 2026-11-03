package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.study.StudyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.MethodParameter
import org.springframework.core.annotation.AnnotatedMethod
import org.springframework.web.util.UriTemplate
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.Method
import java.io.File
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.filters.ConcurrentEditFilter
import org.springframework.web.bind.annotation.RequestMethod
import retrofit2.http.HEAD

class ServletPrefixControllerMappingTest {
    @Test
    fun everyTemplateVariableHasAnExplicitMvcBinding() {
        val mapping = InspectableRequestMappingHandlerMapping()
        val missing = discoverControllers().flatMap { controller ->
            controller.methods.mapNotNull { method ->
                val registration = mapping.mappingFor(method, controller) ?: return@mapNotNull null
                val variables = unboundPathVariables(method, registration.patternValues)
                if (variables.isEmpty()) null else "${controller.simpleName}.${method.name}: $variables"
            }
        }.distinct().sorted()
        assertEquals("Mapped URI variables must bind before invoking a non-null controller argument", emptyList<String>(), missing)
    }

    @Test
    fun bindingInventoryPreservesInheritedOptionalMapAndNoArgumentHandlers() {
        fun method(name: String) = PathBindingExamples::class.java.methods.single { it.name == name }
        val paths = setOf("/study/{studyId}")
        assertEquals(setOf("studyId"), unboundPathVariables(method("missing"), paths))
        for (name in listOf("renamed", "optional", "allVariables", "inherited", "noArguments")) {
            assertEquals(name, emptySet<String>(), unboundPathVariables(method(name), paths + "/study"))
        }
    }

    private fun unboundPathVariables(method: Method, paths: Set<String>): Set<String> {
        // A no-argument handler may intentionally let a request filter consume the URI variables.
        if (method.parameterCount == 0) return emptySet()
        val parameters = AnnotatedMethod(method).methodParameters.mapNotNull { parameter ->
            parameter.initParameterNameDiscovery(DefaultParameterNameDiscoverer())
            parameter.getParameterAnnotation(PathVariable::class.java)?.let { parameter to it }
        }
        val capturesAll = parameters.any { (parameter, annotation) ->
            Map::class.java.isAssignableFrom(parameter.parameterType) && annotation.name.isEmpty() && annotation.value.isEmpty()
        }
        if (capturesAll) return emptySet()
        val bound = parameters.map { (parameter, annotation) -> pathVariableName(parameter, annotation) }.toSet()
        return paths.flatMap { UriTemplate(it).variableNames }.toSet() - bound
    }

    private fun pathVariableName(parameter: MethodParameter, annotation: PathVariable): String = when {
        annotation.name.isNotBlank() -> annotation.name
        annotation.value.isNotBlank() -> annotation.value
        else -> checkNotNull(parameter.parameterName) { "Spring cannot discover the path-variable parameter name" }
    }

    private interface InheritedPathBinding {
        fun inherited(@PathVariable("studyId") studyId: String): String
    }

    private class PathBindingExamples : InheritedPathBinding {
        fun missing(studyId: String): String = studyId
        fun renamed(@PathVariable("studyId") id: String): String = id
        fun optional(@PathVariable(required = false) studyId: String? = null): String? = studyId
        fun allVariables(@PathVariable variables: Map<String, String>): Map<String, String> = variables
        override fun inherited(studyId: String): String = studyId
        fun noArguments() = Unit
    }

    private data class RuntimeRoute(
        val method: RequestMethod,
        val path: String,
        val operation: String,
        val studyPermission: String?,
        val auth: String = "UNDECLARED",
    )

    private fun runtimeRoutes(): List<RuntimeRoute> {
        val handler = InspectableRequestMappingHandlerMapping()
        val declared = ObjectMappers.getJsonMapper().readTree(File(ROUTE_INVENTORY_PATH))
            .associate { "${it["method"].asText()} ${it["path"].asText()}" to (it["auth"]?.asText() ?: "UNDECLARED") }
        return discoverControllers().flatMap { controller ->
            controller.methods.flatMap { method ->
                handler.mappingFor(method, controller)?.let { mapping ->
                    mapping.methodsCondition.methods.ifEmpty { RequestMethod.entries.toSet() }.flatMap { verb ->
                        mapping.patternValues.flatMap { path ->
                            setOf(path, if (path.startsWith(SERVLET_PREFIX)) path else SERVLET_PREFIX + path)
                                .map {
                                    RuntimeRoute(verb, it, "${controller.simpleName}.${method.name}",
                                        (method.getAnnotation(RequiresStudyAccess::class.java)
                                            ?: controller.getAnnotation(RequiresStudyAccess::class.java))?.permission?.name,
                                        declared["${verb.name} $it"] ?: "UNDECLARED")
                                }
                        }
                    }
                }.orEmpty()
            }
        }.plus(ConcurrentEditFilter.revisionReadPaths.map {
            RuntimeRoute(RequestMethod.HEAD, it, "ConcurrentEditFilter.revision", "READ_STUDY",
                declared["HEAD $it"] ?: "UNDECLARED")
        })
            .distinct().sortedWith(compareBy({ it.path }, { it.method.name }))
    }

    @Test
    fun retiredDeviceRoutesAreAbsentAndEveryEditFencePublishesItsRevisionRead() {
        val retired = Regex("/(?:chronicle/)?v3/study/[^/]+/participant/[^/]+/(?:(?:android|ios)/[^/]+(?:/|$)|[^/]+/enroll(?:/|$))")
        assertTrue(runtimeRoutes().none { retired.containsMatchIn(it.path) })
        val fences = ConcurrentEditFilter.revisionReadPaths
        val published = EditRevisionApi::class.java.methods.mapNotNull { it.getAnnotation(HEAD::class.java)?.value }.toSet()
        assertEquals(fences, published)
    }

    /** No client calls these; they exposed other researchers' profiles, every tenant's organizations and org-role writes. */
    @Test
    fun uncalledDirectoryAndOrganizationRoutesAreAbsent() {
        val removed = Regex("^(?:/chronicle)?(?:/principal/users(?:/\\{userId})?" +
            "|/v3/organization/(?:search|\\{organizationId}/(?:members|quotas).*))$")
        val present = runtimeRoutes().filter { removed.matches(it.path) }
            .filterNot { it.method == RequestMethod.DELETE && it.path.endsWith("/principal/users/{userId}") }
        assertEquals(emptyList<RuntimeRoute>(), present)
    }

    @Test
    fun generatedRouteInventoryMatchesEveryControllerMapping() {
        val saved = ObjectMappers.getJsonMapper().readValue(
            File(ROUTE_INVENTORY_PATH), Array<RuntimeRoute>::class.java,
        )
        assertEquals(runtimeRoutes(), saved.toList())
    }

    @Test
    fun `full controller bases preserve direct and servlet-stripped registrations`() {
        val studyMappings = setOf(StudyApi.BASE, StudyApi.CONTROLLER)
        val expectedMappings = mapOf(
            DataDeletionController::class.java to studyMappings,
            ParticipantPurgeController::class.java to studyMappings,
            StudyLifecycleController::class.java to studyMappings,
            WebhookController::class.java to studyMappings,
            ApiKeyController::class.java to studyMappings,
            StudyLimitsController::class.java to setOf("/limits", "/v3/limits"),
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
        // Only the shipped classes: a test's fixture controller is not a product route.
        val production = StudyController::class.java.protectionDomain.codeSource.location
        return scanner.findCandidateComponents(APPLICATION_PACKAGE)
            .map { candidate -> Class.forName(requireNotNull(candidate.beanClassName)) }
            .filterTo(mutableSetOf()) { it.protectionDomain.codeSource.location == production }
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
        /** Generated output, relative to the chronicle-server project directory. */
        const val ROUTE_INVENTORY_PATH = "src/test/resources/route-inventory.json"

        /** Run by `generateRouteInventory`, whose working directory is the project directory. */
        @JvmStatic
        fun main(args: Array<String>) {
            require(args.isEmpty()) { "No arguments: writes $ROUTE_INVENTORY_PATH" }
            File(ROUTE_INVENTORY_PATH).writeText(ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(ServletPrefixControllerMappingTest().runtimeRoutes()) + "\n")
        }

        const val APPLICATION_PACKAGE = "com.openlattice.chronicle"
        const val SERVLET_PREFIX = "/chronicle"

        val knownControllerMappingExceptions = emptyMap<Class<*>, Set<String>>()
    }
}
