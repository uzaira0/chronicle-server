package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.configuration.MobileApiSignatureFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter

class ChronicleServerSecurityPodFilterOrderTest {
    @Test
    fun `rate limiter is anchored before mobile reviewer authentication when that filter exists`() {
        val mobileFilter = Mockito.mock(MobileApiSignatureFilter::class.java)

        assertEquals(mobileFilter.javaClass, rateLimitFilterAnchor(mobileFilter))
    }

    @Test
    fun `rate limiter remains before bearer authentication when mobile filtering is unavailable`() {
        assertEquals(BearerTokenAuthenticationFilter::class.java, rateLimitFilterAnchor(null))
    }
}
