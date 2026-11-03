/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.audit

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditRequest
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.util.LogSanitizer
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

/**
 * Request-level audit records (who did what to which resource, from where, with what outcome, and whether PHI
 * was touched). They are rows of the one append-only `audit` table and the SIEM file, written by
 * [AuditingManager]: a success joins the transaction that owns it, a failure is committed on its own so a
 * rollback cannot erase it.
 */
public open class AuditService(override val auditingManager: AuditingManager) : AuditingComponent {
    internal companion object {
        private const val MAX_AUDIT_STRING_LENGTH = 500
        private const val MAX_COLLECTION_ITEMS = 50
        private val redactedAdditionalDataKeyParts = setOf("token", "secret", "password", "apikey", "api_key")
        private val fingerprintedAdditionalDataKeys = setOf(
            "participantid",
            "participantids",
            "randomdeviceid",
            "randomdeviceids",
            "sourcerandomdeviceid",
            "datasourceid",
            "principalid",
            "targetprincipalid",
            "userid"
        )

        internal fun participantReference(participantId: String): String =
            sanitizeAdditionalDataValue("participantId", participantId).toString()

        internal fun sanitizeAdditionalData(data: Map<String, Any>): Map<String, Any> =
            data.mapValues { (key, value) -> sanitizeAdditionalDataValue(key, value) }

        private fun sanitizeAdditionalDataValue(key: String, value: Any?): Any {
            val normalizedKey = key.lowercase().replace(Regex("[^a-z0-9]"), "")
            return when {
                redactedAdditionalDataKeyParts.any { normalizedKey.contains(it) } -> "[REDACTED]"
                normalizedKey in fingerprintedAdditionalDataKeys -> fingerprintAuditValue(value, prefixForKey(normalizedKey))
                value == null -> "[null]"
                value is String -> LogSanitizer.sanitize(value, MAX_AUDIT_STRING_LENGTH)
                value is Number || value is Boolean -> value
                value is Collection<*> -> value.take(MAX_COLLECTION_ITEMS).map { item ->
                    item?.toString()?.let { LogSanitizer.sanitize(it, MAX_AUDIT_STRING_LENGTH) } ?: "[null]"
                }
                value is Map<*, *> -> value.entries.take(MAX_COLLECTION_ITEMS).associate { (nestedKey, nestedValue) ->
                    val nestedKeyString = nestedKey?.toString() ?: "[null]"
                    LogSanitizer.sanitize(nestedKeyString, 100) to sanitizeAdditionalDataValue(nestedKeyString, nestedValue)
                }
                else -> LogSanitizer.sanitize(value.toString(), MAX_AUDIT_STRING_LENGTH)
            }
        }

        private fun fingerprintAuditValue(value: Any?, prefix: String): Any = when (value) {
            null -> "$prefix:[null]"
            is Collection<*> -> value.take(MAX_COLLECTION_ITEMS).map { item ->
                LogSanitizer.stableFingerprint(item?.toString(), prefix = prefix)
            }
            else -> LogSanitizer.stableFingerprint(value.toString(), prefix = prefix)
        }

        private fun prefixForKey(normalizedKey: String): String = when {
            normalizedKey.contains("participant") -> "participant"
            normalizedKey.contains("device") || normalizedKey.contains("datasource") -> "device"
            normalizedKey.contains("principal") || normalizedKey == "userid" -> "principal"
            else -> "id"
        }
    }

    public fun log(entry: AuditEntryBuilder) {
        val event = entry.build()
        if (entry.succeeded) recordCommittedEvent(event) else recordIntentEvent(event)
    }
}

/** Records a request-level audit entry in the current request's context. */
internal fun AuditService.logWithContext(block: AuditEntryBuilder.() -> Unit) = log(AuditEntryBuilder().apply(block))

/** One request-level audit entry; [action], [resourceType] and [success] are required. */
public class AuditEntryBuilder {
    private var action: AuditEventType? = null
    private var resourceType: String? = null
    private var resourceId: UUID? = null
    private var studyId: UUID? = null
    private var organizationId: UUID? = null
    private var success: Boolean? = null
    private var accessedPHI = false
    private val phiFields = mutableListOf<String>()
    private var responseCode: Int? = null
    private var request: AuditRequest? = AuditRequest.current()
    private val additionalData = mutableMapOf<String, Any>()

    internal val succeeded: Boolean get() = requireNotNull(success) { "success must be set for an audit entry" }

    public fun action(action: AuditEventType): AuditEntryBuilder = apply { this.action = action }
    public fun resourceType(resourceType: String): AuditEntryBuilder = apply { this.resourceType = resourceType }
    public fun resourceId(resourceId: UUID?): AuditEntryBuilder = apply { this.resourceId = resourceId }
    public fun studyId(studyId: UUID?): AuditEntryBuilder = apply { this.studyId = studyId }
    public fun organizationId(organizationId: UUID?): AuditEntryBuilder = apply { this.organizationId = organizationId }
    public fun success(success: Boolean): AuditEntryBuilder = apply { this.success = success }
    public fun accessedPHI(accessedPHI: Boolean): AuditEntryBuilder = apply { this.accessedPHI = accessedPHI }
    public fun phiFields(fields: List<String>): AuditEntryBuilder = apply {
        phiFields.addAll(fields)
        if (fields.isNotEmpty()) accessedPHI = true
    }
    public fun responseCode(code: Int?): AuditEntryBuilder = apply { this.responseCode = code }
    public fun additionalData(data: Map<String, Any>): AuditEntryBuilder = apply { additionalData.putAll(data) }

    /** Sets the request explicitly, for filters and surfaces whose client IP policy differs from the default. */
    public fun request(request: HttpServletRequest?, clientIpRef: String): AuditEntryBuilder =
        apply { this.request = request?.let { AuditRequest.of(it, clientIpRef) } }

    internal fun build(): AuditableEvent {
        // An unauthenticated caller is the anonymous role; the request columns say where it came from.
        val principal = if (AuditRequestContext.isAuthenticated()) Principals.getCurrentSecurablePrincipal() else null
        val data = AuditService.sanitizeAdditionalData(additionalData) + listOfNotNull(
            "success" to succeeded,
            "accessedPHI" to accessedPHI,
            phiFields.takeIf { it.isNotEmpty() }?.let { "phiFields" to it.toList() },
            resourceId?.let { "resourceId" to it.toString() },
            responseCode?.let { "responseCode" to it },
        )
        return AuditableEvent(
            aclKey = AclKey(resourceId ?: studyId ?: organizationId ?: IdConstants.UNINITIALIZED.id),
            securablePrincipalId = principal?.id ?: IdConstants.UNINITIALIZED.id,
            principal = principal?.principal ?: Principals.getAnonymousUser(),
            eventType = requireNotNull(action) { "action must be set for an audit entry" },
            description = requireNotNull(resourceType) { "resourceType must be set for an audit entry" },
            study = studyId ?: IdConstants.UNINITIALIZED.id,
            organization = organizationId ?: IdConstants.UNINITIALIZED.id,
            data = data,
            request = request,
        )
    }
}
