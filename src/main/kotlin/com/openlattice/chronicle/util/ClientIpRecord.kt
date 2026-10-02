package com.openlattice.chronicle.util

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * The only way a client IP address may be logged or stored.
 *
 * Participants' IP addresses are never recorded: the app, enrollment, form links and the reviewer
 * bootstrap all reach the server without a dashboard token, and every such request gets nothing.
 * Staff IPs are recorded only when the operator sets [RECORD_STAFF_IP_ENV]=true, and then as a keyed
 * fingerprint that cannot be reversed without the deployment secret. ClientIpPrivacyGuardTest fails
 * the build on any other path.
 */
public object ClientIpRecord {
    /** Written wherever an IP is not recorded. */
    public const val WITHHELD: String = "ip:[withheld]"

    /** Operator switch for staff IP references; off by default. */
    public const val RECORD_STAFF_IP_ENV: String = "CHRONICLE_RECORD_STAFF_IP"

    @Volatile
    internal var recordStaffIp: Boolean = System.getenv(RECORD_STAFF_IP_ENV).equals("true", ignoreCase = true)

    private val reference = Regex("^ip:[0-9a-f]{12}$")

    /** Keyed reference for a dashboard request when recording is on; otherwise null. */
    @JvmStatic
    public fun staffReference(request: HttpServletRequest?): String? =
        if (recordStaffIp && request != null && isStaffRequest()) staffSurfaceReference(request) else null

    /** [staffReference], or [WITHHELD] for a log line. */
    @JvmStatic
    public fun logReference(request: HttpServletRequest?): String = staffReference(request) ?: WITHHELD

    /**
     * Keyed reference regardless of authentication, for the few endpoints only staff or an attacker
     * ever reach: dashboard login, token refresh, and honey-token hits. The guard test limits its
     * callers to those files. [WITHHELD] unless staff IP recording is on.
     */
    @JvmStatic
    public fun staffSurfaceReference(request: HttpServletRequest): String =
        if (recordStaffIp) {
            LogSanitizer.stableFingerprint(ClientIpResolver.resolve(request), prefix = "ip")
        } else {
            WITHHELD
        }

    /** True only for a keyed reference; anything else must not be persisted. */
    @JvmStatic
    public fun isReference(value: String?): Boolean = value != null && reference.matches(value)

    private fun isStaffRequest(): Boolean =
        SecurityContextHolder.getContext()?.authentication is JwtAuthenticationToken
}
