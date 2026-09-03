package com.openlattice.chronicle.util

import com.openlattice.chronicle.fuzz.FuzzTestConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, example-based tests for [RedirectValidator] that pin the exact
 * boundary, branch, and return values so PIT mutants are killed. Complements the
 * property-based suite (which exercises broad input ranges but leaves several
 * branches with weak / no coverage).
 */
class RedirectValidatorMutationTest {

    private val allowedDomain = "trusted.example.com"

    private val strict = RedirectValidator(
        allowedDomains = setOf(allowedDomain),
        allowRelativePaths = true,
        strictHostMatching = true
    )

    private fun request(
        serverName: String = "app.example.com",
        serverPort: Int = 443,
        scheme: String = "https"
    ) = FuzzTestConstants.mockHttpServletRequest(serverName, serverPort, scheme)

    @Test
    fun `null is rejected`() = assertFalse(strict.isValidRedirect(request(), null))

    @Test
    fun `blank is rejected`() {
        assertFalse(strict.isValidRedirect(request(), ""))
        assertFalse(strict.isValidRedirect(request(), "   "))
    }

    @Test
    fun `double-encoded percent literal is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "/path%25"))
        assertFalse(strict.isValidRedirect(request(), "/path%252Fevil"))
    }

    @Test
    fun `single relative path is accepted`() {
        assertTrue(strict.isValidRedirect(request(), "/dashboard"))
    }

    @Test
    fun `relative path disabled is rejected, even when otherwise valid`() {
        val noRelative = RedirectValidator(allowRelativePaths = false)
        assertFalse(noRelative.isValidRedirect(request(), "/dashboard"))
    }

    @Test
    fun `protocol-relative path is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "//evil.com/path"))
    }

    @Test
    fun `bare double-slash boundary - exactly two chars is rejected`() {
        // hits the redirectUrl.length > 1 && redirectUrl[1] == '/' branch boundary
        assertFalse(strict.isValidRedirect(request(), "//"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        assertFalse(strict.isValidRedirect(request(), "javascript:alert(1)"))
        assertFalse(strict.isValidRedirect(request(), "data:text/html,x"))
        assertFalse(strict.isValidRedirect(request(), "vbscript:msgbox(1)"))
        assertFalse(strict.isValidRedirect(request(), "file:///etc/passwd"))
    }

    @Test
    fun `credential injection at-sign is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "https://user@evil.com/path"))
    }

    @Test
    fun `crlf injection is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "https://trusted.example.com/\rinject"))
        assertFalse(strict.isValidRedirect(request(), "https://trusted.example.com/\ninject"))
    }

    @Test
    fun `malformed URL is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "http://[invalid"))
    }

    @Test
    fun `ftp scheme (valid URI, unsafe scheme) is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "ftp://trusted.example.com/x"))
    }

    @Test
    fun `absolute http URL with no host is rejected`() {
        // "http:relativepath" parses with a null host
        assertFalse(strict.isValidRedirect(request(), "http:nohostpath"))
    }

    @Test
    fun `same-origin URL is accepted`() {
        assertTrue(strict.isValidRedirect(request(), "https://app.example.com/page"))
    }

    @Test
    fun `same-origin with explicit standard port is accepted`() {
        // request on 443/https vs redirect with no explicit port -> both normalize to 443
        assertTrue(strict.isValidRedirect(request(serverPort = 443, scheme = "https"), "https://app.example.com/x"))
    }

    @Test
    fun `same-origin rejected when redirect port differs`() {
        assertFalse(strict.isValidRedirect(request(serverPort = 443, scheme = "https"), "https://app.example.com:8443/x"))
    }

    @Test
    fun `same-origin with custom matching port is accepted`() {
        assertTrue(
            strict.isValidRedirect(
                request(serverName = "app.example.com", serverPort = 9000, scheme = "http"),
                "http://app.example.com:9000/x",
            ),
        )
    }

    @Test
    fun `same-origin rejected when host differs`() {
        // host differs from request host AND is not an allowed domain
        assertFalse(strict.isValidRedirect(request(), "https://other.example.com/x"))
    }

    @Test
    fun `allowed domain URL is accepted`() {
        assertTrue(strict.isValidRedirect(request(), "https://trusted.example.com/login"))
    }

    @Test
    fun `strict matching rejects subdomain of allowed domain`() {
        assertFalse(strict.isValidRedirect(request(), "https://sub.trusted.example.com/login"))
    }

    @Test
    fun `non-strict matching accepts subdomain of allowed domain`() {
        val lenient = RedirectValidator(
            allowedDomains = setOf(allowedDomain),
            allowRelativePaths = true,
            strictHostMatching = false
        )
        assertTrue(lenient.isValidRedirect(request(), "https://sub.trusted.example.com/login"))
    }

    @Test
    fun `non-strict matching accepts exact allowed domain`() {
        val lenient = RedirectValidator(
            allowedDomains = setOf(allowedDomain),
            strictHostMatching = false
        )
        assertTrue(lenient.isValidRedirect(request(), "https://trusted.example.com/login"))
    }

    @Test
    fun `non-strict matching rejects domain that merely ends with allowed text without dot`() {
        // "nottrusted.example.com" endsWith "trusted.example.com" is true textually,
        // but the validator requires a leading dot (".trusted.example.com"), so reject.
        val lenient = RedirectValidator(
            allowedDomains = setOf(allowedDomain),
            strictHostMatching = false
        )
        assertFalse(lenient.isValidRedirect(request(), "https://nottrusted.example.com/login"))
    }

    @Test
    fun `unrelated external domain is rejected`() {
        assertFalse(strict.isValidRedirect(request(), "https://evil.com/phishing"))
    }

    @Test
    fun `getSafeRedirectUrl returns valid URL unchanged`() {
        assertEquals("/dashboard", strict.getSafeRedirectUrl(request(), "/dashboard", "/fallback"))
    }

    @Test
    fun `getSafeRedirectUrl returns fallback for invalid URL`() {
        assertEquals("/fallback", strict.getSafeRedirectUrl(request(), "javascript:alert(1)", "/fallback"))
    }

    @Test
    fun `getSafeRedirectUrl default fallback is root`() {
        assertEquals("/", strict.getSafeRedirectUrl(request(), "https://evil.com"))
    }

    @Test
    fun `multi-layer encoded protocol-relative URL is decoded and rejected`() {
        // %2F%2F decodes once to //, caught by the decoded-url dangerous-pattern scan
        assertFalse(strict.isValidRedirect(request(), "https://app.example.com/x%2F%2Fevil.com"))
    }

    @Test
    fun `Builder constructs an equivalent validator`() {
        val built = RedirectValidator.Builder()
            .allowDomain(allowedDomain)
            .allowRelativePaths(true)
            .strictHostMatching(true)
            .build()
        assertTrue(built.isValidRedirect(request(), "https://trusted.example.com/x"))
        assertFalse(built.isValidRedirect(request(), "https://evil.com/x"))
    }

    @Test
    fun `Builder allowDomains vararg and collection both register domains`() {
        val viaVararg = RedirectValidator.Builder().allowDomains("a.example.com", "b.example.com").build()
        assertTrue(viaVararg.isValidRedirect(request(), "https://a.example.com/x"))
        assertTrue(viaVararg.isValidRedirect(request(), "https://b.example.com/x"))

        val viaCollection = RedirectValidator.Builder().allowDomains(listOf("c.example.com")).build()
        assertTrue(viaCollection.isValidRedirect(request(), "https://c.example.com/x"))
        assertFalse(viaCollection.isValidRedirect(request(), "https://a.example.com/x"))
    }
}
