package com.openlattice.chronicle.services.studies

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_SETTINGS_AUDIT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AFTER_VALUE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIT_ENTRY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BEFORE_VALUE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGE_SUMMARY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTING_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SOURCE_IP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettingsAuditEntry
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for recording and retrieving study settings audit trail entries.
 */
public open class StudySettingsAuditService(
    private val storageResolver: StorageResolver
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(StudySettingsAuditService::class.java)
        private val mapper = ObjectMappers.getJsonMapper()

        private val INSERT_AUDIT_ENTRY_SQL = """
            INSERT INTO ${STUDY_SETTINGS_AUDIT.name} (
                ${AUDIT_ENTRY_ID.name},
                ${STUDY_ID.name},
                ${CHANGED_BY.name},
                ${CHANGED_AT.name},
                ${SOURCE_IP.name},
                ${SETTING_KEY.name},
                ${BEFORE_VALUE.name},
                ${AFTER_VALUE.name},
                ${CHANGE_SUMMARY.name}
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
        """.trimIndent()

        private val SELECT_AUDIT_HISTORY_SQL = """
            SELECT
                ${AUDIT_ENTRY_ID.name},
                ${STUDY_ID.name},
                ${CHANGED_BY.name},
                ${CHANGED_AT.name},
                ${SOURCE_IP.name},
                ${SETTING_KEY.name},
                ${BEFORE_VALUE.name},
                ${AFTER_VALUE.name},
                ${CHANGE_SUMMARY.name}
            FROM ${STUDY_SETTINGS_AUDIT.name}
            WHERE ${STUDY_ID.name} = ?
            ORDER BY ${CHANGED_AT.name} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
    }

    public fun recordSettingsChange(
        studyId: UUID,
        changedBy: String,
        sourceIp: String?,
        settingKey: StudySettingType,
        beforeValue: Any?,
        afterValue: Any,
        changeSummary: String
    ) {
        val hds = storageResolver.getPlatformStorage()
        hds.connection.use { connection ->
            connection.prepareStatement(INSERT_AUDIT_ENTRY_SQL).use { ps ->
                val entryId = UUID.randomUUID()
                ps.setObject(1, entryId)
                ps.setObject(2, studyId)
                ps.setString(3, changedBy)
                ps.setObject(4, OffsetDateTime.now())
                ps.setString(5, sourceIp)
                ps.setString(6, settingKey.name)
                ps.setString(7, if (beforeValue != null) mapper.writeValueAsString(beforeValue) else null)
                ps.setString(8, mapper.writeValueAsString(afterValue))
                ps.setString(9, changeSummary)
                ps.executeUpdate()
                logger.info("Recorded settings audit entry {} for study {} setting {}", entryId, studyId, settingKey)
            }
        }
    }

    public fun getAuditHistory(studyId: UUID, limit: Int, offset: Int): List<StudySettingsAuditEntry> {
        val hds = storageResolver.getPlatformStorage()
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, SELECT_AUDIT_HISTORY_SQL) { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
            }
        ) { rs ->
            val beforeJson = rs.getString(BEFORE_VALUE.name)
            val afterJson = rs.getString(AFTER_VALUE.name)
            StudySettingsAuditEntry(
                id = rs.getObject(AUDIT_ENTRY_ID.name, UUID::class.java),
                studyId = rs.getObject(STUDY_ID.name, UUID::class.java),
                changedBy = rs.getString(CHANGED_BY.name),
                changedAt = rs.getObject(CHANGED_AT.name, OffsetDateTime::class.java),
                sourceIp = rs.getString(SOURCE_IP.name),
                settingKey = StudySettingType.valueOf(rs.getString(SETTING_KEY.name)),
                beforeValue = if (beforeJson != null) mapper.readValue<Map<String, Any?>>(beforeJson) else null,
                afterValue = mapper.readValue<Map<String, Any?>>(afterJson),
                changeSummary = rs.getString(CHANGE_SUMMARY.name)
            )
        }.toList()
    }

    /**
     * Generates a human-readable summary of what changed between before and after values.
     */
    // reason: guard-clause summary builder — each early return is a distinct, mutually-exclusive
    // human-readable outcome (first-config / no-change / module-diff / map-diff / generic); the
    // staged returns keep the precedence explicit and are clearer than nested branching
    @Suppress("ReturnCount")
    public fun generateChangeSummary(settingKey: StudySettingType, beforeValue: Any?, afterValue: Any): String {
        if (beforeValue == null) {
            return "Setting '$settingKey' was configured for the first time"
        }

        val beforeJson = mapper.writeValueAsString(beforeValue)
        val afterJson = mapper.writeValueAsString(afterValue)

        if (beforeJson == afterJson) {
            return "Setting '$settingKey' was updated (no effective change)"
        }

        // Module-granular summary for DataCollection settings shaped like
        // AndroidDataCollectionSetting (a `modules` map keyed by CollectionModuleId).
        // Production passes the setting objects (not Maps), so the generic map-diff
        // below never fires for them — this is what surfaces per-module deltas
        // (enabled/disabled/added/removed) in the audit trail. Falls through to the
        // generic diff for any other DataCollection shape (e.g. the legacy free-form
        // ChronicleDataCollectionSettings), preserving existing behavior.
        if (settingKey == StudySettingType.DataCollection) {
            moduleChangeSummary(beforeValue, afterValue)?.let { return it }
        }

        // If both are maps, diff the keys
        if (beforeValue is Map<*, *> && afterValue is Map<*, *>) {
            val changes = mutableListOf<String>()
            val allKeys = (beforeValue.keys + afterValue.keys).toSet()
            for (key in allKeys) {
                val oldVal = beforeValue[key]
                val newVal = afterValue[key]
                when {
                    oldVal == null && newVal != null -> changes.add("added '$key'")
                    oldVal != null && newVal == null -> changes.add("removed '$key'")
                    oldVal != newVal -> changes.add("changed '$key'")
                }
            }
            if (changes.isNotEmpty()) {
                return "Setting '$settingKey' updated: ${changes.joinToString(", ")}"
            }
        }

        return "Setting '$settingKey' was updated"
    }

    /**
     * Produces a per-module change summary for two AndroidDataCollectionSetting-shaped
     * values (each carrying a `modules` map keyed by module id). Reports which modules
     * were enabled, disabled, added, removed, or otherwise reconfigured. Returns `null`
     * if either value is not module-shaped, so the caller can fall back to a generic diff.
     */
    private fun moduleChangeSummary(beforeValue: Any?, afterValue: Any): String? {
        val before = extractModules(beforeValue) ?: return null
        val after = extractModules(afterValue) ?: return null

        val changes = mutableListOf<String>()
        val allModules = (before.keys + after.keys).toSortedSet()
        for (moduleId in allModules) {
            val b = before[moduleId]
            val a = after[moduleId]
            if (b == a) continue // module entry unchanged — don't report it
            changes.add(describeModuleChange(moduleId, b, a))
        }
        if (changes.isEmpty()) return null
        return "Setting 'DataCollection' updated: ${changes.joinToString(", ")}"
    }

    /**
     * Classifies a single module's before/after config into a human-readable change phrase.
     */
    private fun describeModuleChange(
        moduleId: String,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    ): String {
        val bEnabled = before?.get("enabled") as? Boolean
        val aEnabled = after?.get("enabled") as? Boolean
        return when {
            before == null -> if (aEnabled == true) "enabled '$moduleId'" else "added '$moduleId' (disabled)"
            after == null -> "removed '$moduleId'"
            bEnabled == false && aEnabled == true -> "enabled '$moduleId'"
            bEnabled == true && aEnabled == false -> "disabled '$moduleId'"
            else -> "reconfigured '$moduleId'"
        }
    }

    /**
     * Normalizes an AndroidDataCollectionSetting-shaped value to its `modules` map
     * (module id -> per-module config map). Accepts either a raw `Map` or a setting
     * object (serialized via the mapper). Returns `null` when there is no `modules`
     * map, signalling a non-module-shaped value.
     */
    // reason: boundary catch — Jackson convertValue + unchecked map casts can raise several
    // unrelated runtime types; any failure means "not module-shaped" so we fall back to null
    @Suppress("TooGenericExceptionCaught")
    private fun extractModules(value: Any?): Map<String, Map<String, Any?>>? {
        if (value == null) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val asMap = when (value) {
                is Map<*, *> -> value as Map<String, Any?>
                else -> mapper.convertValue(value, Map::class.java) as Map<String, Any?>
            }
            val modules = asMap["modules"] as? Map<*, *> ?: return null
            modules.entries.associate { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                k.toString() to (v as? Map<String, Any?> ?: emptyMap())
            }
        } catch (e: Exception) {
            logger.debug("Value is not module-shaped, falling back to generic diff: {}", e.message)
            null
        }
    }
}
