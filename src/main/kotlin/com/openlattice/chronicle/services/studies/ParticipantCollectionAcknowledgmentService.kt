package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.storage.CollectionDecisionRepository
import com.openlattice.chronicle.storage.DeviceCollectionSettingsSnapshot
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.collection.ParticipantConsentState
import com.openlattice.chronicle.storage.current
import com.openlattice.chronicle.storage.decodeCollectionModules

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.services.enrollment.loadLockedStudy
import com.openlattice.chronicle.services.participantaccess.lockDeviceEvidence
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_COLLECTION_ACKNOWLEDGMENT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIT_ENTRY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.COLLECTION_TRIGGER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DECISION_SEQ
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DECLINED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DISCLOSURE_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVIDENCE_ACCESS_CODE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVIDENCE_API_KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MANIFEST_DIGEST
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RECORDED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SOURCE_RANDOM_DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UNAVAILABLE_MODULES
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
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

/** Raised when the atomic recheck finds collection halted after the cheap upload gate passed. */
public class CollectionHaltedException(
    public val studyId: UUID,
    public val participantId: String,
    public val randomDeviceId: UUID,
) : RuntimeException("Required collection consent is unresolved")

/**
 * The acknowledgment itself can never be accepted: its evidence contradicts the enrollment receipt, the issued settings
 * ledger or the decision already recorded under its sequence. Its 400 carries the ack-rejected code, the only refusal
 * the phone answers by dropping the queued acknowledgment; every other failure (request, study, enrollment or server
 * state) is a plain [IllegalArgumentException] and keeps it queued.
 */
public class CollectionAcknowledgmentRejectedException(message: String) : IllegalArgumentException(message)

/** [require] for a rule of the acknowledgment's own evidence. */
private inline fun requireAcceptable(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw CollectionAcknowledgmentRejectedException(lazyMessage())
}

internal typealias IssuedCollectionDecisionState = com.openlattice.chronicle.collection.IssuedCollectionDecisionState
internal typealias IssuedCollectionDecision = com.openlattice.chronicle.collection.IssuedCollectionDecision

private data class AcknowledgmentPersistenceContext(
    val studyId: UUID,
    val participantId: String,
    val sourceRandomDeviceId: String,
    val randomDeviceId: UUID,
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

        private val ACK_COLUMNS = listOf(
            AUDIT_ENTRY_ID, STUDY_ID, PARTICIPANT_ID, SOURCE_RANDOM_DEVICE_ID,
            ACKNOWLEDGED_MODULES, ACKNOWLEDGED_AT, RECORDED_AT, APP_VERSION,
            SETTINGS_VERSION, DECLINED_MODULES, UNAVAILABLE_MODULES,
            COLLECTION_TRIGGER, DISCLOSURE_VERSION, MANIFEST_DIGEST, DECISION_SEQ,
        ).joinToString(", ") { it.name }

        private val INSERT_ACK_SQL = """
            INSERT INTO ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name} (
                $ACK_COLUMNS,
                ${EVIDENCE_ACCESS_CODE_ID.name}, ${EVIDENCE_API_KEY_ID.name}
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val SELECT_ACK_BY_SEQ_SQL = """
            SELECT $ACK_COLUMNS FROM ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name}
            WHERE ${EVIDENCE_API_KEY_ID.name} = ? AND ${DECISION_SEQ.name} = ?
        """.trimIndent()

        private val NEXT_DECISION_SEQ_SQL = """
            SELECT COALESCE(max(${DECISION_SEQ.name}), 0) + 1 FROM ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name}
            WHERE ${EVIDENCE_API_KEY_ID.name} = ?
        """.trimIndent()

        private val SELECT_ACK_HISTORY_SQL = """
            SELECT $ACK_COLUMNS
            FROM ${PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name}
            WHERE ${STUDY_ID.name} = ?
            ORDER BY ${RECORDED_AT.name} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
    }

    public fun collectionSettingsForDevice(studyId: UUID): AndroidDataCollectionSetting? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) { CollectionSettingsRepository(connection).forDevice(studyId) }
        }

    /** Only the already-public collection projection leaves this existing privileged repository scope. */
    internal fun collectionSettingsSnapshotForDevice(studyId: UUID): DeviceCollectionSettingsSnapshot? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                CollectionSettingsRepository(connection).forDeviceSnapshot(studyId)
            }
        }

    /** Each participant's consent standing, read by the gate that admits their uploads. */
    public open fun consentStates(studyId: UUID, participantIds: Collection<String>): Map<String, ParticipantConsentState> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                val gate = ParticipantCollectionGate(connection)
                participantIds.mapNotNull { id -> gate.consentState(studyId, id)?.let { id to it } }.toMap()
            }
        }

    public fun recordAcknowledgment(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        apiKeyId: UUID,
        acknowledgment: CollectionAcknowledgment,
    ): CollectionAcknowledgmentEntry {
        val requestedSettingsVersion = requireAuthoritativeEvidence(acknowledgment)
        val context = AcknowledgmentPersistenceContext(
            studyId = studyId,
            participantId = participantId,
            sourceRandomDeviceId = sourceRandomDeviceId,
            randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId),
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
     * Returns whether this enrollment generation has explicitly declined a currently required
     * module. The same
     * per-device advisory lock used by acknowledgment persistence gives the upload gate a
     * deterministic ordering point.
     */
    public open fun isCollectionHalted(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
    ): Boolean = withLockedDeviceEvidence(studyId, randomDeviceId) { connection ->
        currentlyConsentedModules(connection, studyId, participantId, randomDeviceId, emptySet()) == null
    }

    /**
     * Runs [write] with the upload's authorization, atomically with the halt predicate. The one upload rule: a batch
     * is judged by the settings revision ([settingsVersion]) and the decision sequence
     * ([decisionSeq]) the phone stamped at capture, never by a time. A batch without them is judged at the latest
     * revision by every recorded decision. A standing required decline, an unissued revision or an unavailable
     * participant refuses the whole write ([CollectionHaltedException], 403); otherwise each module is accepted or
     * discarded, nothing is held. With [allowPartial] false the write is refused unless every module is accepted.
     *
     * [isCollectionHalted] is a cheap fast-fail that commits and releases its lock before the caller writes. This
     * opens one transaction, takes the per-device advisory lock that [persistAcknowledgment] also takes, evaluates the
     * rule on that connection, and pins the connection so [write] joins the same transaction. A settings revision must
     * `UPDATE studies`, which blocks on the `FOR SHARE` row lock taken while reading the latest settings.
     */
    public open fun <T> withCollectionUploadRecheck(
        request: CollectionUploadRequest, uploadModules: Set<CollectionModuleId>, allowPartial: Boolean,
        allowQueuedCaptures: Boolean = true,
        write: (CollectionUploadAuthorization) -> T,
    ): T = withCollectionWrite(request.studyId, request.randomDeviceId) { connection ->
        val (studyId, participantId, randomDeviceId) = request
        val halted = { CollectionHaltedException(studyId, participantId, randomDeviceId) }
        val authorization = RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
            if (!ParticipantCollectionGate(connection).isActive(studyId, participantId, allowQueuedCaptures, checkConsent = false)) {
                throw halted()
            }
            val settings = CollectionSettingsRepository(connection)
            val latest = settings.latest(studyId) ?: throw halted()
            val revisions = settings.through(studyId, latest.settingsVersion)
            val captured = if (request.settingsVersion == null) latest
                else revisions.singleOrNull { it.settingsVersion == request.settingsVersion } ?: throw halted()
            val history = CollectionDecisionRepository(connection).history(studyId, participantId, randomDeviceId)
            if ((allowPartial && history.isEmpty()) || collectionIsHalted(latest, revisions, history.current())) throw halted()
            collectionUploadAuthorization(captured, revisions, history.current(request.decisionSeq), uploadModules)
        }
        if (!allowPartial && !authorization.acceptedModules.containsAll(uploadModules)) throw halted()
        write(authorization)
    }

    public open fun <T> withHealthUploadRecheck(request: CollectionUploadRequest, write: (AuthorizedHealthScope) -> T): T =
        withCollectionUploadRecheck(request, setOf(CollectionModuleId.HEALTH_CONNECT), allowPartial = false) { authorization ->
            write(AuthorizedHealthScope(request.studyId, request.participantId, request.randomDeviceId, authorization.healthConnectRecordTypes))
        }

    private fun <T> withCollectionWrite(studyId: UUID, randomDeviceId: UUID, write: (Connection) -> T): T {
        val dataSource = storageResolver.getPlatformStorage()
        return dataSource.connection.use { connection ->
            connection.withTransaction {
                DeletionStudyFence.shared(connection, studyId)
                lockDeviceEvidence(connection, randomDeviceId)
                PinnedPlatformConnection.committing(dataSource, connection) { write(connection) }
            }
        }
    }

    // reason: this is the transaction boundary; any persistence/validation failure must roll back
    // before the connection is returned, including checked JDBC/Jackson failures.
    @Suppress("TooGenericExceptionCaught")
    private fun persistAcknowledgment(
        context: AcknowledgmentPersistenceContext,
        acknowledgment: CollectionAcknowledgment,
        requestedSettingsVersion: Int,
    ): CollectionAcknowledgmentEntry = withLockedDeviceEvidence(context.studyId, context.randomDeviceId) { connection ->
        val authority = authorityLoader(
            connection,
            context.studyId,
            context.participantId,
            context.randomDeviceId,
            context.apiKeyId,
            requestedSettingsVersion,
        )
        validateAcknowledgment(acknowledgment, authority)
        // The phone's decision sequence identifies a retried acknowledgment; an iOS client sends none and gets the next.
        val existing = acknowledgment.decisionSeq?.let { loadAcknowledgmentBySeq(connection, authority.apiKeyId, it) }
            ?: if (acknowledgment.trigger == ConsentTrigger.ENROLLMENT) {
                enrollmentReplayLoader(connection, authority.accessCodeId, authority.apiKeyId)
            } else {
                null
            }
        if (existing != null) {
            validateExactReplay(existing, context.studyId, context.participantId, context.sourceRandomDeviceId, acknowledgment)
            existing
        } else {
            val recorded = acknowledgment.copy(decisionSeq = acknowledgment.decisionSeq ?: nextDecisionSeq(connection, authority.apiKeyId))
            insertAcknowledgment(connection, context, recorded, authority)
            acknowledgmentEntry(context, recorded)
        }
    }

    private fun loadAcknowledgmentBySeq(connection: Connection, apiKeyId: UUID, seq: Long): CollectionAcknowledgmentEntry? =
        connection.prepareStatement(SELECT_ACK_BY_SEQ_SQL).use { statement ->
            statement.setObject(1, apiKeyId)
            statement.setLong(2, seq)
            statement.executeQuery().use { rows -> if (rows.next()) acknowledgmentEntry(rows) else null }
        }

    private fun nextDecisionSeq(connection: Connection, apiKeyId: UUID): Long =
        connection.prepareStatement(NEXT_DECISION_SEQ_SQL).use { statement ->
            statement.setObject(1, apiKeyId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }

    // reason: this is the transaction boundary; any validation/query failure must roll back
    // before the connection is returned, including checked JDBC/Jackson failures.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> withLockedDeviceEvidence(studyId: UUID, randomDeviceId: UUID, operation: (Connection) -> T): T {
        val dataSource = storageResolver.getPlatformStorage()
        return dataSource.connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                connection.withTransaction {
                    // The upload recheck's lock order: the deletion fence, then the device.
                    DeletionStudyFence.shared(connection, studyId)
                    lockDeviceEvidence(connection, randomDeviceId)
                    val result = operation(connection)
                    connection.commit()
                    result
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
            statement.setString(4, context.sourceRandomDeviceId)
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
            statement.setLong(15, checkNotNull(acknowledgment.decisionSeq))
            statement.setObject(16, authority.accessCodeId)
            statement.setObject(17, authority.apiKeyId)
            check(statement.executeUpdate() == 1) { "Collection acknowledgment was not persisted" }
        }
    }

    public fun getAcknowledgments(studyId: UUID, limit: Int, offset: Int): List<CollectionAcknowledgmentEntry> {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            connection.autoCommit = false
            val history = connection.prepareStatement(SELECT_ACK_HISTORY_SQL).use { statement ->
                statement.setObject(1, studyId)
                statement.setInt(2, limit)
                statement.setInt(3, offset)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(acknowledgmentEntry(rows)) } }
            }
            val consent = history.groupBy(CollectionAcknowledgmentEntry::participantId).mapValues { (participant, entries) ->
                entries.groupBy(CollectionAcknowledgmentEntry::sourceRandomDeviceId).mapValues { (sourceDevice, _) ->
                    val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participant, sourceDevice)
                    currentlyConsentedModules(connection, studyId, participant, randomDeviceId,
                        CollectionModuleId.entries.toSet()).orEmpty()
                }
            }
            history.map { entry -> entry.copy(currentConsentedModules =
                consent.getValue(entry.participantId).getValue(entry.sourceRandomDeviceId)) }
        }
    }
}

private fun acknowledgmentEntry(
    context: AcknowledgmentPersistenceContext,
    acknowledgment: CollectionAcknowledgment,
): CollectionAcknowledgmentEntry = CollectionAcknowledgmentEntry(
    id = context.entryId,
    studyId = context.studyId,
    participantId = context.participantId,
    sourceRandomDeviceId = context.sourceRandomDeviceId,
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
    decisionSeq = acknowledgment.decisionSeq,
)

/** The modules the device's current decisions accept at the latest revision; null when collection is halted or closed. */
private fun currentlyConsentedModules(
    connection: Connection,
    studyId: UUID,
    participantId: String,
    randomDeviceId: UUID,
    uploadModules: Set<CollectionModuleId>,
): Set<CollectionModuleId>? {
    if (!ParticipantCollectionGate(connection).isActive(studyId, participantId, checkConsent = false)) return null
    val decisions = CollectionDecisionRepository(connection).history(studyId, participantId, randomDeviceId).current()
    val latest = CollectionSettingsRepository(connection).latest(studyId) ?: return null
    val revisions = CollectionSettingsRepository(connection).through(studyId, latest.settingsVersion)
    if (collectionIsHalted(latest, revisions, decisions)) return null
    return uploadModules.filterTo(linkedSetOf()) { !collectionIsHalted(latest, revisions, decisions, setOf(it)) }
}

/** Who is uploading, and the settings version and decision sequence the phone stamped on the rows at capture. */
public data class CollectionUploadRequest(
    val studyId: UUID,
    val participantId: String,
    val randomDeviceId: UUID,
    val settingsVersion: Int?,
    val decisionSeq: Long?,
)

public data class CollectionUploadAuthorization(
    val acceptedModules: Set<CollectionModuleId>,
    val discardedModules: Set<CollectionModuleId>,
    val settingsVersion: Int,
    val healthConnectRecordTypes: Set<HealthConnectRecordType>,
)

/**
 * Each module of a batch captured at revision [captured] under [decisions] (those in force at the batch's decision
 * sequence) is accepted where those decisions accept it at that revision, and discarded otherwise. A required decline
 * in force at capture discards every module.
 */
internal fun collectionUploadAuthorization(
    captured: AndroidDataCollectionSetting,
    revisions: List<AndroidDataCollectionSetting>,
    decisions: Map<CollectionModuleId, IssuedCollectionDecision>,
    modules: Set<CollectionModuleId>,
): CollectionUploadAuthorization {
    val ledger = revisions.filter { it.settingsVersion <= captured.settingsVersion }
    val accepted = if (collectionIsHalted(captured, ledger, decisions)) emptySet()
        else modules.filterTo(linkedSetOf()) { !collectionIsHalted(captured, ledger, decisions, setOf(it)) }
    val healthRecords = if (CollectionModuleId.HEALTH_CONNECT in accepted) {
        captured.effectiveModules()[CollectionModuleId.HEALTH_CONNECT]?.healthConnectRecordTypes.orEmpty()
    } else emptySet()
    return CollectionUploadAuthorization(accepted, modules - accepted, captured.settingsVersion, healthRecords)
}

/** Evidence adapter; all decision validity and halt rules live in chronicle-models. */
internal fun collectionIsHalted(
    latestSettings: AndroidDataCollectionSetting,
    issuedRevisions: List<AndroidDataCollectionSetting>,
    decisions: Map<CollectionModuleId, IssuedCollectionDecision>,
    uploadModules: Set<CollectionModuleId> = emptySet(),
): Boolean = com.openlattice.chronicle.collection.collectionIsHalted(
    latestSettings, issuedRevisions, decisions, uploadModules)

private fun requireAuthoritativeEvidence(acknowledgment: CollectionAcknowledgment): Int {
    val settingsVersion = acknowledgment.settingsVersion
        ?: throw CollectionAcknowledgmentRejectedException("settingsVersion must cite authoritative server state")
    acknowledgment.disclosureVersion
        ?: throw CollectionAcknowledgmentRejectedException("disclosureVersion must cite authoritative server state")
    acknowledgment.manifestDigest
        ?: throw CollectionAcknowledgmentRejectedException("manifestDigest must cite the enrollment receipt")
    return settingsVersion
}

private fun validateExactReplay(
    existing: CollectionAcknowledgmentEntry,
    studyId: UUID,
    participantId: String,
    sourceRandomDeviceId: String,
    acknowledgment: CollectionAcknowledgment,
) {
    requireAcceptable(
        existing.studyId == studyId &&
            existing.participantId == participantId &&
            existing.sourceRandomDeviceId == sourceRandomDeviceId &&
            existing.acknowledgedModules == acknowledgment.acknowledgedModules &&
            // timestamptz rounds to microseconds; a retry carries the phone's full-precision stamp.
            Duration.between(existing.acknowledgedAt, acknowledgment.acknowledgedAt).abs() < Duration.ofNanos(1_000) &&
            existing.declinedModules == acknowledgment.declinedModules &&
            existing.unavailableModules == acknowledgment.unavailableModules &&
            existing.trigger == acknowledgment.trigger &&
            existing.appVersion == acknowledgment.appVersion &&
            existing.settingsVersion == acknowledgment.settingsVersion &&
            existing.disclosureVersion == acknowledgment.disclosureVersion &&
            existing.manifestDigest == acknowledgment.manifestDigest &&
            (acknowledgment.decisionSeq == null || existing.decisionSeq == acknowledgment.decisionSeq)
    ) {
        "Collection acknowledgment already exists with different evidence"
    }
}

private fun validateAcknowledgment(
    acknowledgment: CollectionAcknowledgment,
    authority: CollectionAcknowledgmentAuthority,
) {
    val settingsVersion = requireAuthoritativeEvidence(acknowledgment)
    val disclosureVersion = checkNotNull(acknowledgment.disclosureVersion)
    val manifestDigest = checkNotNull(acknowledgment.manifestDigest)
    requireAcceptable(secureAsciiEquals(manifestDigest, authority.enrollmentManifestDigest)) {
        "manifestDigest does not match the enrollment receipt"
    }
    // Both paths: Jackson can write a repeated key past the model's init, so the stored decision is checked here.
    requireAcceptable(acknowledgment.unavailableModules.all(SensorCollectionModules::isSensorModule)) {
        "Only per-sensor modules may be unavailable"
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
    requireAcceptable(settingsVersion == authority.enrollmentSettingsVersion) {
        "settingsVersion does not match the enrollment receipt"
    }
    requireAcceptable(disclosureVersion == authority.enrollmentDisclosureVersion) {
        "disclosureVersion does not match the enrollment receipt"
    }
    val partition = acknowledgment.decisionModules()
    requireAcceptable(partition == authority.enrollmentEnabledModules) {
        "Enrollment decision must partition the authoritative enabled module set"
    }
    val requiredAvailable = authority.enrollmentRequiredModules - acknowledgment.unavailableModules
    requireAcceptable(acknowledgment.acknowledgedModules.containsAll(requiredAvailable)) {
        "Every available required module must be accepted at enrollment"
    }
}

private fun validatePostEnrollmentAcknowledgment(
    acknowledgment: CollectionAcknowledgment,
    authority: CollectionAcknowledgmentAuthority,
    settingsVersion: Int,
    disclosureVersion: String,
) {
    requireAcceptable(settingsVersion >= authority.enrollmentSettingsVersion) {
        "settingsVersion predates this device's enrollment receipt"
    }
    // A restored or lagging study projection is not evidence that this decision can never be accepted.
    require(settingsVersion <= authority.latestSettingsVersion) {
        "settingsVersion postdates the latest server-issued settings"
    }
    requireAcceptable(settingsVersion == authority.decisionSettingsVersion) {
        "settingsVersion does not match the server-issued decision revision"
    }
    requireAcceptable(disclosureVersion == authority.immutableDisclosureVersion) {
        "disclosureVersion does not match the immutable participant disclosure"
    }
    requireAcceptable(authority.decisionEnabledModules.containsAll(acknowledgment.decisionModules())) {
        "Decision contains a module that was not enabled by the cited settings revision"
    }
    val declinedRequired = acknowledgment.declinedModules.intersect(authority.decisionRequiredModules)
    if (declinedRequired.isNotEmpty()) {
        requireAcceptable(
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
    randomDeviceId: UUID,
    apiKeyId: UUID,
    requestedSettingsVersion: Int,
): CollectionAcknowledgmentAuthority {
    // An acknowledgment is admitted exactly when the participant's uploads are (pause, completion and
    // archive keep it; withdrawal, Mark not enrolled, a processing block and a deletion refuse it).
    ParticipantCollectionGate(connection).requireCollectionAvailable(studyId, participantId)
    val study = loadLockedStudy(connection, studyId)
    val latestSettings = collectionSettingsForFeatures(study.settings, study.modules.keys.map { it.name }.toSet()).androidDataCollection()
    val immutablePolicy = (study.settings[StudySettingType.ParticipantPolicy] as? StudyParticipantPolicy)
        ?.validate()
        ?: throw IllegalArgumentException("Study participant policy is unavailable")
    val decisionSettings = CollectionSettingsRepository(connection).issued(studyId, requestedSettingsVersion)

    val receipt = loadEnrollmentEvidenceReceipt(connection, studyId, participantId, randomDeviceId, apiKeyId)
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
        decisionRequiredModules = decisionSettings.effectiveRequiredModuleIds(),
    )
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
    randomDeviceId: UUID,
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
         AND code.enrollment_random_device_id = mobile_key.random_device_id
         AND code.enrollment_proposed_key_hash = mobile_key.key_hash
        WHERE mobile_key.key_id = ? AND mobile_key.study_id = ?
          AND mobile_key.participant_id = ? AND mobile_key.random_device_id = ?
          AND mobile_key.revoked = false AND mobile_key.expires_at > now()
          AND code.form_kind = 'ENROLLMENT' AND code.exchanged_at IS NOT NULL
          AND code.enrollment_attempt_id IS NOT NULL AND code.revoked_at IS NULL
        FOR SHARE OF mobile_key, code
    """.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, apiKeyId)
        statement.setObject(2, studyId)
        statement.setString(3, participantId)
        statement.setObject(4, randomDeviceId)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "Authenticated key has no enrollment evidence receipt" }
            val receipt = EnrollmentEvidenceReceipt(
                accessCodeId = resultSet.getObject("access_code_id", UUID::class.java),
                apiKeyId = resultSet.getObject("key_id", UUID::class.java),
                manifestDigest = requireNotNull(resultSet.getString("enrollment_manifest_digest")),
                settingsVersion = nullableInt(resultSet, "enrollment_settings_version")
                    ?: throw IllegalArgumentException("Enrollment receipt predates consent evidence binding"),
                disclosureVersion = requireNotNull(resultSet.getString("enrollment_disclosure_version")),
                enabledModules = decodeCollectionModules(requireNotNull(resultSet.getString("enrollment_enabled_modules"))),
                requiredModules = decodeCollectionModules(requireNotNull(resultSet.getString("enrollment_required_modules"))),
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
        sourceRandomDeviceId = resultSet.getString(SOURCE_RANDOM_DEVICE_ID.name),
        acknowledgedModules = decodeCollectionModules(resultSet.getString(ACKNOWLEDGED_MODULES.name)),
        acknowledgedAt = resultSet.getObject(ACKNOWLEDGED_AT.name, OffsetDateTime::class.java),
        declinedModules = decodeCollectionModules(resultSet.getString(DECLINED_MODULES.name)),
        unavailableModules = decodeCollectionModules(resultSet.getString(UNAVAILABLE_MODULES.name)),
        trigger = trigger,
        recordedAt = resultSet.getObject(RECORDED_AT.name, OffsetDateTime::class.java),
        appVersion = resultSet.getString(APP_VERSION.name),
        settingsVersion = nullableInt(resultSet, SETTINGS_VERSION.name),
        disclosureVersion = resultSet.getString(DISCLOSURE_VERSION.name),
        manifestDigest = resultSet.getString(MANIFEST_DIGEST.name),
        decisionSeq = resultSet.getLong(DECISION_SEQ.name),
    )
}

private fun loadEnrollmentAcknowledgment(
    connection: Connection,
    accessCodeId: UUID,
    apiKeyId: UUID,
): CollectionAcknowledgmentEntry? {
    val sql = """
        SELECT
            ${AUDIT_ENTRY_ID.name}, ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, ${SOURCE_RANDOM_DEVICE_ID.name},
            ${ACKNOWLEDGED_MODULES.name}, ${ACKNOWLEDGED_AT.name}, ${RECORDED_AT.name}, ${APP_VERSION.name},
            ${SETTINGS_VERSION.name}, ${DECLINED_MODULES.name}, ${UNAVAILABLE_MODULES.name},
            ${COLLECTION_TRIGGER.name}, ${DISCLOSURE_VERSION.name}, ${MANIFEST_DIGEST.name}, ${DECISION_SEQ.name}
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
