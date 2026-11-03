package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {

    @Test
    fun testDeploymentTrustReplacesFixedRangesForEverySubnetFamily() {
        for (subnet in listOf("10.253.1.0/24", "192.168.77.0/24", "172.28.0.0/16")) {
            assertEquals(listOf(subnet), ClientIpResolver.deploymentTrustedProxyCidrs(listOf("127.0.0.1/32"), subnet))
            val request = MockHttpServletRequest()
            request.remoteAddr = subnet.substringBefore("/").substringBeforeLast(".") + ".5"
            request.addHeader("X-Forwarded-For", "198.51.100.40")
            assertEquals("198.51.100.40", ClientIpResolver.resolve(request,
                trustedProxyCidrs = ClientIpResolver.deploymentTrustedProxyCidrs(emptyList(), subnet)))
        }
    }

    @Test
    fun testIgnoresForwardedHeadersFromUntrustedDirectPeer() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session")
        request.remoteAddr = "203.0.113.10"
        request.addHeader("X-Forwarded-For", "198.51.100.99")
        request.addHeader("X-Real-IP", "198.51.100.100")

        val actual = ClientIpResolver.resolve(
            request = request,
            trustedProxyCidrs = listOf("172.16.0.0/12")
        )

        assertEquals("203.0.113.10", actual)
    }

    @Test
    fun testUsesRightmostNonTrustedForwardedEntryFromTrustedProxy() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session")
        request.remoteAddr = "172.18.0.5"
        request.addHeader("X-Forwarded-For", "198.51.100.40, 172.18.0.9")

        val actual = ClientIpResolver.resolve(
            request = request,
            trustedProxyCidrs = listOf("172.16.0.0/12")
        )

        assertEquals("198.51.100.40", actual)
    }

    @Test
    fun testDoesNotTreatAllPrivateClientsAsTrustedProxies() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session")
        request.remoteAddr = "172.18.0.5"
        request.addHeader("X-Forwarded-For", "192.0.2.54, 198.51.100.10")

        val actual = ClientIpResolver.resolve(
            request = request,
            trustedProxyCidrs = listOf("172.16.0.0/12")
        )

        assertEquals("198.51.100.10", actual)
    }

    @Test
    fun testFallsBackToRemoteAddressWhenProxyChainIsInvalid() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session")
        request.remoteAddr = "172.18.0.5"
        request.addHeader("X-Forwarded-For", "spoofed.example.internal")

        val actual = ClientIpResolver.resolve(
            request = request,
            trustedProxyCidrs = listOf("172.16.0.0/12")
        )

        assertEquals("172.18.0.5", actual)
    }

    /** An operator who turns proxy trust off gets the socket peer even when a trusted proxy forwards headers. */
    @Test
    fun proxyHeadersAreIgnoredWhenTheOperatorDisablesProxyTrust() {
        val request = MockHttpServletRequest("POST", "/chronicle/v3/auth/dashboard-login")
        request.remoteAddr = "172.18.0.5"
        request.addHeader("X-Forwarded-For", "198.51.100.40")
        request.addHeader("X-Real-IP", "198.51.100.41")

        assertEquals("172.18.0.5", ClientIpResolver.resolve(request, trustProxyHeaders = false,
            trustedProxyCidrs = listOf("172.16.0.0/12")))
    }

    /**
     * A reverse proxy that sends only X-Real-IP still names the client; a fallback that is itself a proxy
     * address or not an address literal is not believed, and the socket peer is used instead.
     */
    @Test
    fun realIpFallbackNamesTheClientOnlyWhenItIsAnUntrustedAddressLiteral() {
        fun resolveWithRealIp(realIp: String?, forwardedFor: String? = null): String {
            val request = MockHttpServletRequest("POST", "/chronicle/v3/auth/dashboard-login")
            request.remoteAddr = "172.18.0.5"
            forwardedFor?.let { request.addHeader("X-Forwarded-For", it) }
            realIp?.let { request.addHeader("X-Real-IP", it) }
            return ClientIpResolver.resolve(request, trustedProxyCidrs = listOf("172.16.0.0/12"))
        }

        assertEquals("198.51.100.41", resolveWithRealIp(" 198.51.100.41 "))
        assertEquals("198.51.100.41", resolveWithRealIp("198.51.100.41", forwardedFor = "172.18.0.9"))
        assertEquals("2001:db8::7", resolveWithRealIp("2001:db8::7", forwardedFor = "1::2::3"))
        assertEquals("172.18.0.5", resolveWithRealIp("172.18.0.9"))
        assertEquals("172.18.0.5", resolveWithRealIp("1::2::3"))
        assertEquals("172.18.0.5", resolveWithRealIp("proxy.internal"))
        assertEquals("172.18.0.5", resolveWithRealIp(null))
    }

    /** A typo in CHRONICLE_TRUSTED_PROXY_CIDRS drops only that entry; the valid proxy range is still trusted. */
    @Test
    fun malformedTrustedProxyCidrIsIgnoredAndTheValidRangeStillApplies() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session")
        request.remoteAddr = "172.18.0.5"
        request.addHeader("X-Forwarded-For", "198.51.100.40")

        assertEquals("198.51.100.40", ClientIpResolver.resolve(request,
            trustedProxyCidrs = listOf("172.16.0.0/33", "172.16.0.0/12")))
        assertEquals("172.18.0.5", ClientIpResolver.resolve(request, trustedProxyCidrs = listOf("172.16.0.0/33")))
    }
    /**
     * With CHRONICLE_TRUSTED_PROXY_CIDRS unset there is no deployment proxy, so only a
     * loopback peer may name the client. A container on any Docker bridge (172.16.0.0/12) is just a client;
     * its X-Forwarded-For is not believed by audit and log IP references.
     */
    @Test
    fun withoutDeploymentProxyConfigurationOnlyALoopbackPeerMayNameTheClient() {
        assertNull("the unit-test JVM must not carry deployment proxy trust", System.getenv("CHRONICLE_TRUSTED_PROXY_CIDRS"))
        fun resolveFrom(peer: String): String {
            val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session")
            request.remoteAddr = peer
            request.addHeader("X-Forwarded-For", "198.51.100.40")
            return ClientIpResolver.resolve(request)
        }

        assertEquals("172.18.0.5", resolveFrom("172.18.0.5"))
        assertEquals("172.31.255.1", resolveFrom("172.31.255.1"))
        assertEquals("198.51.100.40", resolveFrom("127.0.0.1"))
        assertEquals("198.51.100.40", resolveFrom("::1"))
    }
}
