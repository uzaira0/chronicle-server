package com.openlattice.chronicle.util

import com.openlattice.chronicle.fuzz.FuzzTestConstants
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based tests for RedirectValidator to prevent open redirect vulnerabilities.
 */
class RedirectValidatorPropertyTest {

    private val allowedDomain = "trusted.example.com"
    private val validator = RedirectValidator(
        allowedDomains = setOf(allowedDomain),
        allowRelativePaths = true,
        strictHostMatching = true
    )

    private fun mockRequest(
        serverName: String = "app.example.com",
        serverPort: Int = 443,
        scheme: String = "https"
    ) = FuzzTestConstants.mockHttpServletRequest(serverName, serverPort, scheme)

    @Test
    fun `null or blank URLs are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.element(null, "", " ", "  ", "\t")) { url ->
            !validator.isValidRedirect(request, url)
        }
    } }

    @Test
    fun `relative paths starting with single slash are accepted`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..50, Codepoint.az())) { path ->
            validator.isValidRedirect(request, "/$path")
        }
    } }

    @Test
    fun `protocol-relative URLs are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(3..30, Codepoint.az())) { domain ->
            !validator.isValidRedirect(request, "//$domain.com/path")
        }
    } }

    @Test
    fun `javascript scheme URLs are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..50, Codepoint.az())) { payload ->
            !validator.isValidRedirect(request, "javascript:$payload")
        }
    } }

    @Test
    fun `data scheme URLs are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..50, Codepoint.alphanumeric())) { payload ->
            !validator.isValidRedirect(request, "data:text/html,$payload")
        }
    } }

    @Test
    fun `URLs with newline injection are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.element("\r", "\n", "\r\n", "%0d", "%0a")) { inject ->
            !validator.isValidRedirect(request, "https://trusted.example.com/${inject}evil")
        }
    } }

    @Test
    fun `URLs with at-sign (credential injection) are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(3..20, Codepoint.az())) { user ->
            !validator.isValidRedirect(request, "https://$user@evil.com/path")
        }
    } }

    @Test
    fun `same-origin URLs are always accepted`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..30, Codepoint.az())) { path ->
            validator.isValidRedirect(request, "https://app.example.com/$path")
        }
    } }

    @Test
    fun `allowed domain URLs are accepted`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..30, Codepoint.az())) { path ->
            validator.isValidRedirect(request, "https://$allowedDomain/$path")
        }
    } }

    @Test
    fun `random external domains are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(3..20, Codepoint.az())) { domain ->
            val url = "https://$domain.evil.com/phishing"
            // Only passes if it happens to match our allowed domain
            val result = validator.isValidRedirect(request, url)
            !result || "$domain.evil.com" == allowedDomain || "$domain.evil.com" == "app.example.com"
        }
    } }

    @Test
    fun `double-encoded percent is always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..20, Codepoint.az())) { path ->
            !validator.isValidRedirect(request, "/$path%252F%252F")
        }
    } }

    @Test
    fun `file scheme URLs are always rejected`() { runBlocking {
        val request = mockRequest()
        forAll(Arb.string(1..30, Codepoint.az())) { path ->
            !validator.isValidRedirect(request, "file:///$path")
        }
    } }

    @Test
    fun `getSafeRedirectUrl returns fallback for invalid URLs`() {
        val request = mockRequest()
        val result = validator.getSafeRedirectUrl(request, "javascript:alert(1)", "/safe")
        assertTrue(result == "/safe")
    }

    @Test
    fun `getSafeRedirectUrl returns valid URL when valid`() {
        val request = mockRequest()
        val result = validator.getSafeRedirectUrl(request, "/dashboard", "/safe")
        assertTrue(result == "/dashboard")
    }

    @Test
    fun `validator with relative paths disabled rejects all relative paths`() { runBlocking {
        val noRelative = RedirectValidator(allowRelativePaths = false)
        val request = mockRequest()
        forAll(Arb.string(1..30, Codepoint.az())) { path ->
            !noRelative.isValidRedirect(request, "/$path")
        }
    } }
}
