package com.openlattice.chronicle.contract

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.lang.reflect.Method

/**
 * Spec-to-controller parity test.
 *
 * Reads the OpenAPI spec at chronicle-api/chronicle.yaml, extracts all path+method
 * combinations, then uses reflection to find all @RequestMapping/@GetMapping/etc.
 * annotated methods across controller classes. Asserts that every spec endpoint has
 * a corresponding controller method.
 *
 * This is a structural smoke test — it does not verify parameter types or response
 * shapes, only that each declared API endpoint is backed by a controller method.
 */
class SpecToControllerParityTest {

    /**
     * Represents an endpoint declared in the OpenAPI spec.
     */
    data class SpecEndpoint(val path: String, val method: String) {
        override fun toString(): String = "$method ${path}"
    }

    /**
     * Represents a controller endpoint discovered via reflection.
     */
    data class ControllerEndpoint(
        val path: String,
        val method: String,
        val controllerClass: String,
        val methodName: String
    )

    // -------------------------------------------------------------------------
    // OpenAPI spec parsing (minimal YAML line-based parser — no dependency needed)
    // -------------------------------------------------------------------------

    // reason: line-based YAML state machine — the continue statements are the section/path/method
    // dispatch; restructuring the loop control would obscure the parser and risk altering parsing
    @Suppress("LoopWithTooManyJumpStatements")
    private fun parseSpecEndpoints(): List<SpecEndpoint> {
        val specFile = resolveSpecFile()
        val lines = specFile.readLines()
        val endpoints = mutableListOf<SpecEndpoint>()
        var currentPath: String? = null
        var inPaths = false

        for (line in lines) {
            // Detect the start of the paths: section
            if (line.trimEnd() == "paths:") {
                inPaths = true
                continue
            }

            if (!inPaths) continue

            // A new top-level section (components:, tags:, etc.) ends paths
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                inPaths = false
                continue
            }

            // Path entries are indented by 2 spaces
            val pathMatch = Regex("^  (/[^:]+):").find(line)
            if (pathMatch != null) {
                currentPath = pathMatch.groupValues[1].trim()
                continue
            }

            // HTTP methods are indented by 4 spaces
            val methodMatch = Regex("^    (get|post|put|delete|patch|head|options):").find(line)
            if (methodMatch != null && currentPath != null) {
                endpoints.add(SpecEndpoint(currentPath, methodMatch.groupValues[1].uppercase()))
            }
        }

        return endpoints
    }

    private fun resolveSpecFile(): File {
        // From chronicle-server/ submodule
        val fromSubmodule = File("../chronicle-api/chronicle.yaml")
        if (fromSubmodule.exists()) return fromSubmodule

        // From monorepo root
        val fromRoot = File("chronicle-api/chronicle.yaml")
        if (fromRoot.exists()) return fromRoot

        error("OpenAPI spec not found (tried ${fromSubmodule.absolutePath} and ${fromRoot.absolutePath})")
    }

    // -------------------------------------------------------------------------
    // Controller reflection
    // -------------------------------------------------------------------------

    private val controllerPackage = "com.openlattice.chronicle"

    private fun discoverControllerEndpoints(): List<ControllerEndpoint> {
        val endpoints = mutableListOf<ControllerEndpoint>()

        for (clazz in discoverControllerClasses()) {
            val name = clazz.simpleName
            // Get the base path from the class-level @RequestMapping
            val classMapping = clazz.getAnnotation(RequestMapping::class.java)
            val basePaths = classMapping
                ?.let { mapping ->
                    (mapping.value.toList() + mapping.path.toList())
                        .distinct()
                        .ifEmpty { listOf("") }
                }
                ?: listOf("")

            for (method in clazz.declaredMethods) {
                for ((httpMethod, methodPaths) in extractMappings(method)) {
                    endpoints += expandEndpoints(name, method.name, httpMethod, basePaths, methodPaths)
                }
            }
        }

        return endpoints
    }

    private fun discoverControllerClasses(): Set<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        return scanner.findCandidateComponents(controllerPackage)
            .mapTo(mutableSetOf()) { candidate ->
                Class.forName(requireNotNull(candidate.beanClassName))
            }
    }

    /**
     * Expands a single (controller, method, httpMethod) mapping across all base/method path
     * combinations into concrete [ControllerEndpoint] entries.
     */
    private fun expandEndpoints(
        controllerName: String,
        methodName: String,
        httpMethod: String,
        basePaths: List<String>,
        methodPaths: List<String>
    ): List<ControllerEndpoint> {
        val resolvedPaths = methodPaths.ifEmpty { listOf("") }
        val expanded = mutableListOf<ControllerEndpoint>()
        for (basePath in basePaths) {
            for (methodPath in resolvedPaths) {
                expanded.add(
                    ControllerEndpoint(
                        path = normalizePath("$basePath$methodPath"),
                        method = httpMethod,
                        controllerClass = controllerName,
                        methodName = methodName
                    )
                )
            }
        }
        return expanded
    }

    /**
     * Extracts HTTP method + paths from all Spring mapping annotations on a method.
     */
    private fun extractMappings(method: Method): List<Pair<String, List<String>>> {
        val results = mutableListOf<Pair<String, List<String>>>()

        method.getAnnotation(GetMapping::class.java)?.let {
            results.add("GET" to (it.value.toList() + it.path.toList()).distinct())
        }
        method.getAnnotation(PostMapping::class.java)?.let {
            results.add("POST" to (it.value.toList() + it.path.toList()).distinct())
        }
        method.getAnnotation(PutMapping::class.java)?.let {
            results.add("PUT" to (it.value.toList() + it.path.toList()).distinct())
        }
        method.getAnnotation(DeleteMapping::class.java)?.let {
            results.add("DELETE" to (it.value.toList() + it.path.toList()).distinct())
        }
        method.getAnnotation(PatchMapping::class.java)?.let {
            results.add("PATCH" to (it.value.toList() + it.path.toList()).distinct())
        }
        method.getAnnotation(RequestMapping::class.java)?.let { rm ->
            val methods = rm.method.map { it.name }.ifEmpty { listOf("GET") }
            val paths = (rm.value.toList() + rm.path.toList()).distinct()
            for (m in methods) {
                results.add(m to paths)
            }
        }

        return results
    }

    /**
     * Normalizes a path for comparison: strips trailing slashes, collapses double slashes,
     * and converts Spring path variables {foo} to the OpenAPI format (which is also {foo}).
     */
    private fun normalizePath(path: String): String {
        return path
            .replace(Regex("/+"), "/")
            .trimEnd('/')
            .ifEmpty { "/" }
    }

    /**
     * Converts an OpenAPI path template to a normalized form for matching.
     * Both OpenAPI and Spring use {param} syntax, so no conversion needed.
     */
    private fun normalizeSpecPath(path: String): String = normalizePath(path)

    /**
     * Match a controller path against a spec path, treating any {…} segment on
     * either side as a wildcard that matches a single segment. This lets a
     * parameterized controller path (e.g. /settings/type/{settingType}) match
     * a literal spec path (e.g. /settings/type/AndroidSensor).
     */
    private fun pathsMatch(controllerPath: String, specPath: String): Boolean {
        val c = controllerPath.trim('/').split('/')
        val s = specPath.trim('/').split('/')
        if (c.size != s.size) return false
        for (i in c.indices) {
            val cs = c[i]
            val ss = s[i]
            val cWild = cs.startsWith("{") && cs.endsWith("}")
            val sWild = ss.startsWith("{") && ss.endsWith("}")
            if (cWild || sWild) continue
            if (cs != ss) return false
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Test
    // -------------------------------------------------------------------------

    @Test
    fun `spec has at least one endpoint`() {
        val specEndpoints = parseSpecEndpoints()
        assertTrue("OpenAPI spec should contain endpoints", specEndpoints.isNotEmpty())
    }

    @Test
    fun `all spec endpoints have matching controller methods`() {
        val specEndpoints = parseSpecEndpoints()
        val controllerEndpoints = discoverControllerEndpoints()

        // Pre-index controller endpoints by HTTP method for fast lookup.
        val controllerByMethod = controllerEndpoints.groupBy { it.method }

        val missingEndpoints = mutableListOf<SpecEndpoint>()

        for (spec in specEndpoints) {
            val normalizedSpecPath = normalizeSpecPath(spec.path)
            // Prepend /chronicle service prefix if not already present
            val searchPath = if (normalizedSpecPath.startsWith("/chronicle"))
                normalizedSpecPath
            else
                "/chronicle$normalizedSpecPath"
            val altPath = normalizedSpecPath.removePrefix("/chronicle")

            val candidates = controllerByMethod[spec.method].orEmpty()
            val matched = candidates.any {
                pathsMatch(it.path, searchPath) || pathsMatch(it.path, altPath)
            }
            if (!matched) missingEndpoints.add(spec)
        }

        if (missingEndpoints.isNotEmpty()) {
            val report = buildString {
                appendLine("The following spec endpoints have no matching controller method:")
                for (ep in missingEndpoints.sortedBy { it.path }) {
                    appendLine("  - ${ep.method} ${ep.path}")
                }
                appendLine()
                appendLine("Controller endpoints found (${controllerEndpoints.size} total):")
                for (ep in controllerEndpoints.sortedBy { it.path }.take(20)) {
                    appendLine("  + ${ep.method} ${ep.path} -> ${ep.controllerClass}.${ep.methodName}")
                }
                if (controllerEndpoints.size > 20) {
                    appendLine("  ... and ${controllerEndpoints.size - 20} more")
                }
            }
            fail(report)
        }
    }

    @Test
    fun `controller endpoint count is within reasonable range of spec`() {
        val specEndpoints = parseSpecEndpoints()
        val controllerEndpoints = discoverControllerEndpoints()

        // Controllers may have extra endpoints not in the spec (legacy, internal, etc.)
        // but there should not be dramatically fewer controller endpoints than spec endpoints
        assertTrue(
            "Controller has ${controllerEndpoints.size} endpoints but spec has ${specEndpoints.size}. " +
                "Fewer controller endpoints than spec endpoints suggests missing implementations.",
            controllerEndpoints.size >= specEndpoints.size * 0.5
        )
    }
}
