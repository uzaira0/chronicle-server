package com.openlattice.chronicle.storage.tde

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W4 — Traefik↔backend mTLS serversTransport CONFIG assertion (HIPAA §164.312(e)(1) — transmission
 * security; mutual authentication + encryption of the last internal hop).
 *
 * Full mTLS connectivity is a two-sided, cert-dependent cutover (backend `require-client-auth` +
 * mounted certs), so it is intentionally staged DEFAULT-OFF and enabled operationally — it cannot be
 * exercised end-to-end in a unit runner. What CAN and MUST be guarded in CI is that the staged
 * `serversTransport` is **safe by construction**: a future edit that weakens it (flips
 * `insecureSkipVerify` on, drops the CA, or removes the client cert — turning "mutual + verified TLS"
 * into "blind TLS") fails the build here.
 *
 * This is the W4 mTLS "container-structure-test assertion" the design called for, expressed as a
 * structural config test (the repo's testing idiom). It parses the actual dynamic-config file Traefik
 * loads and asserts the transport's security invariants.
 *
 * When chronicle-server is built standalone (no monorepo checkout, so `docker/` is absent) the test
 * SKIPS its assertions — the config-as-shipped is what the monorepo gate enforces.
 */
class TraefikBackendMtlsConfigTest {

    private val mapper = YAMLMapper()

    private fun locateTransportFile(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = dir?.resolve("docker/traefik/dynamic/servers-transport.yml")
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    private fun transport(): JsonNode? {
        val file = locateTransportFile() ?: return null
        val root = mapper.readTree(file)
        return root.path("http").path("serversTransports").path("chronicle-backend-mtls")
    }

    @Test
    fun `mTLS transport is defined for the backend bridge`() {
        val t = transport() ?: return // standalone checkout → skip
        assertFalse(
            "chronicle-backend-mtls serversTransport must be defined in servers-transport.yml",
            t.isMissingNode || t.isNull,
        )
    }

    @Test
    fun `mTLS transport verifies the backend server certificate — insecureSkipVerify must be off`() {
        val t = transport() ?: return
        val skip = t.path("insecureSkipVerify")
        // Default for the field is false; if present it MUST be false. true would defeat the upgrade.
        if (!skip.isMissingNode) {
            assertFalse(
                "insecureSkipVerify must be false — verifying the backend cert is the point of mTLS",
                skip.asBoolean(true),
            )
        }
    }

    @Test
    fun `mTLS transport pins a rootCA to verify the backend server cert`() {
        val t = transport() ?: return
        val rootCAs = t.path("rootCAs")
        assertTrue("rootCAs must be a non-empty list", rootCAs.isArray && rootCAs.size() > 0)
        assertTrue(
            "rootCAs must reference a CA file under /etc/traefik/certs",
            (0 until rootCAs.size()).any { rootCAs.get(it).asText().contains("ca.crt") },
        )
    }

    @Test
    fun `mTLS transport presents a client certificate — the mutual half`() {
        val t = transport() ?: return
        val certs = t.path("certificates")
        assertTrue("certificates must be a non-empty list", certs.isArray && certs.size() > 0)
        val first = certs.get(0)
        assertNotNull("client certFile must be set", first.path("certFile").asText(null))
        assertNotNull("client keyFile must be set", first.path("keyFile").asText(null))
        assertTrue(first.path("certFile").asText().endsWith("client.crt"))
        assertTrue(first.path("keyFile").asText().endsWith("client.key"))
    }

    @Test
    fun `mTLS transport sets the expected backend serverName`() {
        val t = transport() ?: return
        assertEquals(
            "serverName must match the backend service Traefik dials on the bridge",
            "chronicle-backend",
            t.path("serverName").asText(),
        )
    }
}
