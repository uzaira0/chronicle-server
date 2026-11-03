package com.openlattice.chronicle.configuration

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.controllers.ChronicleServerExceptionHandler
import com.openlattice.chronicle.controllers.StudyController
import com.openlattice.chronicle.controllers.StudyV4Controller
import com.openlattice.chronicle.generated.UploadLimits
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

class UploadBodyLimitTest {
    @Test fun mvcConversionReturns413ForStreamingMobileUploadOverflow() {
        AnnotationConfigApplicationContext(SecurityHardeningConfig::class.java, ParameterPollutionConfig::class.java).use { context ->
            context.beanFactory.registerSingleton("studyController", mock(StudyController::class.java))
            val streaming = Filter { request, response, chain ->
                chain.doFilter(object : HttpServletRequestWrapper(request as HttpServletRequest) {
                    override fun getContentLengthLong(): Long = -1
                    override fun getContentLength(): Int = -1
                }, response)
            }
            val hardening = context.getBean(SecurityHardeningConfig::class.java)
            AnnotationConfigWebApplicationContext().use { child ->
                child.parent = context
                child.servletContext = MockServletContext()
                child.register(UploadLimitMvcFixture::class.java)
                child.refresh()
                val mvc = MockMvcBuilders.webAppContextSetup(child)
                    .addFilters<DefaultMockMvcBuilder>(streaming, hardening.requestSizeLimitFilter()).build()
                assertMvcUploadBoundary(mvc)
            }
        }
    }

    private fun assertMvcUploadBoundary(mvc: MockMvc) {
        for (protocol in listOf("HTTP/1.1", "HTTP/2")) for (extra in 0..1) {
            val bytes = ByteArray(UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt() + extra) { ' '.code.toByte() }
            bytes[bytes.size - 2] = '['.code.toByte()
            bytes[bytes.size - 1] = ']'.code.toByte()
            val response = mvc.perform(post("/chronicle/v4/study/11111111-1111-1111-1111-111111111111/participant/test/android/battery")
                .contextPath("/chronicle").header(MobileApiSignatureFilter.RANDOM_DEVICE_ID_HEADER, "test")
                .contentType(MediaType.APPLICATION_JSON).content(bytes)
                .with { it.protocol = protocol; it }).andReturn().response
            assertEquals("protocol=$protocol extra=$extra",
                if (extra == 0) HttpStatus.OK.value() else HttpStatus.CONTENT_TOO_LARGE.value(), response.status)
        }
    }

    @Test fun committedResponsePropagatesOverflowWithoutSendingAnotherError() {
        val request = object : MockHttpServletRequest("POST", "/upload") {
            override fun getContentLengthLong(): Long = -1
        }
        request.setContent(ByteArray(UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt() + 1))
        val response = MockHttpServletResponse()
        assertThrows(RequestBodyTooLargeException::class.java) {
            SecurityHardeningConfig().requestSizeLimitFilter().doFilter(request, response) { req, res ->
                res.flushBuffer()
                req.inputStream.readBytes()
            }
        }
        assertEquals(HttpStatus.OK.value(), response.status)
    }

    @Test fun wireBoundaryIncludesExactLimitForDeclaredAndStreamingBodies() {
        for (contentType in listOf(null, "application/json"))
            for (streaming in listOf(false, true)) for (extra in 0..1) {
                assertWireBodyBoundary(contentType, streaming, extra)
            }
    }

    private fun assertWireBodyBoundary(contentType: String?, streaming: Boolean, extra: Int) {
        val body = ByteArray(UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt() + extra)
        val request = object : MockHttpServletRequest("POST", "/upload") {
            override fun getContentLengthLong(): Long = if (streaming) -1 else body.size.toLong()
        }
        request.contentType = contentType
        request.setContent(body)
        val response = MockHttpServletResponse()
        SecurityHardeningConfig().requestSizeLimitFilter().doFilter(request, response) { req, res ->
            req.inputStream.readBytes()
            (res as MockHttpServletResponse).status = 204
        }
        assertEquals("contentType=$contentType streaming=$streaming extra=$extra",
            if (extra == 0) HttpStatus.NO_CONTENT.value() else HttpStatus.CONTENT_TOO_LARGE.value(), response.status)
    }

    @Test fun decodedBoundaryIncludesExactLimit() {
        val filter = uploadSizeFilters().last()
        for (extra in 0..1) {
            val bytes = ByteArray(UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt() + extra)
            val compressed = ByteArrayOutputStream().also { output ->
                DeflaterOutputStream(output).use { it.write(bytes) }
            }.toByteArray()
            val request = MockHttpServletRequest("POST", "/chronicle/v4/study/test/participant/test/android")
            request.addHeader("Content-Encoding", "deflate")
            request.setContent(compressed)
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { req, res ->
                assertEquals(bytes.size, req.inputStream.readBytes().size)
                (res as MockHttpServletResponse).status = 204
            }
            assertEquals(if (extra == 0) HttpStatus.NO_CONTENT.value() else HttpStatus.CONTENT_TOO_LARGE.value(), response.status)
        }
    }

}

internal fun uploadSizeFilters(): List<Filter> {
    val hz = mock(HazelcastInstance::class.java)
    @Suppress("UNCHECKED_CAST")
    val cache = mock(IMap::class.java) as IMap<String, Long>
    `when`(hz.getMap<String, Long>(MobileApiSignatureFilter.NONCE_CACHE_MAP_NAME)).thenReturn(cache)
    return listOf(SecurityHardeningConfig().requestSizeLimitFilter(),
        MobileApiSignatureFilter(hz, "", signatureVerificationEnabled = false))
}

/** Parent hardening advice and child controller advice mirror ChronicleServerMvcPod discovery. */
@Configuration(proxyBeanMethods = false)
@EnableWebMvc
internal class UploadLimitMvcFixture {
    @Bean fun controller() = StudyV4Controller()
    @Bean fun exceptionHandler() = ChronicleServerExceptionHandler(mock(AuditingManager::class.java), ErrorSanitizationConfig())
}
