package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
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
        ).associateWith(::packagedResource)

        resources.forEach { (path, content) ->
            assertFalse("$path contains an institution-specific hostname", content.contains("bcm.edu", true))
            assertFalse("$path contains a legacy product hostname", content.contains("chronicle-screentime", true))
        }
        assertTrue(resources.getValue("/twilio.yaml").contains("enabled: false"))
        assertTrue(resources.getValue("/twilio.yaml").contains("callbackBaseUrl: \"\""))
        assertEquals("{}", resources.getValue("/mail.yaml").trim())
        assertTrue(resources.getValue("/cors.yaml").contains("allowed-origins: []"))
        val mobileSecurity = resources.getValue("/mobile-security.yaml")
        assertTrue(mobileSecurity.contains("enabled: false"))
        assertTrue(mobileSecurity.contains("signing-secret: \"\""))
        assertTrue(mobileSecurity.contains("signing-required: false"))
        val packagedAuth = packagedResource("/chronicle-auth.yaml")
        assertTrue(
            "packaged auth must have no usable configuration",
            packagedAuth.contains("configurations: []"),
        )
        assertFalse(packagedAuth.contains("secret:"))
        assertFalse(packagedAuth.contains("testingTokenIssuer:"))
        assertFalse(packagedAuth.contains("bcm.edu", true))
        assertFalse(packagedAuth.contains("chronicle-screentime", true))
        assertFalse(resources.getValue("/webapp/index.html").contains("http-equiv=\"refresh\""))
        // Deployments render their own TLS stores and database login; the jar ships neither.
        val jetty = packagedResource("/jetty.yaml")
        listOf("keystore:", "truststore:", "keymanager-password:").forEach { key ->
            assertFalse("packaged jetty.yaml must not carry $key", jetty.contains(key))
        }
        // rhizome's own tests jar keeps its test keystore on this classpath, so check the server's resources.
        assertFalse(java.io.File("src/main/resources/security/rhizome.jks").exists())
        val rhizome = packagedResource("/rhizome.yaml")
        assertFalse("packaged rhizome.yaml must not carry a usable database login",
            Regex("""(username|password): "[^"]+"""").containsMatchIn(rhizome))
    }

    /** Without a rendered rhizome.yaml every pool has a blank login, so startup cannot reach a database. */
    @Test
    fun `packaged database pools carry no login in any spelling`() {
        val rhizome = YAMLMapper().readTree(packagedResource("/rhizome.yaml"))
        val pools = listOf("postgres" to rhizome["postgres"]["hikari"]) +
            rhizome["datasources"].fields().asSequence().toList().map { (name, source) -> "datasources.$name" to source["hikari"] }
        pools.forEach { (name, hikari) ->
            listOf("username", "password", "dataSource.user", "dataSource.password").forEach { key ->
                assertEquals("$name $key", "", hikari[key]?.asText().orEmpty())
            }
        }
    }

    /** Deployments render their own stores; nothing under the server's resources is a key or its password. */
    @Test
    fun `packaged resources carry no key material or key passwords`() {
        val keyExtensions = setOf("jks", "p12", "pfx", "keystore", "key", "pem")
        val offenders = java.io.File("src/main/resources").walkTopDown().filter { it.isFile }.filter { file ->
            file.extension.lowercase() in keyExtensions || file.readBytes().toString(Charsets.ISO_8859_1).let {
                it.contains("PRIVATE KEY-----") || Regex("""(?i)(keystore|truststore|keymanager)-password\s*:""").containsMatchIn(it)
            }
        }.map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun packagedResource(path: String): String {
        val resources = javaClass.classLoader.getResources(path.removePrefix("/")).toList().filterNot { resource ->
            resource.toExternalForm().replace('\\', '/').contains("/resources/test/")
        }
        return resources.first().openStream().bufferedReader().use { it.readText() }
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
