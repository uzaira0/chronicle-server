package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
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
}
