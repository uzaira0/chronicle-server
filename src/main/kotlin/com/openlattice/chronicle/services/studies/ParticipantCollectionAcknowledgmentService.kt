package com.openlattice.chronicle.services.studies

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.services.enrollment.loadLockedStudy
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_COLLECTION_ACKNOWLEDGMENT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIT_ENTRY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.COLLECTION_TRIGGER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DECLINED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DISCLOSURE_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVIDENCE_ACCESS_CODE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVIDENCE_API_KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MANIFEST_DIGEST
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RECORDED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SOURCE_DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UNAVAILABLE_MODULES
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.util.DeviceIdUtils
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Server-derived evidence used to validate one participant collection decision. */
public data class CollectionAcknowledgmentAuthority(
    val accessCodeId: UUID,
    val apiKeyId: UUID,
    val enrollmentManifestDigest: String,
    val enrollmentSettingsVersion: Int,
    val enrollmentDisclosureVersion: String,
    val enrollmentEnabledModules: Set<CollectionModuleId>,
    val enrollmentRequiredModules: Set<CollectionModuleId>,
    val latestSettingsVersion: Int,
    val immutableDisclosureVersion: String,
    val decisionSettingsVersion: Int,
    val decisionEnabledModules: Set<CollectionModuleId>,
    val decisionRequiredModules: Set<CollectionModuleId>,
)

internal enum class IssuedCollectionDecisionState {
    ACCEPTED,
    DECLINED,
    UNAVAILABLE,
}

internal data class IssuedCollectionDecision(
    val state: IssuedCollectionDecisionState,
    val settingsVersion: Int,
)

private data class AcknowledgmentPersistenceContext(
    val studyId: UUID,
    val participantId: String,
    val sourceDeviceId: String,
    val deviceId: UUID,
    val apiKeyId: UUID,
    val entryId: UUID = UUID.randomUUID(),
    val recordedAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * Persists an append-only consent trail only after comparing client evidence with the exact
 * authenticated key's enrollment receipt, an immutable issued settings revision, and the locked latest study state.
 */
public open class ParticipantCollectionAcknowledgmentService(
    private val storageResolver: StorageResolver,
    private val authorityLoader: (
        Connection,
        UUID,
        String,
        UUID,
        UUID,
        Int,
    ) -> CollectionAcknowledgmentAuthority = ::loadCollectionAcknowledgmentAuthority,
    private val enrollmentReplayLoader: (
        Connection,
        UUID,
        UUID,
    ) -> CollectionAcknowledgmentEntry? = ::loadEnrollmentAcknowledgment,
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(ParticipantCollectionAcknowledgmentService::class.java)

        private val INSERT_ACK_SQL = """
            INSERT INTO ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name} (
                ${AUDIT_ENTRY_ID.name}, ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, ${SOURCE_DEVICE_ID.name},
                ${ACKNOWLEDGED_MODULES.name}, ${ACKNOWLEDGED_AT.name}, ${RECORDED_AT.name}, ${APP_VERSION.name},
                ${SETTINGS_VERSION.name}, ${DECLINED_MODULES.name}, ${UNAVAILABLE_MODULES.name},
                ${COLLECTION_TRIGGER.name}, ${DISCLOSURE_VERSION.name}, ${MANIFEST_DIGEST.name},
                ${EVIDENCE_ACCESS_CODE_ID.name}, ${EVIDENCE_API_KEY_ID.name}
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val SELECT_ACK_HISTORY_SQL = """
            SELECT
                ${AUDIT_ENTRY_ID.name}, ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, ${SOURCE_DEVICE_ID.name},
                ${ACKNOWLEDGED_MODULES.name}, ${ACKNOWLEDGED_AT.name}, ${RECORDED_AT.name}, ${APP_VERSION.name},
                ${SETTINGS_VERSION.name}, ${DECLINED_MODULES.name}, ${UNAVAILABLE_MODULES.name},
                ${COLLECTION_TRIGGER.name}, ${DISCLOSURE_VERSION.name}, ${MANIFEST_DIGEST.name}
            FROM ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name}
            WHERE ${STUDY_ID.name} = ?
            ORDER BY ${RECORDED_AT.name} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        internal val ACTIVE_COLLECTION_DECISIONS_SQL = """
            SELECT acknowledgment.acknowledged_modules, acknowledgment.declined_modules,
                   acknowledgment.unavailable_modules, acknowledgment.settings_version
            FROM participant_collection_acknowledgment AS acknowledgment
            JOIN api_keys AS mobile_key ON mobile_key.key_id = acknowledgment.evidence_api_key_id
            WHERE mobile_key.study_id = ? AND mobile_key.participant_id = ? AND mobile_key.device_id = ?
              AND mobile_key.revoked = false AND mobile_key.expires_at > now()
            ORDER BY acknowledgment.settings_version, acknowledgment.recorded_at, acknowledgment.id
        """.trimIndent()
    }

    public fun recordAcknowledgment(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
        apiKeyId: UUID,
        acknowledgment: CollectionAcknowledgment,
    ): CollectionAcknowledgmentEntry {
        val requestedSettingsVersion = requireAuthoritativeEvidence(acknowledgment)
        val context = AcknowledgmentPersistenceContext(
            studyId = studyId,
            participantId = participantId,
            sourceDeviceId = sourceDeviceId,
            deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId),
            apiKeyId = apiKeyId,
        )
        val result = persistAcknowledgment(context, acknowledgment, requestedSettingsVersion)
        logger.info(
            "Recorded collection decision {} for study {} with {} accepted, {} declined, {} unavailable",
            LogSanitizer.stableFingerprint(result.id.toString(), prefix = "acknowledgment"),
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
            acknowledgment.acknowledgedModules.size,
            acknowledgment.declinedModules.size,
            acknowledgment.unavailableModules.size,
        )
        return result
    }

    /**
     * Returns whether this enrollment generation lacks a still-applicable acceptance or explicit
     * sensor-unavailable exemption for any module the latest server settings require. The same
     * per-device advisory lock used by acknowledgment persistence gives the upload gate a
     * deterministic ordering point.
     */
    public open fun isCollectionHalted(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
    ): Boolean = withLockedDeviceEvidence(deviceId) { connection ->
        loadCollectionHaltStatus(connection, studyId, participantId, deviceId)
    }

    // reason: this is the transaction boundary; any persistence/validation failure must roll back
    // before the connection is returned, including checked JDBC/Jackson failures.
    @Suppress("TooGenericExceptionCaught")
    private fun persistAcknowledgment(
        context: AcknowledgmentPersistenceContext,
        acknowledgment: CollectionAcknowledgment,
        requestedSettingsVersion: Int,
    ): CollectionAcknowledgmentEntry = withLockedDeviceEvidence(context.deviceId) { connection ->
        val authority = authorityLoader(
            connection,
            context.studyId,
            context.participantId,
            context.deviceId,
            context.apiKeyId,
            requestedSettingsVersion,
        )
        validateAcknowledgment(acknowledgment, authority)
        val existing = if (acknowledgment.trigger == ConsentTrigger.ENROLLMENT) {
            enrollmentReplayLoader(connection, authority.accessCodeId, authority.apiKeyId)
        } else {
            null
        }
        if (existing != null) {
            validateExactEnrollmentReplay(
                existing,
                context.studyId,
                context.participantId,
                context.sourceDeviceId,
                acknowledgment,
            )
            existing
        } else {
            insertAcknowledgment(connection, context, acknowledgment, authority)
            acknowledgmentEntry(context, acknowledgment)
        }
    }

    // reason: this is the transaction boundary; any validation/query failure must roll back
    // before the connection is returned, including checked JDBC/Jackson failures.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> withLockedDeviceEvidence(deviceId: UUID, operation: (Connection) -> T): T {
        val dataSource = storageResolver.getPlatformStorage()
        return dataSource.connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) context@{
                val originalAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    lockDeviceEvidence(connection, deviceId)
                    val result = operation(connection)
                    connection.commit()
                    return@context result
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = originalAutoCommit
                }
            }
        }
    }

    private fun insertAcknowledgment(
        connection: Connection,
        context: AcknowledgmentPersistenceContext,
        acknowledgment: CollectionAcknowledgment,
        authority: CollectionAcknowledgmentAuthority,
    ) {
        connection.prepareStatement(INSERT_ACK_SQL).use { statement ->
            statement.setObject(1, context.entryId)
            statement.setObject(2, context.studyId)
            statement.setString(3, context.participantId)
            statement.setString(4, context.sourceDeviceId)
            statement.setString(5, encodeModules(acknowledgment.acknowledgedModules))
            statement.setObject(6, acknowledgment.acknowledgedAt)
            statement.setObject(7, context.recordedAt)
            statement.setString(8, acknowledgment.appVersion)
            statement.setInt(9, checkNotNull(acknowledgment.settingsVersion))
            statement.setString(10, encodeModules(acknowledgment.declinedModules))
            statement.setString(11, encodeModules(acknowledgment.unavailableModules))
            statement.setString(12, acknowledgment.trigger.name)
            statement.setString(13, acknowledgment.disclosureVersion)
            statement.setString(14, acknowledgment.manifestDigest)
            statement.setObject(15, authority.accessCodeId)
            statement.setObject(16, authority.apiKeyId)
            check(statement.executeUpdate() == 1) { "Collection acknowledgment was not persisted" }
        }
    }

    public fun getAcknowledgments(studyId: UUID, limit: Int, offset: Int): List<CollectionAcknowledgmentEntry> {
        val dataSource = storageResolver.getPlatformStorage()
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(dataSource, SELECT_ACK_HISTORY_SQL) { statement ->
                statement.setObject(1, studyId)
                statement.setInt(2, limit)
                statement.setInt(3, offset)
            },
        ) { resultSet -> acknowledgmentEntry(resultSet) }.toList()
    }
}

private fun acknowledgmentEntry(
    context: AcknowledgmentPersistenceContext,
    acknowledgment: CollectionAcknowledgment,
): CollectionAcknowledgmentEntry = CollectionAcknowledgmentEntry(
    id = context.entryId,
    studyId = context.studyId,
    participantId = context.participantId,
    sourceDeviceId = context.sourceDeviceId,
    acknowledgedModules = acknowledgment.acknowledgedModules,
    acknowledgedAt = acknowledgment.acknowledgedAt,
    declinedModules = acknowledgment.declinedModules,
    unavailableModules = acknowledgment.unavailableModules,
    trigger = acknowledgment.trigger,
    recordedAt = context.recordedAt,
    appVersion = acknowledgment.appVersion,
    settingsVersion = acknowledgment.settingsVersion,
    disclosureVersion = acknowledgment.disclosureVersion,
    manifestDigest = acknowledgment.manifestDigest,
)

private fun loadCollectionHaltStatus(
    connection: Connection,
    studyId: UUID,
    participantId: String,
    deviceId: UUID,
): Boolean {
    val decisions = loadIssuedCollectionDecisions(connection, studyId, participantId, deviceId)
    val latestSettings = loadLatestCollectionSettings(connection, studyId) ?: return true
    val issuedRevisions = loadCollectionRevisionsThrough(
        connection,
        studyId,
        latestSettings.settingsVersion,
    )
    return collectionIsHalted(latestSettings, issuedRevisions, decisions)
}

private fun loadIssuedCollectionDecisions(
    connection: Connection,
    studyId: UUID,
    participantId: String,
    deviceId: UUID,
): Map<CollectionModuleId, IssuedCollectionDecision> {
    return connection.prepareStatement(ParticipantCollectionAcknowledgmentService.ACTIVE_COLLECTION_DECISIONS_SQL)
        .use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, deviceId)
        statement.executeQuery().use { resultSet ->
            buildMap {
                while (resultSet.next()) {
                    val version = nullableInt(resultSet, SETTINGS_VERSION.name) ?: continue
                    val accepted = decodeModules(resultSet.getString(ACKNOWLEDGED_MODULES.name))
                    val declined = decodeModules(resultSet.getString(DECLINED_MODULES.name) ?: "[]")
                    val unavailable = decodeModules(resultSet.getString(UNAVAILABLE_MODULES.name) ?: "[]")
                    require((accepted + declined + unavailable).size == accepted.size + declined.size + unavailable.size) {
                        "Collection acknowledgment contains contradictory module decisions"
                    }
                    require(unavailable.all(SensorCollectionModules::isSensorModule)) {
                        "Collection acknowledgment marks a non-sensor module unavailable"
                    }
                    accepted.forEach { moduleId ->
                        put(
                            moduleId,
                            IssuedCollectionDecision(IssuedCollectionDecisionState.ACCEPTED, version),
                        )
                    }
                    declined.forEach { moduleId ->
                        put(
                            moduleId,
                            IssuedCollectionDecision(IssuedCollectionDecisionState.DECLINED, version),
                        )
                    }
                    unavailable.forEach { moduleId ->
                        put(
                            moduleId,
                            IssuedCollectionDecision(IssuedCollectionDecisionState.UNAVAILABLE, version),
                        )
                    }
                }
            }
        }
    }
}

private fun loadLatestCollectionSettings(
    connection: Connection,
    studyId: UUID,
): AndroidDataCollectionSetting? = connection.prepareStatement(
    "SELECT settings FROM studies WHERE study_id = ? FOR SHARE",
).use { statement ->
    statement.setObject(1, studyId)
    statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) return@use null
        val settings = resultSet.getString("settings")
            ?.let { ObjectMappers.getJsonMapper().readValue<StudySettings>(it) }
            ?: return@use null
        settings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting
    }
}

private fun loadCollectionRevisionsThrough(
    connection: Connection,
    studyId: UUID,
    latestSettingsVersion: Int,
): List<AndroidDataCollectionSetting> = connection.prepareStatement(
    """
        SELECT setting
        FROM data_collection_settings_revisions
        WHERE study_id = ? AND settings_version <= ?
        ORDER BY settings_version
    """.trimIndent(),
).use { statement ->
    statement.setObject(1, studyId)
    statement.setInt(2, latestSettingsVersion)
    statement.executeQuery().use { resultSet ->
        buildList {
            while (resultSet.next()) {
                add(ObjectMappers.getJsonMapper().readValue(resultSet.getString("setting")))
            }
        }
    }
}

/** Pure upload-gate rule, split out so required/optional/disabled transitions are exhaustive. */
internal fun collectionIsHalted(
    latestSettings: AndroidDataCollectionSetting,
    issuedRevisions: List<AndroidDataCollectionSetting>,
    decisions: Map<CollectionModuleId, IssuedCollectionDecision>,
): Boolean {
    val latestModules = latestSettings.effectiveModules()
    val latestEnabled = latestSettings.effectiveEnabledModuleIds()
    val latestRequired = latestModules
        .filter { (moduleId, moduleSetting) ->
            moduleId in latestEnabled && moduleSetting.required
        }
        .keys
    if (latestRequired.isEmpty()) return false

    val revisionsByVersion = issuedRevisions.associateBy(AndroidDataCollectionSetting::settingsVersion)
    if (revisionsByVersion.size != issuedRevisions.size) return true
    if (revisionsByVersion[latestSettings.settingsVersion] != latestSettings) return true

    return latestRequired.any { moduleId ->
        val decision = decisions[moduleId] ?: return@any true
        if (decision.settingsVersion > latestSettings.settingsVersion) return@any true
        when (decision.state) {
            IssuedCollectionDecisionState.DECLINED -> true
            IssuedCollectionDecisionState.UNAVAILABLE -> {
                if (!SensorCollectionModules.isSensorModule(moduleId)) return@any true
                !hasUnchangedAuthoritativeModulePolicy(
                    moduleId,
                    decision.settingsVersion,
                    latestSettings,
                    revisionsByVersion,
                )
            }
            IssuedCollectionDecisionState.ACCEPTED -> !hasUnchangedAuthoritativeModulePolicy(
                moduleId,
                decision.settingsVersion,
                latestSettings,
                revisionsByVersion,
            )
        }
    }
}

private fun hasUnchangedAuthoritativeModulePolicy(
    moduleId: CollectionModuleId,
    decisionSettingsVersion: Int,
    latestSettings: AndroidDataCollectionSetting,
    revisionsByVersion: Map<Int, AndroidDataCollectionSetting>,
): Boolean {
    val latestSettingsVersion = latestSettings.settingsVersion
    if (decisionSettingsVersion <= 0 || decisionSettingsVersion > latestSettingsVersion) return false
    val expectedRevisionCount = latestSettingsVersion.toLong() - decisionSettingsVersion.toLong() + 1L
    if (expectedRevisionCount > revisionsByVersion.size.toLong()) return false

    val currentModulePolicy = latestSettings.effectiveModules()[moduleId] ?: return false
    return (decisionSettingsVersion..latestSettingsVersion).all { settingsVersion ->
        revisionsByVersion[settingsVersion]
            ?.effectiveModules()
            ?.get(moduleId) == currentModulePolicy
    }
}

private fun requireAuthoritativeEvidence(acknowledgment: CollectionAcknowledgment): Int {
    val settingsVersion = requireNotNull(acknowledgment.settingsVersion) {
        "settingsVersion must cite authoritative server state"
    }
    requireNotNull(acknowledgment.disclosureVersion) {
        "disclosureVersion must cite authoritative server state"
    }
    requireNotNull(acknowledgment.manifestDigest) {
        "manifestDigest must cite the enrollment receipt"
    }
    return settingsVersion
}

private fun validateExactEnrollmentReplay(
    existing: CollectionAcknowledgmentEntry,
    studyId: UUID,
    participantId: String,
    sourceDeviceId: String,
    acknowledgment: CollectionAcknowledgment,
) {
    require(
        existing.studyId == studyId &&
            existing.participantId == participantId &&
            existing.sourceDeviceId == sourceDeviceId &&
            existing.acknowledgedModules == acknowledgment.acknowledgedModules &&
            existing.acknowledgedAt.toInstant() == acknowledgment.acknowledgedAt.toInstant() &&
            existing.declinedModules == acknowledgment.declinedModules &&
            existing.unavailableModules == acknowledgment.unavailableModules &&
            existing.trigger == acknowledgment.trigger &&
            existing.appVersion == acknowledgment.appVersion &&
            existing.settingsVersion == acknowledgment.settingsVersion &&
            existing.disclosureVersion == acknowledgment.disclosureVersion &&
            existing.manifestDigest == acknowledgment.manifestDigest
    ) {
        "Enrollment acknowledgment receipt already exists with different evidence"
    }
}

private fun lockDeviceEvidence(connection: Connection, deviceId: UUID) {
    connection.prepareStatement(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
    ).use { statement ->
        statement.setObject(1, deviceId)
        statement.execute()
    }
}

private fun validateAcknowledgment(
    acknowledgment: CollectionAcknowledgment,
    authority: CollectionAcknowledgmentAuthority,
) {
    val settingsVersion = requireNotNull(acknowledgment.settingsVersion) {
        "settingsVersion must cite authoritative server state"
    }
    val disclosureVersion = requireNotNull(acknowledgment.disclosureVersion) {
        "disclosureVersion must cite authoritative server state"
    }
    val manifestDigest = requireNotNull(acknowledgment.manifestDigest) {
        "manifestDigest must cite the enrollment receipt"
    }
    require(secureAsciiEquals(manifestDigest, authority.enrollmentManifestDigest)) {
        "manifestDigest does not match the enrollment receipt"
    }
    if (acknowledgment.trigger == ConsentTrigger.ENROLLMENT) {
        validateEnrollmentAcknowledgment(acknowledgment, authority, settingsVersion, disclosureVersion)
    } else {
        validatePostEnrollmentAcknowledgment(acknowledgment, authority, settingsVersion, disclosureVersion)
    }
}

private fun validateEnrollmentAcknowledgment(
    acknowledgment: CollectionAcknowledgment,
    authority: CollectionAcknowledgmentAuthority,
    settingsVersion: Int,
    disclosureVersion: String,
) {
    require(settingsVersion == authority.enrollmentSettingsVersion) {
        "settingsVersion does not match the enrollment receipt"
    }
    require(disclosureVersion == authority.enrollmentDisclosureVersion) {
        "disclosureVersion does not match the enrollment receipt"
    }
    val partition = acknowledgment.decisionModules()
    require(partition == authority.enrollmentEnabledModules) {
        "Enrollment decision must partition the authoritative enabled module set"
    }
    require(acknowledgment.unavailableModules.all(SensorCollectionModules::isSensorModule)) {
        "Only per-sensor modules may be unavailable"
    }
    val requiredAvailable = authority.enrollmentRequiredModules - acknowledgment.unavailableModules
    require(acknowledgment.acknowledgedModules.containsAll(requiredAvailable)) {
        "Every available required module must be accepted at enrollment"
    }
}

private fun validatePostEnrollmentAcknowledgment(
    acknowledgment: CollectionAcknowledgment,
    authority: CollectionAcknowledgmentAuthority,
    settingsVersion: Int,
    disclosureVersion: String,
) {
    require(settingsVersion >= authority.enrollmentSettingsVersion) {
        "settingsVersion predates this device's enrollment receipt"
    }
    require(settingsVersion <= authority.latestSettingsVersion) {
        "settingsVersion postdates the latest server-issued settings"
    }
    require(settingsVersion == authority.decisionSettingsVersion) {
        "settingsVersion does not match the server-issued decision revision"
    }
    require(disclosureVersion == authority.immutableDisclosureVersion) {
        "disclosureVersion does not match the immutable participant disclosure"
    }
    require(authority.decisionEnabledModules.containsAll(acknowledgment.decisionModules())) {
        "Decision contains a module that was not enabled by the cited settings revision"
    }
    require(acknowledgment.unavailableModules.all(SensorCollectionModules::isSensorModule)) {
        "Only per-sensor modules may be unavailable"
    }
    val declinedRequired = acknowledgment.declinedModules.intersect(authority.decisionRequiredModules)
    if (declinedRequired.isNotEmpty()) {
        require(
            acknowledgment.trigger == ConsentTrigger.SETTINGS_CHANGE ||
                acknowledgment.trigger == ConsentTrigger.WITHDRAWAL,
        ) {
            "Required modules may be declined only through the collection-halt or withdrawal flow"
        }
    }
}

private fun CollectionAcknowledgment.decisionModules(): Set<CollectionModuleId> =
    acknowledgedModules + declinedModules + unavailableModules

private fun loadCollectionAcknowledgmentAuthority(
    connection: Connection,
    studyId: UUID,
    participantId: String,
    deviceId: UUID,
    apiKeyId: UUID,
    requestedSettingsVersion: Int,
): CollectionAcknowledgmentAuthority {
    val study = loadLockedStudy(connection, studyId)
    val latestSettings = study.settings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting
        ?: AndroidDataCollectionSetting.fromLegacy(
            study.settings[StudySettingType.AndroidSensor] as? AndroidSensorSetting,
        )
    val immutablePolicy = (study.settings[StudySettingType.ParticipantPolicy] as? StudyParticipantPolicy)
        ?.validate()
        ?: throw IllegalArgumentException("Study participant policy is unavailable")
    val decisionSettings = loadIssuedCollectionSettings(
        connection,
        studyId,
        requestedSettingsVersion,
    )
    val decisionModules = decisionSettings.effectiveModules()

    val receipt = loadEnrollmentEvidenceReceipt(connection, studyId, participantId, deviceId, apiKeyId)
    require(immutablePolicy.version == receipt.disclosureVersion) {
        "Study participant policy differs from the immutable enrollment disclosure"
    }
    return CollectionAcknowledgmentAuthority(
        accessCodeId = receipt.accessCodeId,
        apiKeyId = receipt.apiKeyId,
        enrollmentManifestDigest = receipt.manifestDigest,
        enrollmentSettingsVersion = receipt.settingsVersion,
        enrollmentDisclosureVersion = receipt.disclosureVersion,
        enrollmentEnabledModules = receipt.enabledModules,
        enrollmentRequiredModules = receipt.requiredModules,
        latestSettingsVersion = latestSettings.settingsVersion,
        immutableDisclosureVersion = receipt.disclosureVersion,
        decisionSettingsVersion = decisionSettings.settingsVersion,
        decisionEnabledModules = decisionSettings.effectiveEnabledModuleIds(),
        decisionRequiredModules = decisionModules.filterValues { it.enabled && it.required }.keys,
    )
}

private fun loadIssuedCollectionSettings(
    connection: Connection,
    studyId: UUID,
    requestedSettingsVersion: Int,
): AndroidDataCollectionSetting {
    val sql = """
        SELECT setting
        FROM data_collection_settings_revisions
        WHERE study_id = ? AND settings_version = ?
    """.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, studyId)
        statement.setInt(2, requestedSettingsVersion)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "settingsVersion was not issued by the immutable server ledger" }
            val issued = ObjectMappers.getJsonMapper()
                .readValue<AndroidDataCollectionSetting>(resultSet.getString("setting"))
            require(issued.settingsVersion == requestedSettingsVersion) {
                "Immutable settings ledger revision does not match settingsVersion"
            }
            require(!resultSet.next()) { "settingsVersion has ambiguous immutable server evidence" }
            issued
        }
    }
}

private data class EnrollmentEvidenceReceipt(
    val accessCodeId: UUID,
    val apiKeyId: UUID,
    val manifestDigest: String,
    val settingsVersion: Int,
    val disclosureVersion: String,
    val enabledModules: Set<CollectionModuleId>,
    val requiredModules: Set<CollectionModuleId>,
)

private fun loadEnrollmentEvidenceReceipt(
    connection: Connection,
    studyId: UUID,
    participantId: String,
    deviceId: UUID,
    apiKeyId: UUID,
): EnrollmentEvidenceReceipt {
    val sql = """
        SELECT code.access_code_id, mobile_key.key_id, code.enrollment_manifest_digest,
               code.enrollment_settings_version, code.enrollment_disclosure_version,
               code.enrollment_enabled_modules, code.enrollment_required_modules
        FROM api_keys AS mobile_key
        JOIN participant_form_access_codes AS code
          ON code.study_id = mobile_key.study_id
         AND code.participant_id = mobile_key.participant_id
         AND code.enrollment_device_id = mobile_key.device_id
         AND code.enrollment_proposed_key_hash = mobile_key.key_hash
        WHERE mobile_key.key_id = ? AND mobile_key.study_id = ?
          AND mobile_key.participant_id = ? AND mobile_key.device_id = ?
          AND mobile_key.revoked = false AND mobile_key.expires_at > now()
          AND code.form_kind = 'ENROLLMENT' AND code.exchanged_at IS NOT NULL
          AND code.enrollment_attempt_id IS NOT NULL AND code.revoked_at IS NULL
        FOR SHARE OF mobile_key, code
    """.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, apiKeyId)
        statement.setObject(2, studyId)
        statement.setString(3, participantId)
        statement.setObject(4, deviceId)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "Authenticated key has no enrollment evidence receipt" }
            val receipt = EnrollmentEvidenceReceipt(
                accessCodeId = resultSet.getObject("access_code_id", UUID::class.java),
                apiKeyId = resultSet.getObject("key_id", UUID::class.java),
                manifestDigest = requireNotNull(resultSet.getString("enrollment_manifest_digest")),
                settingsVersion = nullableInt(resultSet, "enrollment_settings_version")
                    ?: throw IllegalArgumentException("Enrollment receipt predates consent evidence binding"),
                disclosureVersion = requireNotNull(resultSet.getString("enrollment_disclosure_version")),
                enabledModules = decodeModules(requireNotNull(resultSet.getString("enrollment_enabled_modules"))),
                requiredModules = decodeModules(requireNotNull(resultSet.getString("enrollment_required_modules"))),
            )
            require(!resultSet.next()) { "Authenticated key has ambiguous enrollment evidence receipts" }
            receipt
        }
    }
}

private fun nullableInt(resultSet: ResultSet, column: String): Int? {
    val value = resultSet.getInt(column)
    return if (resultSet.wasNull()) null else value
}

private fun encodeModules(modules: Set<CollectionModuleId>): String =
    ObjectMappers.getJsonMapper().writeValueAsString(modules.map { it.id }.sorted())

private fun decodeModules(json: String): Set<CollectionModuleId> =
    ObjectMappers.getJsonMapper().readValue<List<String>>(json)
        .map { id -> requireNotNull(CollectionModuleId.fromIdOrNull(id)) { "Unknown collection module in receipt" } }
        .toCollection(LinkedHashSet())

private fun secureAsciiEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
    first.toByteArray(StandardCharsets.US_ASCII),
    second.toByteArray(StandardCharsets.US_ASCII),
)

private fun acknowledgmentEntry(resultSet: ResultSet): CollectionAcknowledgmentEntry {
    val trigger = resultSet.getString(COLLECTION_TRIGGER.name)
        ?.let { value -> runCatching { ConsentTrigger.valueOf(value) }.getOrNull() }
        ?: ConsentTrigger.ENROLLMENT
    return CollectionAcknowledgmentEntry(
        id = resultSet.getObject(AUDIT_ENTRY_ID.name, UUID::class.java),
        studyId = resultSet.getObject(STUDY_ID.name, UUID::class.java),
        participantId = resultSet.getString(PARTICIPANT_ID.name),
        sourceDeviceId = resultSet.getString(SOURCE_DEVICE_ID.name),
        acknowledgedModules = decodeModules(resultSet.getString(ACKNOWLEDGED_MODULES.name)),
        acknowledgedAt = resultSet.getObject(ACKNOWLEDGED_AT.name, OffsetDateTime::class.java),
        declinedModules = decodeModules(resultSet.getString(DECLINED_MODULES.name) ?: "[]"),
        unavailableModules = decodeModules(resultSet.getString(UNAVAILABLE_MODULES.name) ?: "[]"),
        trigger = trigger,
        recordedAt = resultSet.getObject(RECORDED_AT.name, OffsetDateTime::class.java),
        appVersion = resultSet.getString(APP_VERSION.name),
        settingsVersion = nullableInt(resultSet, SETTINGS_VERSION.name),
        disclosureVersion = resultSet.getString(DISCLOSURE_VERSION.name),
        manifestDigest = resultSet.getString(MANIFEST_DIGEST.name),
    )
}

private fun loadEnrollmentAcknowledgment(
    connection: Connection,
    accessCodeId: UUID,
    apiKeyId: UUID,
): CollectionAcknowledgmentEntry? {
    val sql = """
        SELECT
            ${AUDIT_ENTRY_ID.name}, ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, ${SOURCE_DEVICE_ID.name},
            ${ACKNOWLEDGED_MODULES.name}, ${ACKNOWLEDGED_AT.name}, ${RECORDED_AT.name}, ${APP_VERSION.name},
            ${SETTINGS_VERSION.name}, ${DECLINED_MODULES.name}, ${UNAVAILABLE_MODULES.name},
            ${COLLECTION_TRIGGER.name}, ${DISCLOSURE_VERSION.name}, ${MANIFEST_DIGEST.name}
        FROM ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name}
        WHERE ${EVIDENCE_ACCESS_CODE_ID.name} = ? AND ${EVIDENCE_API_KEY_ID.name} = ?
          AND ${COLLECTION_TRIGGER.name} = 'ENROLLMENT'
    """.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, accessCodeId)
        statement.setObject(2, apiKeyId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            val entry = acknowledgmentEntry(resultSet)
            require(!resultSet.next()) { "Enrollment acknowledgment evidence is ambiguous" }
            entry
        }
    }
}
