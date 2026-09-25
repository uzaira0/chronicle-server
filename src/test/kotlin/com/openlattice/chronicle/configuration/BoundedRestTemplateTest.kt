package com.openlattice.chronicle.configuration

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.web.client.ResourceAccessException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration

/**
 * The OIDC token exchange and JWKS fetches must not hold a request thread forever when the
 * identity provider accepts the connection and never answers (launch audit L4).
 */
class BoundedRestTemplateTest {
    @Test
    fun `read from a silent server fails within the bound`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val template = boundedRestTemplate(Duration.ofMillis(300))
            val started = System.nanoTime()
            assertThrows(ResourceAccessException::class.java) {
                template.getForObject("http://127.0.0.1:${silent.localPort}/token", String::class.java)
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue("request took $elapsed", elapsed < Duration.ofSeconds(3))
        }
    }

    @Test
    fun `default bound is five seconds`() {
        assertTrue(DEFAULT_OUTBOUND_TIMEOUT == Duration.ofSeconds(5))
    }
}
