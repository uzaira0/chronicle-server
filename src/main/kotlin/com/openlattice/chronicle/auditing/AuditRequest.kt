package com.openlattice.chronicle.auditing

import com.openlattice.chronicle.audit.AuditRequestContext
import com.openlattice.chronicle.util.ClientIpRecord
import com.openlattice.chronicle.util.LogSanitizer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders

/**
 * The request context of an audit record: who, from where, through what. The client address is
 * only ever the keyed reference [ClientIpRecord] allows for this caller, never a raw IP.
 */
public data class AuditRequest(
    val clientIpRef: String,
    val userAgent: String?,
    val path: String?,
    val method: String?,
) {
    public companion object {
        private const val MAX_USER_AGENT_LENGTH = 500

        public fun current(): AuditRequest? = AuditRequestContext.getCurrentRequest()?.let { of(it) }

        public fun of(request: HttpServletRequest, clientIpRef: String = ClientIpRecord.logReference(request)): AuditRequest =
            AuditRequest(
                // A raw address never reaches the audit table or SIEM file, whatever a caller passes.
                clientIpRef = clientIpRef.takeIf(ClientIpRecord::isReference) ?: ClientIpRecord.WITHHELD,
                userAgent = request.getHeader(HttpHeaders.USER_AGENT)?.let { LogSanitizer.sanitize(it, MAX_USER_AGENT_LENGTH) },
                path = request.requestURI?.let { LogSanitizer.sanitizeRequestPath(it) },
                method = request.method,
            )
    }
}
