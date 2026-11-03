package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.deser.SettableBeanProperty
import com.fasterxml.jackson.databind.deser.ValueInstantiator
import com.fasterxml.jackson.databind.deser.impl.PropertyValueBuffer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter
import com.openlattice.chronicle.configuration.RateLimitFilter
import com.openlattice.chronicle.configuration.SecurityHardeningConfig
import com.openlattice.chronicle.configuration.RequestBodyTooLargeException
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.pods.servlet.ChronicleServerMvcPod
import com.openlattice.chronicle.services.upload.MAX_UPLOAD_ROWS
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.validation.constraints.Size
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.Profile
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockServletContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.time.OffsetDateTime

class JsonArrayRowLimitMvcTest {
    companion object {
        private lateinit var parent: GenericApplicationContext
        private lateinit var child: AnnotationConfigWebApplicationContext
        private lateinit var mvc: MockMvc
        private lateinit var mapper: ObjectMapper
        private val studyId = UUID.randomUUID()
        private val constructions = AtomicInteger()
        private val usageConstructions = AtomicInteger()
        private const val ROWS = "/b2-fixture/rows"
        private val mobileRows = "/chronicle/v4/study/$studyId/participant/probe/android/b2-row-probe"

        @BeforeClass @JvmStatic fun startMvc() {
            mapper = JacksonSecurityConfig().secureObjectMapper()
            observeUsageConstructors(mapper)
            parent = GenericApplicationContext().apply {
                beanFactory.registerSingleton("mapper", mapper)
                beanFactory.registerSingleton("storageResolver", Mockito.mock(StorageResolver::class.java))
                beanFactory.registerSingleton("auditingManager", Mockito.mock(AuditingManager::class.java))
                beanFactory.registerSingleton("errorConfig", ErrorSanitizationConfig(logFullErrors = false))
                beanFactory.registerSingleton("rateLimitFilter", Mockito.mock(RateLimitFilter::class.java))
                refresh()
            }
            child = AnnotationConfigWebApplicationContext().apply {
                setParent(JsonArrayRowLimitMvcTest.parent)
                servletContext = MockServletContext()
                environment.setActiveProfiles("json-row-limit-test")
                register(ChronicleServerMvcPod::class.java, ProbeController::class.java)
                addBeanFactoryPostProcessor { factory ->
                    val registry = factory as BeanDefinitionRegistry
                    registry.beanDefinitionNames.filter { name ->
                        val definition = registry.getBeanDefinition(name)
                        val type = definition.beanClassName.orEmpty()
                        val advice = (definition as? AnnotatedBeanDefinition)?.metadata
                            ?.hasAnnotation(RestControllerAdvice::class.java.name) == true
                        type.startsWith("com.openlattice.chronicle.controllers.") &&
                            type != ProbeController::class.java.name && !advice
                    }.forEach(registry::removeBeanDefinition)
                }
                refresh()
            }
            val mobile = MobileApiSignatureFilter(
                Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS),
                signingSecret = "", signatureVerificationEnabled = false,
            )
            mvc = MockMvcBuilders.webAppContextSetup(child).apply {
                addFilters<DefaultMockMvcBuilder>(
                    SecurityHardeningConfig().requestSizeLimitFilter(), mobile,
                )
            }.build()
        }

        @AfterClass @JvmStatic fun stopMvc() {
            if (::child.isInitialized) child.close()
            if (::parent.isInitialized) parent.close()
        }

        private fun observeUsageConstructors(mapper: ObjectMapper) {
            val module = SimpleModule("B2 constructor observation")
            module.setDeserializerModifier(object : BeanDeserializerModifier() {
                override fun updateBuilder(config: DeserializationConfig, description: BeanDescription,
                                           builder: BeanDeserializerBuilder): BeanDeserializerBuilder {
                    if (description.beanClass == ChronicleUsageEvent::class.java) {
                        builder.valueInstantiator = UsageConstructorCounter(builder.valueInstantiator)
                    }
                    return builder
                }
            })
            mapper.registerModule(module)
        }
    }

    private class UsageConstructorCounter(delegate: ValueInstantiator) : ValueInstantiator.Delegating(delegate) {
        override fun createFromObjectWith(context: DeserializationContext, properties: Array<SettableBeanProperty>,
                                          buffer: PropertyValueBuffer): Any =
            super.createFromObjectWith(context, properties, buffer).also { usageConstructions.incrementAndGet() }
    }

    data class CountingRow(val value: Int = 0, val label: String = "", val nested: Map<String, List<Int>> = emptyMap()) {
        init { constructions.incrementAndGet() }
    }

    @RestController
    @Profile("json-row-limit-test")
    class ProbeController {
        @PostMapping(ROWS, "/v4/study/{studyId}/participant/{participantId}/android/b2-row-probe")
        fun rows(@RequestBody @Size(max = MAX_UPLOAD_ROWS) rows: List<CountingRow>): Int = rows.size

        @PostMapping("/b2-fixture/unbounded")
        fun unbounded(@RequestBody rows: List<CountingRow>): Int = rows.size

        @PostMapping("/b2-fixture/small")
        fun small(@RequestBody @Size(max = 2) rows: List<CountingRow>): Int = rows.size

        @PostMapping("/b2-fixture/set")
        fun set(@RequestBody @Size(max = 2) rows: Set<CountingRow>): Int = rows.size

        @PostMapping("/b2-fixture/optional")
        fun optional(@RequestBody(required = false) @Size(max = 2) rows: List<CountingRow>?): Int = rows?.size ?: -1

        @PostMapping("/b2-fixture/chronicle")
        fun chronicle(@RequestBody rows: ChronicleData): Int = rows.size
    }

    @Before fun resetCounters() { constructions.set(0); usageConstructions.set(0) }
    @After fun clearAuthentication() { SecurityContextHolder.clearContext() }

    private fun rows(count: Int, row: String = "{}"): ByteArray =
        List(count) { row }.joinToString(prefix = "[", postfix = "]").toByteArray()

    private fun device() {
        SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(
            "device-fixture", UUID.randomUUID(), studyId, "probe", UUID.randomUUID(), ApiKeyScope.WRITE, emptyList(),
        )
    }

    @Test fun overflowIsRejectedBeforeConstructorsWhileAnUnboundedControlBuildsEveryRow() {
        val body = rows(MAX_UPLOAD_ROWS + 1)
        val control = mvc.perform(post("/b2-fixture/unbounded").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(200, control.status)
        assertEquals(MAX_UPLOAD_ROWS + 1, constructions.get())
        constructions.set(0)
        device()
        val refusal = mvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(400, refusal.status)
        assertEquals("UPLOAD_ROW_REJECTED", mapper.readTree(refusal.contentAsByteArray)["code"].asText())
        assertEquals(0, constructions.get())
        val details = mapper.readTree(refusal.contentAsByteArray).path("details").toString()
        assertTrue(details, details.contains("rows") && details.contains(MAX_UPLOAD_ROWS.toString()))
    }

    @Test fun theExistingFiftyThousandRowBoundaryRemainsAllowed() {
        device()
        val response = mvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON).content(rows(MAX_UPLOAD_ROWS))).andReturn().response
        assertEquals(200, response.status)
        assertEquals(MAX_UPLOAD_ROWS.toString(), response.contentAsString)
        assertEquals(MAX_UPLOAD_ROWS, constructions.get())
    }

    @Test fun delegatingChronicleDataIsBoundedBeforeItsRealUsageConstructors() {
        val row = mapper.writeValueAsString(ChronicleUsageEvent(studyId, "probe", "example.app", "Move to Foreground",
            timestamp = OffsetDateTime.parse("2026-11-01T00:00:00Z"), timezone = "UTC", user = "", applicationLabel = "app"))
        val oversized = rows(MAX_UPLOAD_ROWS + 1, row)
        val control = mapper.readValue(oversized, ChronicleData::class.java)
        assertEquals(MAX_UPLOAD_ROWS + 1, control.size)
        assertEquals(MAX_UPLOAD_ROWS + 1, usageConstructions.get())
        usageConstructions.set(0)
        device()
        val refusal = mvc.perform(post("/b2-fixture/chronicle").contentType(MediaType.APPLICATION_JSON).content(oversized))
            .andReturn().response
        assertEquals(400, refusal.status)
        assertEquals("UPLOAD_ROW_REJECTED", mapper.readTree(refusal.contentAsByteArray)["code"].asText())
        assertEquals(0, usageConstructions.get())
        val accepted = mvc.perform(post("/b2-fixture/chronicle").contentType(MediaType.APPLICATION_JSON)
            .content(rows(MAX_UPLOAD_ROWS, row))).andReturn().response
        assertEquals(200, accepted.status)
        assertEquals(MAX_UPLOAD_ROWS.toString(), accepted.contentAsString)
        assertEquals(MAX_UPLOAD_ROWS, usageConstructions.get())
    }

    @Test fun eachMethodAnnotationOwnsItsLimitAndNestedValuesAreNotCountedAsRows() {
        val row = """{"value":7,"nested":{"tags":[1,2,3,4]}}"""
        val accepted = mvc.perform(post("/b2-fixture/small").contentType(MediaType.APPLICATION_JSON).content(rows(2, row)))
            .andReturn().response
        assertEquals(200, accepted.status)
        assertEquals(2, constructions.get())
        constructions.set(0)
        val refused = mvc.perform(post("/b2-fixture/small").contentType(MediaType.APPLICATION_JSON).content(rows(3, row)))
            .andReturn().response
        assertEquals(400, refused.status)
        assertNull(mapper.readTree(refused.contentAsByteArray)["code"])
        assertEquals(0, constructions.get())
        val details = mapper.readTree(refused.contentAsByteArray).path("details").toString()
        assertTrue(details, details.contains("rows") && details.contains("2"))
    }

    @Test fun duplicateSetElementsStillConsumeRawRows() {
        val response = mvc.perform(post("/b2-fixture/set").contentType(MediaType.APPLICATION_JSON).content(rows(3))).andReturn().response
        assertEquals(400, response.status)
        assertEquals(0, constructions.get())
    }

    @Test fun malformedAndTrailingJsonFailBeforeAnyRowIsConstructed() {
        for (body in listOf("[{}", "[{}] true", "[{},]")) {
            constructions.set(0)
            val response = mvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
            assertEquals(body, 400, response.status)
            assertNull(mapper.readTree(response.contentAsByteArray)["code"])
            assertEquals(body, 0, constructions.get())
        }
    }

    @Test fun optionalEmptyAndNullBodiesAndUnknownFieldsKeepTheirExistingSemantics() {
        for (body in listOf("", "null")) {
            val response = mvc.perform(post("/b2-fixture/optional").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().response
            assertEquals(200, response.status)
            assertEquals("-1", response.contentAsString)
        }
        val unknown = mvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON).content("[{\"unknown\":1}]"))
            .andReturn().response
        assertEquals(400, unknown.status)
        assertNull(mapper.readTree(unknown.contentAsByteArray)["code"])
    }

    @Test fun unknownLengthBodiesCannotBypassTheRawRowLimit() {
        device()
        val body = rows(MAX_UPLOAD_ROWS + 1)
        val request = RequestBuilder { servletContext ->
            object : MockHttpServletRequest(servletContext, "POST", ROWS) {
                override fun getContentLength(): Int = -1
                override fun getContentLengthLong(): Long = -1
            }.apply { contentType = MediaType.APPLICATION_JSON_VALUE; setContent(body) }
        }
        val response = mvc.perform(request).andReturn().response
        assertEquals(400, response.status)
        assertEquals("UPLOAD_ROW_REJECTED", mapper.readTree(response.contentAsByteArray)["code"].asText())
        assertEquals(0, constructions.get())
    }

    @Test fun theRealDeflateFilterStillFeedsTheSameRawRowGuard() {
        device()
        fun encoded(count: Int): ByteArray = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(rows(count)) }
        }.toByteArray()
        val accepted = mvc.perform(post(mobileRows).contextPath("/chronicle").contentType(MediaType.APPLICATION_JSON)
            .header("Content-Encoding", "deflate")
            .content(encoded(3))).andReturn().response
        assertEquals(200, accepted.status)
        assertEquals(3, constructions.get())
        constructions.set(0)
        val refused = mvc.perform(post(mobileRows).contextPath("/chronicle").contentType(MediaType.APPLICATION_JSON)
            .header("Content-Encoding", "deflate")
            .content(encoded(MAX_UPLOAD_ROWS + 1))).andReturn().response
        assertEquals(400, refused.status)
        assertEquals("UPLOAD_ROW_REJECTED", mapper.readTree(refused.contentAsByteArray)["code"].asText())
        assertEquals(0, constructions.get())
    }

    @Test fun replayPreservesBytesAndHeadersForMarkedAndFallbackStreamsWithoutClosingTheInput() {
        val adviceBeans = child.getBeansOfType(RequestBodyAdvice::class.java).values
        assertEquals("production advice must be discovered by the actual MVC component scan", 1, adviceBeans.size)
        val parameter = MethodParameter(ProbeController::class.java.getMethod("rows", List::class.java), 0)
        val converter = child.getBean(RequestMappingHandlerAdapter::class.java).messageConverters
            .first { it.canRead(List::class.java, MediaType.APPLICATION_JSON) }.javaClass
        val body = """ [ {"label":"café ⛄","nested":{"tags":[1,2]}} ] """.toByteArray()
        for (marked in listOf(true, false)) {
            var closed = false
            val source = object : FilterInputStream(ByteArrayInputStream(body)) {
                override fun markSupported(): Boolean = marked
                override fun close() { closed = true; super.close() }
            }
            val input = MockHttpInputMessage(source).apply { headers.contentType = MediaType.APPLICATION_JSON; headers.add("X-Probe", "same") }
            val replay = adviceBeans.single().beforeBodyRead(input, parameter, parameter.genericParameterType, converter)
            assertSame(input.headers, replay.headers)
            assertEquals("same", replay.headers.getFirst("X-Probe"))
            assertFalse(closed)
            assertArrayEquals(body, replay.body.readBytes())
            if (marked) assertSame(source, replay.body)
        }
    }

    @Test fun anUnknownLengthFallbackReadsAtMostTheExistingByteCapPlusOne() {
        val adviceBeans = child.getBeansOfType(RequestBodyAdvice::class.java).values
        assertEquals(1, adviceBeans.size)
        val parameter = MethodParameter(ProbeController::class.java.getMethod("rows", List::class.java), 0)
        val converter = child.getBean(RequestMappingHandlerAdapter::class.java).messageConverters
            .first { it.canRead(List::class.java, MediaType.APPLICATION_JSON) }.javaClass
        var consumed = 0L
        val endlessWhitespace = object : InputStream() {
            override fun read(): Int { consumed++; return ' '.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                Arrays.fill(bytes, offset, offset + length, ' '.code.toByte())
                consumed += length
                return length
            }
        }
        val input = MockHttpInputMessage(endlessWhitespace).apply { headers.contentType = MediaType.APPLICATION_JSON }
        assertThrows(RequestBodyTooLargeException::class.java) {
            adviceBeans.single().beforeBodyRead(input, parameter, parameter.genericParameterType, converter)
        }
        assertEquals(UploadLimits.MAX_REQUEST_SIZE_BYTES + 1, consumed)
        assertEquals(0, constructions.get())
    }
}
