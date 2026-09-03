package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.ChronicleRoleClaims
import com.openlattice.chronicle.configuration.roleClientIdForClaims
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.services.auth.RefreshTokenException
import com.openlattice.chronicle.services.auth.RefreshTokenService
import com.openlattice.chronicle.util.ClientIpResolver
import com.openlattice.chronicle.util.ChronicleServerUtil
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.users.UserListingService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.spec.SecretKeySpec

/**
 * Provides endpoints for managing JWT authentication via httpOnly cookies.
 *
 * F-P0-2: Migrates JWT storage from frontend localStorage (XSS-accessible) to
 * httpOnly Secure SameSite=Strict cookies (inaccessible to JavaScript).
 *
 * Transitional flow:
 * 1. Frontend checks the current cookie-backed session via `/session`.
 * 2. Testing environments may call `/testing-login` to mint a server-managed session.
 * 3. Transitional browser flows can still POST a JWT to `/set-cookie`.
 * 4. Subsequent requests use the httpOnly cookie automatically.
 */
@RestController
// DUAL PATH REQUIRED: Rhizome maps the DispatcherServlet to /chronicle/*. Spring 6's
// PathPatternParser strips the servlet prefix for matching, so /v3/auth is the effective
// path. The /chronicle/v3/auth variant is needed for direct container-to-container calls
// that bypass the servlet mapping. See memory/spring-servlet-path-resolution.md.
@RequestMapping(value = ["/chronicle/v3/auth", "/v3/auth"])
@Timed
// reason: auth controller — cohesive set of endpoints + cookie/OIDC/CSRF helpers; splitting the
// class would fragment the request/response handling and helper logic that belong together.
// LargeClass for the same reason: dashboard-login has to sit next to ensureCookieSession and
// buildAuthenticatedSession, because minting a session identical to the other login paths' is
// the whole point of it.
@Suppress("TooManyFunctions", "LargeClass")
public open class AuthTokenController(
    private val jwtDecoder: JwtDecoder,
    private val chronicleAuthConfiguration: ChronicleAuthConfiguration,
    private val objectMapper: ObjectMapper,
    private val userListingService: UserListingService,
    private val refreshTokenService: RefreshTokenService,
    private val environment: Environment,
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(AuthTokenController::class.java)
        public const val AUTH_COOKIE_NAME = "chronicle_auth"
        public const val CSRF_COOKIE_NAME = "ol_csrf_token"
        private const val OIDC_STATE_COOKIE_NAME = "chronicle_oidc_state"
        private const val OIDC_NONCE_COOKIE_NAME = "chronicle_oidc_nonce"
        private const val OIDC_PKCE_VERIFIER_COOKIE_NAME = "chronicle_oidc_pkce"
        private const val COOKIE_PATH = "/chronicle"
        private const val MAX_AGE_30_DAYS = 30 * 24 * 60 * 60
        private const val MAX_AGE_10_MINUTES = 10 * 60
        private const val TESTING_PROVIDER_LABEL = "Chronicle testing session"
        private const val SSO_PROVIDER_LABEL = "Institutional SSO"

        // One response for every way dashboard-login can fail — wrong password, no hash
        // configured, no local user to mint a session for, an unusable token. Distinct
        // messages (or distinct status codes) would turn this endpoint into an oracle that
        // tells an attacker when they have guessed the password but hit a config problem.
        private const val DASHBOARD_LOGIN_REJECTED = "invalid dashboard password"

        // Deliberately below the 10/min the AUTH tier gives every other endpoint here: this
        // is the only one where a correct guess yields an admin session, so brute force is
        // the threat that matters. Keyed on IP explicitly, because supplying an explicit
        // requestsPerMinute skips the type-derived key-strategy default.
        private const val DASHBOARD_LOGIN_PER_MINUTE = 5L
        private const val DASHBOARD_LOGIN_BURST = 5L
    }

    private val secureRandom = SecureRandom()

    // Stateless and thread-safe; bcrypt's own comparison is constant time over the digest.
    private val dashboardPasswordEncoder = BCryptPasswordEncoder()

    private fun useSecureCookie(request: HttpServletRequest?): Boolean {
        val explicitCookieSecure = environment.getProperty("chronicle.security.cookie.secure")?.toBooleanStrictOrNull()
        if (explicitCookieSecure != null) {
            return explicitCookieSecure
        }

        return request?.let { isSecureRequest(it) } ?: true
    }

    /**
     * Determines if the request arrived over a secure channel.
     * Defaults to Secure=true when behind a proxy (header absent or "https").
     * Only returns false when X-Forwarded-Proto explicitly says "http".
     */
    public fun isSecureRequest(request: HttpServletRequest): Boolean {
        return request.isSecure ||
            request.getHeader("X-Forwarded-Proto")?.equals("https", ignoreCase = true) ?: true
    }

    @RateLimit(type = RateLimitType.AUTH)
    @PostMapping(
        path = ["/set-cookie"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun setAuthCookie(
        @RequestBody body: Map<String, String>,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, Any>> {
        val token = body["token"]
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "missing 'token' field" as Any))

        val jwt = try {
            jwtDecoder.decode(token)
        } catch (e: JwtException) {
            logger.info("Rejecting set-cookie request with invalid JWT: {}", e.message)
            return ResponseEntity.status(401).body(mapOf("error" to "invalid token" as Any))
        }

        val csrfToken = ensureCookieSession(request, token, response)
        return ResponseEntity.ok(buildAuthenticatedSession(jwt, csrfToken))
    }

    @RateLimit(type = RateLimitType.AUTH)
    @GetMapping(
        path = ["/session"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun getSession(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, Any>> {
        val authentication = request.userPrincipal as? Authentication
        val jwtAuthentication = authentication as? JwtAuthenticationToken
        if (jwtAuthentication == null || !jwtAuthentication.isAuthenticated) {
            val cookieToken = readCookie(request, AUTH_COOKIE_NAME)
            if (!cookieToken.isNullOrBlank()) {
                val decodedCookieJwt = try {
                    jwtDecoder.decode(cookieToken)
                } catch (exception: JwtException) {
                    logger.info("Ignoring invalid Chronicle auth cookie on session bootstrap: {}", exception.message)
                    null
                }
                if (decodedCookieJwt != null) {
                    val csrfToken = ensureCsrfCookie(request, response)
                    return ResponseEntity.ok(buildAuthenticatedSession(decodedCookieJwt, csrfToken))
                }
            }

            return ResponseEntity.ok(buildUnauthenticatedSession())
        }

        val csrfToken = ensureCsrfCookie(request, response)
        return ResponseEntity.ok(buildAuthenticatedSession(jwtAuthentication.token, csrfToken))
    }

    @RateLimit(type = RateLimitType.AUTH)
    @GetMapping(path = ["/oidc/login"])
    public fun oidcLogin(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        val oidc = chronicleAuthConfiguration.oidc
        if (!isOidcReady()) {
            return ResponseEntity.status(404).build()
        }

        val state = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        val codeVerifier = generateCodeVerifier()
        addCookie(
            request = request,
            response = response,
            name = OIDC_STATE_COOKIE_NAME,
            value = state,
            httpOnly = true,
            maxAge = MAX_AGE_10_MINUTES,
            sameSite = "Lax"
        )
        addCookie(
            request = request,
            response = response,
            name = OIDC_NONCE_COOKIE_NAME,
            value = nonce,
            httpOnly = true,
            maxAge = MAX_AGE_10_MINUTES,
            sameSite = "Lax"
        )
        addCookie(
            request = request,
            response = response,
            name = OIDC_PKCE_VERIFIER_COOKIE_NAME,
            value = codeVerifier,
            httpOnly = true,
            maxAge = MAX_AGE_10_MINUTES,
            sameSite = "Lax"
        )

        val authorizationUrlBuilder = UriComponentsBuilder.fromUriString(oidc.authorizationUri)
            .queryParam("response_type", "code")
            .queryParam("client_id", oidc.clientId)
            .queryParam("redirect_uri", redirectUri())
            .queryParam("scope", oidc.scopes.joinToString(" "))
            .queryParam("state", state)
            .queryParam("nonce", nonce)
            .queryParam("code_challenge", codeChallenge(codeVerifier))
            .queryParam("code_challenge_method", "S256")

        if (oidc.identityProviderHint.isNotBlank()) {
            authorizationUrlBuilder.queryParam("kc_idp_hint", oidc.identityProviderHint)
        }

        val authorizationUrl = authorizationUrlBuilder
            .build()
            .encode()
            .toUri()

        return ResponseEntity.status(302).location(authorizationUrl).build()
    }

    // reason: guard-clause OIDC callback — each return maps to a distinct error/redirect HTTP
    // outcome (not_configured/login_failed/missing_state/invalid_state/...); collapsing the guards
    // would obscure the security-sensitive validation flow
    @Suppress("ReturnCount")
    @RateLimit(type = RateLimitType.AUTH)
    @GetMapping(path = ["/oidc/callback"])
    public fun oidcCallback(
        @RequestParam("code", required = false) code: String?,
        @RequestParam("state", required = false) state: String?,
        @RequestParam("error", required = false) error: String?,
        @RequestParam("error_description", required = false) errorDescription: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<*> {
        if (!isOidcReady()) {
            return ResponseEntity.status(404).body(mapOf("error" to "oidc_not_configured"))
        }
        if (!error.isNullOrBlank()) {
            logger.warn(
                "OIDC login failed: {} {}",
                LogSanitizer.sanitize(error, 200),
                LogSanitizer.sanitize(errorDescription ?: "", 500)
            )
            return ResponseEntity.status(401).body(mapOf("error" to "oidc_login_failed"))
        }
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "missing_oidc_code_or_state"))
        }
        val expectedState = readCookie(request, OIDC_STATE_COOKIE_NAME)
        if (expectedState.isNullOrBlank() || !constantTimeEquals(expectedState, state)) {
            return ResponseEntity.status(400).body(mapOf("error" to "invalid_oidc_state"))
        }
        val codeVerifier = readCookie(request, OIDC_PKCE_VERIFIER_COOKIE_NAME)
            ?: return ResponseEntity.status(400).body(mapOf("error" to "missing_oidc_pkce_verifier"))

        val tokenPayload = exchangeOidcCode(code, codeVerifier)
            ?: return ResponseEntity.status(502).body(mapOf("error" to "oidc_token_exchange_failed"))
        val sessionToken = selectSessionToken(tokenPayload)
            ?: return ResponseEntity.status(502).body(mapOf("error" to "missing_oidc_token"))

        val jwt = try {
            jwtDecoder.decode(sessionToken)
        } catch (exception: JwtException) {
            logger.warn("OIDC broker returned an invalid Chronicle session token: {}", exception.message)
            return ResponseEntity.status(401).body(mapOf("error" to "invalid_oidc_token"))
        }

        if (!validateOidcNonce(tokenPayload, request)) {
            return ResponseEntity.status(400).body(mapOf("error" to "invalid_oidc_nonce"))
        }

        ensureCookieSession(request, sessionToken, response)
        clearCookie(response, request, OIDC_STATE_COOKIE_NAME, sameSite = "Lax")
        clearCookie(response, request, OIDC_NONCE_COOKIE_NAME, sameSite = "Lax")
        clearCookie(response, request, OIDC_PKCE_VERIFIER_COOKIE_NAME, sameSite = "Lax")
        logger.info("OIDC login completed for subject {}", jwt.subject)
        return ResponseEntity.status(302).location(URI.create(chronicleAuthConfiguration.oidc.postLoginRedirectUri)).build<Void>()
    }

    /**
     * WARNING: This endpoint is for development/testing environments ONLY.
     * It is blocked when the "production" Spring profile is active unless
     * allowProductionTestingLogin=true in ChronicleAuthConfiguration (disabled by
     * default). It also requires testingLoginEnabled=true in ChronicleAuthConfiguration
     * (disabled by default). The endpoint bypasses SSO
     * authentication entirely and mints a session from pre-configured user tokens.
     */
    // reason: guard-clause endpoint — each return is a distinct HTTP outcome (disabled/forbidden/
    // error/ok); collapsing them would obscure the auth decision flow. Boundary catch wraps
    // decodeTestingToken which can throw JwtException/IllegalArgumentException/Base64 errors.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    @RateLimit(type = RateLimitType.AUTH)
    @PostMapping(
        path = ["/testing-login"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun testingLogin(
        @RequestBody(required = false) body: Map<String, String>?,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, Any>> {
        // Allowing testing-login in production is a deliberate, high-trust override.
        // Keep off by default; production profiles must set both flags explicitly.
        if (environment.activeProfiles.any { it.equals("production", ignoreCase = true) } &&
            !chronicleAuthConfiguration.allowProductionTestingLogin
        ) {
            return ResponseEntity.status(404).build()
        }

        if (!chronicleAuthConfiguration.testingLoginEnabled) {
            return ResponseEntity.status(403).body(
                mapOf(
                    "authenticated" to false,
                    "error" to "testing login is not enabled",
                    "providerLabel" to TESTING_PROVIDER_LABEL,
                    "status" to "awaiting-sso"
                )
            )
        }

        val token = userListingService.issueTestingToken(body?.get("userId"))
            ?: return ResponseEntity.status(403).body(
                mapOf(
                    "authenticated" to false,
                    "error" to "testing login is not enabled",
                    "providerLabel" to TESTING_PROVIDER_LABEL,
                    "status" to "awaiting-sso"
                )
            )

        val jwt = try {
            decodeTestingToken(token)
        } catch (exception: Exception) {
            logger.warn(
                "testing-login issued token could not be decoded with current JWT decoder: {}",
                exception.message
            )
            return ResponseEntity.status(500).body(
                mapOf(
                    "authenticated" to false,
                    "error" to "configured testing token is invalid",
                    "providerLabel" to TESTING_PROVIDER_LABEL,
                    "status" to "error"
                )
            )
        }

        val csrfToken = ensureCookieSession(request, token, response)
        val metadata = buildAuthenticatedSession(jwt, csrfToken).toMutableMap()
        metadata["authToken"] = token
        metadata["providerLabel"] = TESTING_PROVIDER_LABEL
        metadata["tokenSource"] = "testing-login"
        return ResponseEntity.ok(metadata)
    }

    private fun decodeTestingToken(token: String): org.springframework.security.oauth2.jwt.Jwt {
        return try {
            jwtDecoder.decode(token)
        } catch (primaryError: JwtException) {
            val testingTokenConfig = chronicleAuthConfiguration.configurations
                .firstOrNull { it.testingTokenIssuer }
                ?: throw primaryError

            logger.debug(
                "Fallback decoding of testing token because primary JwtDecoder failed: {}",
                primaryError::class.java.simpleName
            )

            createLaxJwtDecoder(testingTokenConfig).decode(token)
        }
    }

    private fun createLaxJwtDecoder(config: ChronicleJwtClientConfiguration): JwtDecoder {
        return when {
            !config.jwkSetUri.isNullOrBlank() -> NimbusJwtDecoder.withJwkSetUri(config.jwkSetUri).build()
            config.signingAlgorithm.startsWith("HS") -> {
                val secret = if (config.base64EncodedSecret) {
                    java.util.Base64.getDecoder().decode(config.secret)
                } else {
                    config.secret.toByteArray(StandardCharsets.UTF_8)
                }
                val algorithm = when (config.signingAlgorithm) {
                    "HS384" -> "HmacSHA384"
                    "HS512" -> "HmacSHA512"
                    else -> "HmacSHA256"
                }
                NimbusJwtDecoder.withSecretKey(SecretKeySpec(secret, algorithm)).build()
            }
            else -> throw IllegalArgumentException("Unsupported testing fallback algorithm: ${config.signingAlgorithm}")
        }
    }

    /**
     * Verifies the configured researcher-dashboard password and, only on a match, mints the
     * same cookie-backed session `/testing-login` hands out with no credential check at all.
     *
     * This is the credential check for the self-host dashboard. It replaces the browser's
     * native HTTP Basic prompt in front of the proxy, moving the check from Caddy into the
     * backend so one password gates both. The hash it verifies against is the very same
     * bcrypt hash Caddy's `basic_auth` uses (`DASHBOARD_PASSWORD_HASH`) — one password, one
     * hash, two consumers.
     *
     * Fails closed. With no hash configured every attempt is rejected, so a deployment that
     * forgot to set one is locked out rather than wide open.
     */
    // reason: guard-clause endpoint — every failure path returns the identical body on
    // purpose (see DASHBOARD_LOGIN_REJECTED), and collapsing them would hide which
    // conditions are checked. Boundary catch wraps decodeTestingToken, which can throw
    // JwtException/IllegalArgumentException/Base64 errors.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    @RateLimit(
        type = RateLimitType.AUTH,
        requestsPerMinute = DASHBOARD_LOGIN_PER_MINUTE,
        burstCapacity = DASHBOARD_LOGIN_BURST,
        keyStrategy = RateLimitKeyStrategy.IP,
    )
    @PostMapping(
        path = ["/dashboard-login"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun dashboardLogin(
        @RequestBody(required = false) body: Map<String, String>?,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, Any>> {
        val password = body?.get("password")
        if (password.isNullOrEmpty()) {
            // Not a credential oracle: supplying no password at all reveals nothing about
            // the configured one, so this may say what is missing.
            return ResponseEntity.badRequest().body(
                mapOf("authenticated" to false, "error" to "missing 'password' field")
            )
        }

        val configuredHash = chronicleAuthConfiguration.dashboardPasswordHash?.trim()
        if (configuredHash.isNullOrBlank()) {
            logger.warn(
                "Rejecting dashboard-login from {}: no dashboardPasswordHash is configured",
                dashboardClientReference(request)
            )
            return dashboardLoginRejected()
        }

        // BCryptPasswordEncoder.matches() returns false (it does not throw) when the stored
        // value is not a bcrypt hash, so a cleartext password left in the config rejects
        // every attempt instead of being compared against.
        if (!dashboardPasswordEncoder.matches(password, configuredHash)) {
            logger.warn("Rejecting dashboard-login from {}: password mismatch", dashboardClientReference(request))
            return dashboardLoginRejected()
        }

        val token = userListingService.issueDashboardToken(null)
        if (token == null) {
            logger.error("dashboard-login accepted the password but no local user is configured to mint a session for")
            return dashboardLoginRejected()
        }

        val jwt = try {
            decodeTestingToken(token)
        } catch (exception: Exception) {
            logger.error(
                "dashboard-login minted a session token the JWT decoder rejects: {}",
                exception.message
            )
            return dashboardLoginRejected()
        }

        val csrfToken = ensureCookieSession(request, token, response)
        val metadata = buildAuthenticatedSession(jwt, csrfToken).toMutableMap()
        metadata["tokenSource"] = "dashboard-login"
        logger.info("dashboard-login succeeded for {}", dashboardClientReference(request))
        return ResponseEntity.ok(metadata)
    }

    private fun dashboardClientReference(request: HttpServletRequest): String = LogSanitizer.stableFingerprint(
        ClientIpResolver.resolve(request),
        prefix = "ip",
    )

    /**
     * The single response every dashboard-login failure returns. Identical status and body
     * whatever went wrong, so the endpoint never confirms a correct guess.
     */
    private fun dashboardLoginRejected(): ResponseEntity<Map<String, Any>> = ResponseEntity.status(401).body(
        mapOf("authenticated" to false, "error" to DASHBOARD_LOGIN_REJECTED)
    )

    @RateLimit(type = RateLimitType.AUTH)
    @PostMapping(path = ["/logout"])
    public fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Void> {
        clearCookie(
            request = request,
            response = response,
            name = AUTH_COOKIE_NAME,
            httpOnly = true,
            sameSite = "Strict",
        )
        clearCookie(
            request = request,
            response = response,
            name = CSRF_COOKIE_NAME,
            httpOnly = false,
            sameSite = "Strict",
        )

        return ResponseEntity.noContent().build()
    }

    /**
     * Rotates a refresh token and returns a new access + refresh token pair.
     *
     * Token theft detection: if a rotated-out (already-used) refresh token is
     * presented, the entire token family is revoked and a security alert is logged.
     */
    @RateLimit(type = RateLimitType.AUTH)
    @PostMapping(
        path = ["/refresh"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun refreshToken(
        @RequestBody body: Map<String, String>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, Any>> {
        val rawRefreshToken = body["refreshToken"]
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "missing 'refreshToken' field" as Any))

        return try {
            val ipAddress = ClientIpResolver.resolve(request)
            val userAgent = request.getHeader("User-Agent")

            val result = refreshTokenService.rotateRefreshToken(rawRefreshToken, ipAddress, userAgent)

            // Set the new access token as an httpOnly cookie
            val csrfToken = ensureCookieSession(request, result.accessToken, response)

            ResponseEntity.ok(
                mapOf(
                    "accessToken" to result.accessToken,
                    "refreshToken" to result.refreshToken,
                    "expiresIn" to result.expiresIn,
                    "csrfToken" to csrfToken,
                    "tokenType" to "Bearer",
                ) as Map<String, Any>
            )
        } catch (ex: RefreshTokenException) {
            logger.warn("Refresh token rejected: {}", ex.message)
            ResponseEntity.status(401).body(
                mapOf("error" to (ex.message ?: "invalid refresh token") as Any)
            )
        }
    }

    private fun isOidcReady(): Boolean {
        val oidc = chronicleAuthConfiguration.oidc
        return oidc.enabled &&
            oidc.publicBaseUrl.isNotBlank() &&
            oidc.authorizationUri.isNotBlank() &&
            oidc.tokenUri.isNotBlank() &&
            oidc.clientId.isNotBlank() &&
            oidc.clientSecret.isNotBlank()
    }

    private fun redirectUri(): String {
        val oidc = chronicleAuthConfiguration.oidc
        return oidc.publicBaseUrl.trimEnd('/') + oidc.redirectPath
    }

    private fun loginUrl(): String? {
        return if (isOidcReady()) {
            "/chronicle/v3/auth/oidc/login"
        } else {
            null
        }
    }

    // reason: boundary catch — the token exchange does HTTP (RestTemplate) + JSON parsing which can
    // fail with many exception types; all are treated as an exchange failure and must return null
    @Suppress("TooGenericExceptionCaught")
    private fun exchangeOidcCode(code: String, codeVerifier: String): Map<String, Any?>? {
        val oidc = chronicleAuthConfiguration.oidc
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

        val form = LinkedMultiValueMap<String, String>()
        form.add("grant_type", "authorization_code")
        form.add("code", code)
        form.add("redirect_uri", redirectUri())
        form.add("client_id", oidc.clientId)
        form.add("client_secret", oidc.clientSecret)
        form.add("code_verifier", codeVerifier)

        return try {
            val response = RestTemplate().postForEntity(
                oidc.tokenUri,
                HttpEntity(form, headers),
                String::class.java
            )
            if (!response.statusCode.is2xxSuccessful || response.body.isNullOrBlank()) {
                logger.warn("OIDC token exchange failed with status {}", response.statusCode)
                null
            } else {
                objectMapper.readValue<Map<String, Any?>>(response.body!!)
            }
        } catch (exception: Exception) {
            logger.warn("OIDC token exchange failed: {}", exception.message)
            null
        }
    }

    private fun selectSessionToken(tokenPayload: Map<String, Any?>): String? {
        val preferredClaim = chronicleAuthConfiguration.oidc.cookieTokenClaim.takeIf { it.isNotBlank() }
            ?: "access_token"
        return tokenPayload[preferredClaim] as? String
            ?: tokenPayload["access_token"] as? String
            ?: tokenPayload["id_token"] as? String
    }

    private fun validateOidcNonce(tokenPayload: Map<String, Any?>, request: HttpServletRequest): Boolean {
        val idToken = tokenPayload["id_token"] as? String ?: return true
        val expectedNonce = readCookie(request, OIDC_NONCE_COOKIE_NAME) ?: return false
        val actualNonce = try {
            jwtDecoder.decode(idToken).claims["nonce"] as? String
        } catch (exception: JwtException) {
            logger.warn("OIDC id_token nonce validation failed: {}", exception.message)
            return false
        }
        return actualNonce != null && constantTimeEquals(expectedNonce, actualNonce)
    }

    private fun buildUnauthenticatedSession(): Map<String, Any> {
        val hasTestingToken = userListingService.issueTestingToken() != null
        val providerLabel = if (chronicleAuthConfiguration.oidc.enabled) {
            chronicleAuthConfiguration.oidc.providerLabel.takeIf { it.isNotBlank() } ?: SSO_PROVIDER_LABEL
        } else if (hasTestingToken) {
            TESTING_PROVIDER_LABEL
        } else {
            SSO_PROVIDER_LABEL
        }
        val session = mutableMapOf<String, Any>(
            "authenticated" to false,
            "providerLabel" to providerLabel,
            "status" to "awaiting-sso",
            "testingLoginEnabled" to hasTestingToken,
            "tokenSource" to "sso-session"
        )
        loginUrl()?.let { session["loginUrl"] = it }
        return session
    }

    private fun ensureCookieSession(
        request: HttpServletRequest,
        token: String,
        response: HttpServletResponse
    ): String {
        addCookie(
            response = response,
            request = request,
            name = AUTH_COOKIE_NAME,
            value = token,
            httpOnly = true,
            maxAge = MAX_AGE_30_DAYS,
            sameSite = "Strict"
        )

        val csrfToken = UUID.randomUUID().toString()
        // httpOnly=false: JS must read CSRF token for header inclusion
        addCookie(
            response = response,
            request = request,
            name = CSRF_COOKIE_NAME,
            value = csrfToken,
            httpOnly = false,
            maxAge = MAX_AGE_30_DAYS,
            sameSite = "Strict"
        )

        return csrfToken
    }

    private fun ensureCsrfCookie(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): String {
        val existingToken = readCookie(request, CSRF_COOKIE_NAME)

        if (!existingToken.isNullOrBlank()) {
            return existingToken
        }

        return ensureCookieSession(request, ChronicleServerUtil.getTokenFromContext(), response)
    }

    private fun readCookie(request: HttpServletRequest, name: String): String? {
        return request.cookies
            ?.firstOrNull { it.name == name }
            ?.value
    }

    private fun addCookie(
        response: HttpServletResponse,
        request: HttpServletRequest,
        name: String,
        value: String,
        httpOnly: Boolean,
        maxAge: Int,
        sameSite: String,
    ) {
        val cookie = Cookie(name, value)
        cookie.isHttpOnly = httpOnly
        cookie.secure = useSecureCookie(request)
        cookie.path = COOKIE_PATH
        cookie.maxAge = maxAge
        cookie.setAttribute("SameSite", sameSite)
        response.addCookie(cookie)
    }

    private fun clearCookie(
        response: HttpServletResponse,
        request: HttpServletRequest,
        name: String,
        httpOnly: Boolean = true,
        sameSite: String = "Strict",
    ) {
        addCookie(
            response = response,
            request = request,
            name = name,
            value = "",
            httpOnly = httpOnly,
            maxAge = 0,
            sameSite = sameSite
        )
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val actualBytes = actual.toByteArray(Charsets.UTF_8)
        if (expectedBytes.size != actualBytes.size) {
            return false
        }
        var result = 0
        expectedBytes.indices.forEach { index ->
            result = result or (expectedBytes[index].toInt() xor actualBytes[index].toInt())
        }
        return result == 0
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun buildAuthenticatedSession(
        jwt: org.springframework.security.oauth2.jwt.Jwt,
        csrfToken: String,
    ): Map<String, Any> {
        val tokenIssuer = jwt.issuer
        val issuerMismatch = tokenIssuer != null &&
            tokenIssuer.toString() != chronicleAuthConfiguration.oidc.issuer
        val providerLabel = when {
            !chronicleAuthConfiguration.oidc.enabled || issuerMismatch -> TESTING_PROVIDER_LABEL
            chronicleAuthConfiguration.oidc.providerLabel.isNotBlank() ->
                chronicleAuthConfiguration.oidc.providerLabel
            else -> SSO_PROVIDER_LABEL
        }
        val metadata = mutableMapOf<String, Any>(
            "authenticated" to true,
            "authMode" to "cookie-bootstrap",
            "csrfToken" to csrfToken,
            "providerLabel" to providerLabel,
            "status" to "authenticated",
            "tokenSource" to "sso-session"
        )

        jwt.expiresAt?.let { metadata["expiresAt"] = it.toEpochMilli() }
        jwt.subject?.let { metadata["subject"] = it }
        val roles = extractRoles(jwt.claims)

        val fullName = jwt.claims["name"]
            ?: listOf(jwt.claims["given_name"], jwt.claims["family_name"])
                .filterIsInstance<String>()
                .joinToString(" ")
                .ifBlank { null }

        metadata["user"] = mapOf(
            "email" to jwt.claims["email"],
            "id" to jwt.subject,
            "name" to fullName,
            "roles" to roles
        )
        return metadata
    }

    private fun extractRoles(claims: Map<String, Any>): List<String> {
        return ChronicleRoleClaims.extract(
            claims,
            chronicleAuthConfiguration.roleClaimNamespace,
            chronicleAuthConfiguration.roleClientIdForClaims(claims),
        )
    }
}
