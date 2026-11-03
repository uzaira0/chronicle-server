package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.exceptions.StudyNotFoundException

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_DELETION_SCHEDULE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_LIFECYCLE_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATION_STUDIES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETE_AFTER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LIFECYCLE_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NEW_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PREVIOUS_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REASON
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCHEDULED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_LIMITS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ENDS
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyLifecycleStatus
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.services.webhooks.WebhookPayload
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.openlattice.chronicle.i18n.Messages
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

public open class StudyLifecycleService(
    private val storageResolver: StorageResolver,
    private val studyService: StudyService,
    private val authorizationService: AuthorizationManager,
    private val idGenerationService: HazelcastIdGenerationService,
    override val auditingManager: AuditingManager,
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
    private val webhookService: WebhookService,
    private val studyLimitsMgr: StudyLimitsManager,
    private val clock: Clock = Clock.systemUTC(),
) : AuditingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyLifecycleService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()
        private const val DELETION_OPERATION_ID = "operation_id"

        /** V1 studies_title_format: at most 2000 UTF-16 code units, the same unit as the API @Size(max = 2000). */
        private const val STUDY_TITLE_MAX_UTF16 = 2000

        private val GET_LIFECYCLE_STATUS_SQL = """
            SELECT ${LIFECYCLE_STATUS.name} FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        private val GET_LIFECYCLE_STATUS_FOR_UPDATE_SQL = """
            SELECT ${LIFECYCLE_STATUS.name} FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ? FOR UPDATE
        """.trimIndent()

        private val UPDATE_LIFECYCLE_STATUS_SQL = """
            UPDATE ${STUDIES.name}
            SET ${LIFECYCLE_STATUS.name} = ?, updated_at = now(),
                -- Every lifecycle writer advances the study revision that dashboard If-Match is checked against.
                settings_revision = settings_revision + 1
            WHERE ${STUDY_ID.name} = ?
            RETURNING ${LIFECYCLE_STATUS.name}
        """.trimIndent()

        private val INSERT_LIFECYCLE_EVENT_SQL = """
            INSERT INTO ${STUDY_LIFECYCLE_EVENTS.name}
                (${EVENT_ID.name}, ${STUDY_ID.name}, ${PREVIOUS_STATUS.name}, ${NEW_STATUS.name}, ${CHANGED_BY.name}, ${REASON.name})
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val INSERT_DELETION_SCHEDULE_SQL = """
            INSERT INTO ${STUDY_DELETION_SCHEDULE.name}
                (${STUDY_ID.name}, ${SCHEDULED_BY.name}, ${DELETE_AFTER.name},
                 $DELETION_OPERATION_ID, ${PREVIOUS_STATUS.name})
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (${STUDY_ID.name}) DO UPDATE
                SET ${SCHEDULED_BY.name} = EXCLUDED.${SCHEDULED_BY.name},
                    ${DELETE_AFTER.name} = EXCLUDED.${DELETE_AFTER.name},
                    $DELETION_OPERATION_ID = EXCLUDED.$DELETION_OPERATION_ID,
                    ${PREVIOUS_STATUS.name} = EXCLUDED.${PREVIOUS_STATUS.name},
                    ${CREATED_AT.name} = now()
        """.trimIndent()

        private val DELETE_DELETION_SCHEDULE_SQL = """
            DELETE FROM ${STUDY_DELETION_SCHEDULE.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        private val GET_SCHEDULED_PREVIOUS_STATUS_SQL = """
            SELECT ${PREVIOUS_STATUS.name}
            FROM ${STUDY_DELETION_SCHEDULE.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        private val CLONE_ORG_STUDIES_SQL = """
            INSERT INTO ${ORGANIZATION_STUDIES.name}
                (${ORGANIZATION_ID.name}, ${STUDY_ID.name}, user_id, settings)
            SELECT ${ORGANIZATION_ID.name}, ?, user_id, settings
            FROM ${ORGANIZATION_STUDIES.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()
    }

    /** Archives the study if the caller saw [expectedRevision]; replaying an archive that already landed is a no-op. */
    public open fun archiveStudy(studyId: UUID, userId: String, expectedRevision: Long) {
        transitionStatus(
            studyId, userId, StudyLifecycleStatus.ACTIVE, StudyLifecycleStatus.ARCHIVED, "Archived by user",
            expectedRevision = expectedRevision,
        )
        logger.info("Study {} archived by user {}", studyId, userId)
    }

    /** Restores the study if the caller saw [expectedRevision]; replaying a restore that already landed is a no-op. */
    public open fun unarchiveStudy(studyId: UUID, userId: String, expectedRevision: Long) {
        transitionStatus(
            studyId, userId, StudyLifecycleStatus.ARCHIVED, StudyLifecycleStatus.ACTIVE, "Unarchived by user",
            expectedRevision = expectedRevision,
        )
        logger.info("Study {} unarchived by user {}", studyId, userId)
    }

    public open fun cloneStudy(studyId: UUID, userId: String, request: StudyCloneRequest): UUID {
        val originalStudy = studyService.getStudy(studyId)
        val newStudyId = idGenerationService.getNextId()
        val newTitle = request.newTitle.ifBlank { cloneTitle(originalStudy.title) }
        val aclKey = AclKey(newStudyId)
        val creator = Principals.getCurrentUser()
        val adminRole = Principals.getAdminRole()
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    RLSConnectionCustomizer.authorizeCreatedStudy(conn, newStudyId)
                    // Insert cloned study row
                    insertClonedStudy(conn, newStudyId, newTitle, originalStudy, request)
                    studyLimitsMgr.initializeStudyLimits(conn, newStudyId)

                    // Clone organization associations
                    conn.prepareStatement(CLONE_ORG_STUDIES_SQL).use { ps ->
                        ps.setObject(1, newStudyId)
                        ps.setObject(2, studyId)
                        ps.executeUpdate()
                    }

                    // Set up authorization
                    authorizationService.createUnnamedSecurableObject(
                        connection = conn,
                        aclKey = aclKey,
                        principal = creator,
                        objectType = SecurableObjectType.Study
                    )
                    authorizationService.createUnnamedSecurableObject(
                        connection = conn,
                        aclKey = aclKey,
                        principal = adminRole,
                        objectType = SecurableObjectType.Study
                    )
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            aclKey,
                            eventType = AuditEventType.CLONE_STUDY,
                            description = "Cloned from study $studyId",
                            study = newStudyId,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = mapOf("sourceStudyId" to studyId.toString())
                        )
                    )
                }
                .buildAndRun()
        }

        // The clone and both ACEs are durable at this point. Cache warming is
        // recoverable read-through work and must not turn a committed clone
        // into an apparent failure that encourages a duplicate retry.
        listOf(creator, adminRole).forEach { principal ->
            try {
                authorizationService.ensureAceIsLoaded(aclKey, principal)
            } catch (ex: Exception) {
                logger.error(
                    "Cloned study {} committed, but the {} ACE cache warm failed.",
                    newStudyId,
                    principal.type,
                    ex,
                )
            }
        }
        logger.info("Study {} cloned to {} by user {}", studyId, newStudyId, userId)
        return newStudyId
    }

    /** "Copy of <title>", cut to the studies.title limit without splitting a surrogate pair. */
    private fun cloneTitle(title: String): String {
        val copy = "Copy of $title"
        if (copy.length <= STUDY_TITLE_MAX_UTF16) return copy
        val end = if (copy[STUDY_TITLE_MAX_UTF16 - 1].isHighSurrogate()) STUDY_TITLE_MAX_UTF16 - 1 else STUDY_TITLE_MAX_UTF16
        return copy.substring(0, end)
    }

    private fun insertClonedStudy(
        conn: Connection,
        newStudyId: UUID,
        newTitle: String,
        originalStudy: Study,
        request: StudyCloneRequest,
    ) {
        conn.prepareStatement(StudyService.INSERT_STUDY_SQL).use { ps ->
            ps.setObject(1, newStudyId)
            ps.setString(2, newTitle)
            ps.setString(3, originalStudy.description)
            ps.setDouble(4, originalStudy.lat)
            ps.setDouble(5, originalStudy.lon)
            ps.setString(6, originalStudy.group)
            ps.setString(7, originalStudy.version)
            ps.setString(8, originalStudy.contact)
            ps.setString(9, originalStudy.storage)
            ps.setString(10, if (request.includeSettings) mapper.writeValueAsString(originalStudy.settings) else "{}")
            ps.setString(11, mapper.writeValueAsString(originalStudy.modules))
            ps.setString(12, originalStudy.phoneNumber)
            ps.executeUpdate()
        }
    }

    /**
     * Records the deletion request and blocks the study's data at once. Physical erasure waits for an owner
     * to run it; one stable key per study makes uncertain HTTP retries resolve the same request.
     */
    public open fun requestStudyDeletion(studyId: UUID, userId: String): UUID {
        val aclKey = AclKey(studyId)
        val requestedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val operationId = dataDeletionOrchestrator.quarantineStudyAtomically(
            studyId = studyId,
            requestedBy = userId,
            idempotencyKey = studyErasureIdempotencyKey(studyId),
        ) { connection, deletionOperationId, inserted ->
            val previousStatus = getCurrentStatusForUpdate(connection, studyId)
            if (previousStatus == StudyLifecycleStatus.SCHEDULED_FOR_DELETION) {
                check(!inserted) { "Study deletion is already requested" }
                return@quarantineStudyAtomically
            }
            check(
                previousStatus == StudyLifecycleStatus.ACTIVE ||
                    previousStatus == StudyLifecycleStatus.ARCHIVED
            ) { "Study cannot be scheduled for deletion from $previousStatus" }

            updateStatus(connection, studyId, StudyLifecycleStatus.SCHEDULED_FOR_DELETION)
            insertLifecycleEvent(
                connection,
                studyId,
                previousStatus,
                StudyLifecycleStatus.SCHEDULED_FOR_DELETION,
                userId,
                "Deletion requested",
            )
            // delete_after records when deletion was requested; erasure itself waits for an owner.
            connection.prepareStatement(INSERT_DELETION_SCHEDULE_SQL).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, userId)
                statement.setObject(3, requestedAt)
                statement.setObject(4, deletionOperationId)
                statement.setString(5, previousStatus.name)
                statement.executeUpdate()
            }
            enqueueStudyStatusChanged(
                connection,
                studyId,
                StudyLifecycleStatus.SCHEDULED_FOR_DELETION,
                "Deletion requested",
                previousStatus,
            )
            recordEvent(connection,
                AuditableEvent(
                    aclKey,
                    eventType = AuditEventType.SCHEDULE_STUDY_DELETION,
                    description = "Deletion requested",
                    study = studyId,
                    organization = IdConstants.UNINITIALIZED.id,
                    data = mapOf("operationId" to deletionOperationId.toString()),
                )
            )
        }
        studyService.refreshStudyCache(setOf(studyId))
        logger.info("Study {} deletion requested by user {}", studyId, userId)
        return operationId
    }

    public open fun cancelScheduledDeletion(studyId: UUID, userId: String) {
        val aclKey = AclKey(studyId)
        dataDeletionOrchestrator.cancelStudyErasureAtomically(studyId, userId) { connection, cancelledCount ->
            val currentStatus = getCurrentStatusForUpdate(connection, studyId)
            check(currentStatus == StudyLifecycleStatus.SCHEDULED_FOR_DELETION || cancelledCount > 0) {
                "Study is not scheduled for deletion"
            }
            val restoredStatus = if (currentStatus == StudyLifecycleStatus.SCHEDULED_FOR_DELETION) {
                getPreDeletionStatus(connection, studyId)
            } else {
                // The erasure request was recorded without a lifecycle change, so there is none to undo.
                currentStatus
            }
            check(
                restoredStatus == StudyLifecycleStatus.ACTIVE ||
                    restoredStatus == StudyLifecycleStatus.ARCHIVED
            ) { "Scheduled deletion has no restorable lifecycle state" }

            if (currentStatus != restoredStatus) {
                updateStatus(connection, studyId, restoredStatus)
                insertLifecycleEvent(
                    connection,
                    studyId,
                    currentStatus,
                    restoredStatus,
                    userId,
                    "Scheduled deletion cancelled",
                )
                enqueueStudyStatusChanged(
                    connection,
                    studyId,
                    restoredStatus,
                    "Scheduled deletion cancelled",
                    currentStatus,
                )
            }
            connection.prepareStatement(DELETE_DELETION_SCHEDULE_SQL).use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            recordEvent(connection,
                AuditableEvent(
                    aclKey,
                    eventType = AuditEventType.CANCEL_SCHEDULED_DELETION,
                    description = "Scheduled deletion cancelled",
                    study = studyId,
                    organization = IdConstants.UNINITIALIZED.id,
                    data = mapOf("cancelledOperations" to cancelledCount),
                )
            )
        }
        studyService.refreshStudyCache(setOf(studyId))
        logger.info("Scheduled deletion cancelled for study {} by user {}", studyId, userId)
    }

    public open fun getLifecycleStatus(studyId: UUID): StudyLifecycleStatus {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            getCurrentStatus(connection, studyId)
        }
    }

    private fun getPreDeletionStatus(connection: Connection, studyId: UUID): StudyLifecycleStatus {
        return connection.prepareStatement(GET_SCHEDULED_PREVIOUS_STATUS_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Scheduled deletion has no restorable lifecycle state" }
                StudyLifecycleStatus.valueOf(rows.getString(PREVIOUS_STATUS.name))
            }
        }
    }

    private fun studyErasureIdempotencyKey(studyId: UUID): UUID =
        // deterministic retry idempotency key
        // nosemgrep: chronicle-uuid-from-string
        UUID.nameUUIDFromBytes("study-erasure:$studyId:default".toByteArray(Charsets.UTF_8))

    private fun transitionStatus(
        studyId: UUID, userId: String,
        expectedFrom: StudyLifecycleStatus, targetStatus: StudyLifecycleStatus,
        reason: String,
        onlyIfExpired: Boolean = false,
        expectedRevision: Long? = null,
    ): Boolean {
        val aclKey = AclKey(studyId)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Boolean>(connection, auditingManager)
                .transaction { conn ->
                    val currentStatus = getCurrentStatusForUpdate(conn, studyId)
                    if (expectedRevision != null) {
                        if (currentStatus == targetStatus) return@transaction false
                        if (studyService.getStudySettingsRevision(conn, studyId) != expectedRevision) {
                            throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED, Messages.get("error.study.lifecycleChanged"))
                        }
                    }
                    check(currentStatus == expectedFrom) {
                        "Cannot transition study $studyId from $currentStatus to $targetStatus (expected $expectedFrom)"
                    }
                    if (onlyIfExpired && !studyHasExpired(conn, studyId)) return@transaction false
                    updateStatus(conn, studyId, targetStatus)
                    insertLifecycleEvent(conn, studyId, currentStatus, targetStatus, userId, reason)
                    enqueueStudyStatusChanged(conn, studyId, targetStatus, reason, currentStatus)
                    true
                }
                .audit { changed ->
                    if (!changed) emptyList() else listOf(
                        AuditableEvent(
                            aclKey,
                            eventType = if (targetStatus == StudyLifecycleStatus.ARCHIVED)
                                AuditEventType.ARCHIVE_STUDY else AuditEventType.UNARCHIVE_STUDY,
                            description = reason,
                            study = studyId,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = mapOf(
                                "previousStatus" to expectedFrom.name,
                                "newStatus" to targetStatus.name
                            )
                        )
                    )
                }
                .buildAndRun()
        }.also { changed -> if (changed) studyService.refreshStudyCache(setOf(studyId)) }
    }

    private fun enqueueStudyStatusChanged(
        connection: Connection,
        studyId: UUID,
        newStatus: StudyLifecycleStatus,
        reason: String,
        previousStatus: StudyLifecycleStatus? = null,
    ) {
        webhookService.enqueueEvent(
            connection,
            studyId,
            WebhookEventType.STUDY_STATUS_CHANGED,
            WebhookPayload.StudyStatusChanged(previousStatus?.name, newStatus.name, reason),
        )
    }

    private fun getCurrentStatus(connection: Connection, studyId: UUID): StudyLifecycleStatus {
        return readCurrentStatus(connection, studyId, GET_LIFECYCLE_STATUS_SQL)
    }

    /**
     * Serializes lifecycle mutations on the study row. Deletion scheduling also holds the
     * deletion advisory lock, but archive/unarchive do not; the common row lock prevents a
     * concurrent archive from overwriting SCHEDULED_FOR_DELETION after quarantine commits.
     */
    private fun getCurrentStatusForUpdate(connection: Connection, studyId: UUID): StudyLifecycleStatus {
        return readCurrentStatus(connection, studyId, GET_LIFECYCLE_STATUS_FOR_UPDATE_SQL)
    }

    private fun readCurrentStatus(
        connection: Connection,
        studyId: UUID,
        sql: String,
    ): StudyLifecycleStatus {
        return connection.prepareStatement(sql).use { ps ->
            ps.setObject(1, studyId)
            val rs = ps.executeQuery()
            if (!rs.next()) throw StudyNotFoundException(studyId)
            StudyLifecycleStatus.valueOf(rs.getString(LIFECYCLE_STATUS.name))
        }
    }

    private fun updateStatus(connection: Connection, studyId: UUID, status: StudyLifecycleStatus) {
        connection.prepareStatement(UPDATE_LIFECYCLE_STATUS_SQL).use { ps ->
            ps.setString(1, status.name)
            ps.setObject(2, studyId)
            val rs = ps.executeQuery()
            if (!rs.next()) throw StudyNotFoundException(studyId)
        }
    }

    // reason: boundary catch — a failure auto-archiving one study must be logged and not abort the batch, regardless of failure type
    @Suppress("TooGenericExceptionCaught")
    public open fun autoArchiveExpiredStudies() {
        val expiredStudyIds = mutableListOf<UUID>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("""
                SELECT sl.${STUDY_ID.name}
                FROM ${STUDY_LIMITS.name} sl
                JOIN ${STUDIES.name} s ON s.${STUDY_ID.name} = sl.${STUDY_ID.name}
                WHERE sl.${STUDY_ENDS.name} <= now()
                AND s.${LIFECYCLE_STATUS.name} = '${StudyLifecycleStatus.ACTIVE.name}'
            """.trimIndent()).use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    expiredStudyIds.add(rs.getObject(STUDY_ID.name, UUID::class.java))
                }
            }
        }

        if (expiredStudyIds.isEmpty()) {
            logger.debug("No expired studies to auto-archive")
            return
        }

        logger.info("Auto-archiving {} expired studies: {}", expiredStudyIds.size, expiredStudyIds)
        for (studyId in expiredStudyIds) {
            try {
                val archived = transitionStatus(
                    studyId, "system",
                    StudyLifecycleStatus.ACTIVE, StudyLifecycleStatus.ARCHIVED,
                    "Auto-archived: study end date has passed", onlyIfExpired = true,
                )
                if (archived) logger.info("Auto-archived expired study {}", studyId)
            } catch (ex: Exception) {
                logger.error("Failed to auto-archive study {}", studyId, ex)
                ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.AUTO_ARCHIVE)
            }
        }
    }

    private fun insertLifecycleEvent(
        connection: Connection, studyId: UUID,
        previousStatus: StudyLifecycleStatus, newStatus: StudyLifecycleStatus,
        changedBy: String, reason: String
    ) {
        connection.prepareStatement(INSERT_LIFECYCLE_EVENT_SQL).use { ps ->
            ps.setObject(1, idGenerationService.getNextId())
            ps.setObject(2, studyId)
            ps.setString(3, previousStatus.name)
            ps.setString(4, newStatus.name)
            ps.setString(5, changedBy)
            ps.setString(6, reason)
            ps.executeUpdate()
        }
    }
}
