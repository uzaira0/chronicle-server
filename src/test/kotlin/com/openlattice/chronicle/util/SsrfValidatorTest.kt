package com.openlattice.chronicle.util

import com.openlattice.chronicle.configuration.SsrfConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class SsrfValidatorTest {
    private val config = SsrfConfig(
        allowedHosts = emptySet(),
        allowedProtocols = setOf("https"),
        blockPrivateIps = true,
        blockLocalhost = true,
        blockLinkLocal = true,
        blockMetadataEndpoints = true,
        enabled = true,
    )

    @Test
    fun testRejectsIpv4SpecialPurposeAndNonUnicastRangesAtBoundaries() {
        val blocked = listOf(
            "0.0.0.0", "0.255.255.255",
            "10.0.0.0", "10.255.255.255",
            "100.64.0.0", "100.127.255.255",
            "127.0.0.0", "127.255.255.255",
            "169.254.0.0", "169.254.255.255",
            "172.16.0.0", "172.31.255.255",
            "192.0.0.0", "192.0.0.255",
            "192.0.2.0", "192.0.2.255",
            "192.31.196.0", "192.31.196.255",
            "192.52.193.0", "192.52.193.255",
            "192.88.99.0", "192.88.99.255",
            "192.168.0.0", "192.168.255.255",
            "192.175.48.0", "192.175.48.255",
            "198.18.0.0", "198.19.255.255",
            "198.51.100.0", "198.51.100.255",
            "203.0.113.0", "203.0.113.255",
            "224.0.0.0", "239.255.255.255",
            "240.0.0.0", "255.255.255.255",
        )

        blocked.forEach { address ->
            assertTrue("$address must be rejected", SsrfValidator.isPrivateIp(ip(address)))
            assertThrows("$address must fail validation", SsrfException::class.java) {
                SsrfValidator.validateIpAddress(ip(address), address, config)
            }
        }
    }

    @Test
    fun testIpv4CidrNeighborsRemainPublic() {
        listOf(
            "9.255.255.255",
            "11.0.0.0",
            "100.63.255.255",
            "100.128.0.0",
            "172.15.255.255",
            "172.32.0.0",
            "191.255.255.255",
            "192.0.1.255",
            "192.0.3.0",
            "198.17.255.255",
            "198.20.0.0",
            "203.0.112.255",
            "203.0.114.0",
            "223.255.255.255",
        ).forEach { address ->
            assertFalse("$address must remain public", SsrfValidator.isPrivateIp(ip(address)))
        }
    }

    @Test
    fun testRejectsIpv6SpecialPurposeAndNonGlobalRanges() {
        listOf(
            "::",
            "::1",
            "::ffff:192.168.1.1",
            "64:ff9b:1::",
            "100::",
            "100:0:0:1::",
            "2001::",
            "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:db8::",
            "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
            "2002::",
            "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "2620:4f:8000::",
            "2620:4f:8000:ffff:ffff:ffff:ffff:ffff",
            "2d00::",
            "3000::",
            "3ffe::",
            "3fff::",
            "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff",
            "5f00::",
            "fc00::",
            "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fe80::",
            "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "ff00::",
        ).forEach { address ->
            assertTrue("$address must be rejected", SsrfValidator.isPrivateIp(ip(address)))
        }
    }

    @Test
    fun testOrdinaryGlobalUnicastIpv6AddressesRemainPublic() {
        listOf(
            "2001:200::",
            "2001:4860:4860::8888",
            "2606:4700:4700::1111",
            "2a00:1450:4009::200e",
        ).forEach { address ->
            assertFalse("$address must remain public", SsrfValidator.isPrivateIp(ip(address)))
        }
    }

    @Test
    fun testPinnedDnsResolvesCanonicalHostnameExactlyOnce() {
        val resolutions = AtomicInteger()
        val publicAddress = ip("93.184.216.34")
        val url = "https://MiXeD.Example.COM/callback".toHttpUrl()

        val dns = SsrfValidator.createPinnedDns(url, config) { hostname ->
            resolutions.incrementAndGet()
            assertEquals("mixed.example.com", hostname)
            arrayOf(publicAddress)
        }

        assertEquals(listOf(publicAddress), dns.lookup("mixed.example.com"))
        assertEquals(listOf(publicAddress), dns.lookup("mixed.example.com"))
        assertEquals(1, resolutions.get())
        assertThrows(UnknownHostException::class.java) {
            dns.lookup("different.example.com")
        }
        assertEquals(1, resolutions.get())
    }

    @Test
    fun testRejectsEntireResolutionSetWhenAnyAnswerIsNonGlobal() {
        val resolutions = AtomicInteger()

        assertThrows(SsrfException::class.java) {
            SsrfValidator.validateAndResolve("mixed.example.com", config) {
                resolutions.incrementAndGet()
                arrayOf(ip("93.184.216.34"), ip("127.0.0.1"))
            }
        }

        assertEquals(1, resolutions.get())
    }

    @Test
    fun testDynamicDestinationStillRejectsMetadataHostname() {
        assertThrows(SsrfException::class.java) {
            SsrfValidator.validateHostSafety("metadata.google.internal", config)
        }
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
