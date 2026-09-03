package com.openlattice.chronicle.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicDistributionDefaultsTest {
    @Test
    fun `bundled operator defaults are generic and fail closed`() {
        val resources = listOf(
            "/twilio.yaml",
            "/mail.yaml",
            "/cors.yaml",
            "/cors-local.yaml",
            "/mobile-security.yaml",
            "/webapp/index.html",
        ).associateWith { path ->
            requireNotNull(javaClass.getResourceAsStream(path)) { "$path is missing" }
                .bufferedReader()
                .use { it.readText() }
        }

        resources.forEach { (path, content) ->
            assertFalse("$path contains an institution-specific hostname", content.contains("bcm.edu", true))
            assertFalse("$path contains a legacy product hostname", content.contains("chronicle-screentime", true))
        }
        assertTrue(resources.getValue("/twilio.yaml").contains("enabled: false"))
        assertTrue(resources.getValue("/twilio.yaml").contains("callbackBaseUrl: \"\""))
        assertTrue(resources.getValue("/mail.yaml").contains("defaultFromEmail: \"\""))
        assertTrue(resources.getValue("/cors.yaml").contains("allowed-origins: []"))
        val mobileSecurity = resources.getValue("/mobile-security.yaml")
        assertTrue(mobileSecurity.contains("enabled: false"))
        assertTrue(mobileSecurity.contains("signing-secret: \"\""))
        assertTrue(mobileSecurity.contains("signing-required: false"))
        val authResourceUrls = javaClass.classLoader.getResources("chronicle-auth.yaml").toList()
        val packagedAuthResourceUrls = authResourceUrls.filterNot { resource ->
            resource.toExternalForm().replace('\\', '/').contains("/resources/test/")
        }
        assertEquals(
            "expected exactly one packaged main chronicle-auth.yaml; classpath resources=$authResourceUrls",
            1,
            packagedAuthResourceUrls.size,
        )
        val packagedAuth = packagedAuthResourceUrls.single().openStream().bufferedReader().use { it.readText() }
        assertTrue(
            "packaged auth must have no usable configuration; resource=${packagedAuthResourceUrls.single()}",
            packagedAuth.contains("configurations: []"),
        )
        assertFalse(packagedAuth.contains("secret:"))
        assertFalse(packagedAuth.contains("testingTokenIssuer:"))
        assertFalse(packagedAuth.contains("bcm.edu", true))
        assertFalse(packagedAuth.contains("chronicle-screentime", true))
        assertFalse(resources.getValue("/webapp/index.html").contains("http-equiv=\"refresh\""))
    }

    @Test
    fun `Twilio requires a canonical HTTPS root only when explicitly enabled`() {
        assertEquals(TwilioConfiguration(), TwilioConfiguration().validated())

        val configured = TwilioConfiguration(
            enabled = true,
            sid = "AC-test",
            token = "token-test",
            defaultFromPhone = "+15551234567",
            callbackBaseUrl = "https://study.example.org/",
        )
        assertEquals("https://study.example.org", configured.validated().callbackBaseUrl)

        listOf(
            configured.copy(callbackBaseUrl = ""),
            configured.copy(callbackBaseUrl = "http://study.example.org"),
            configured.copy(callbackBaseUrl = "https://study.example.org/path"),
            configured.copy(callbackBaseUrl = "https://user@study.example.org"),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
        }
    }
}
