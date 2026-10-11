package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.configuration.MobileApiSignatureFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

class ChronicleServerSecurityPodFilterOrderTest {
    @Test
    fun `rate limiter runs after device authentication when mobile filtering exists`() {
        val mobileFilter = Mockito.mock(MobileApiSignatureFilter::class.java)

        assertEquals(AnonymousAuthenticationFilter::class.java, rateLimitFilterAnchor(mobileFilter))
    }

    @Test
    fun `rate limiter runs after bearer authentication without mobile filtering`() {
        assertEquals(AnonymousAuthenticationFilter::class.java, rateLimitFilterAnchor(null))
    }
}
