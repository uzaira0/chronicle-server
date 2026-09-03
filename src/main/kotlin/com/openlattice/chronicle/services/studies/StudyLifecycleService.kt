package com.openlattice.chronicle.services.studies

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
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_DELETION_SCHEDULE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_LIFECYCLE_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_PARTICIPANTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATION_STUDIES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETE_AFTER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LIFECYCLE_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NEW_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPATION_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PREVIOUS_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REASON
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCHEDULED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_LIMITS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ENDS
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyDataSummary
import com.openlattice.chronicle.study.StudyLifecycleStatus
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.webhooks.WebhookEventType
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
    private val clock: Clock = Clock.systemUTC(),
) : AuditingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyLifecycleService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()
        private const val DELETION_OPERATION_ID = "operation_id"

        private val GET_LIFECYCLE_STATUS_SQL = """
            SELECT ${LIFECYCLE_STATUS.name} FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        private val GET_LIFECYCLE_STATUS_FOR_UPDATE_SQL = """
            SELECT ${LIFECYCLE_STATUS.name} FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ? FOR UPDATE
        """.trimIndent()

        private val UPDATE_LIFECYCLE_STATUS_SQL = """
            UPDATE ${STUDIES.name}
            SET ${LIFECYCLE_STATUS.name} = ?, updated_at = now()
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

        private val GET_LEGACY_PRE_DELETION_STATUS_SQL = """
            SELECT ${PREVIOUS_STATUS.name}
            FROM ${STUDY_LIFECYCLE_EVENTS.name}
            WHERE ${STUDY_ID.name} = ? AND ${NEW_STATUS.name} = 'SCHEDULED_FOR_DELETION'
            ORDER BY ${CREATED_AT.name} DESC, ${EVENT_ID.name} DESC
            LIMIT 1
        """.trimIndent()

        private val GET_STUDIES_DUE_FOR_DELETION_SQL = """
            SELECT ${STUDY_ID.name}, ${SCHEDULED_BY.name}, ${DELETE_AFTER.name}
            FROM ${STUDY_DELETION_SCHEDULE.name}
            WHERE ${DELETE_AFTER.name} <= now() AND $DELETION_OPERATION_ID IS NULL
        """.trimIndent()

        private val LINK_DELETION_SCHEDULE_OPERATION_SQL = """
            UPDATE ${STUDY_DELETION_SCHEDULE.name}
            SET $DELETION_OPERATION_ID = ?
            WHERE ${STUDY_ID.name} = ? AND $DELETION_OPERATION_ID IS NULL
        """.trimIndent()

        private val GET_DELETION_SCHEDULE_OPERATION_SQL = """
            SELECT $DELETION_OPERATION_ID
            FROM ${STUDY_DELETION_SCHEDULE.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        private val CLONE_PARTICIPANTS_SQL = """
            INSERT INTO ${STUDY_PARTICIPANTS.name}
                (${STUDY_ID.name}, ${PARTICIPANT_ID.name}, ${PARTICIPATION_STATUS.name})
            SELECT ?, ${PARTICIPANT_ID.name}, ${PARTICIPATION_STATUS.name}
            FROM ${STUDY_PARTICIPANTS.name}
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

    public open fun archiveStudy(studyId: UUID, userId: String) {
        transitionStatus(studyId, userId, StudyLifecycleStatus.ACTIVE, StudyLifecycleStatus.ARCHIVED, "Archived by user")
        logger.info("Study {} archived by user {}", studyId, userId)
    }

    public open fun unarchiveStudy(studyId: UUID, userId: String) {
        transitionStatus(studyId, userId, StudyLifecycleStatus.ARCHIVED, StudyLifecycleStatus.ACTIVE, "Unarchived by user")
        logger.info("Study {} unarchived by user {}", studyId, userId)
    }

    public open fun cloneStudy(studyId: UUID, userId: String, request: StudyCloneRequest): UUID {
        val originalStudy = studyService.getStudy(studyId)
        val newStudyId = idGenerationService.getNextId()
        val newTitle = request.newTitle.ifBlank { "Copy of ${originalStudy.title}" }
        val aclKey = AclKey(newStudyId)
        val creator = Principals.getCurrentUser()
        val adminRole = Principals.getAdminRole()
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    // Insert cloned study row
                    insertClonedStudy(conn, newStudyId, newTitle, originalStudy, request)

                    // Clone organization associations
                    conn.prepareStatement(CLONE_ORG_STUDIES_SQL).use { ps ->
                        ps.setObject(1, newStudyId)
                        ps.setObject(2, studyId)
                        ps.executeUpdate()
                    }

                    // Clone participants if requested
                    if (request.includeParticipants) {
                        conn.prepareStatement(CLONE_PARTICIPANTS_SQL).use { ps ->
                            ps.setObject(1, newStudyId)
                            ps.setObject(2, studyId)
                            ps.executeUpdate()
                        }
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
            ps.setBoolean(9, originalStudy.notificationsEnabled)
            ps.setString(10, originalStudy.storage)
            ps.setString(11, if (request.includeSettings) mapper.writeValueAsString(originalStudy.settings) else "{}")
            ps.setString(12, mapper.writeValueAsString(originalStudy.modules))
            ps.setString(13, originalStudy.phoneNumber)
            ps.executeUpdate()
        }
    }

    public open fun scheduleStudyDeletion(studyId: UUID, userId: String, deleteAfter: OffsetDateTime): UUID =
        scheduleStudyDeletionAtomically(
            studyId = studyId,
            userId = userId,
            deleteAfter = deleteAfter,
            idempotencyKey = studyErasureIdempotencyKey(studyId, deleteAfter),
        )

    /**
     * Compatibility path for the legacy direct-delete endpoint, which has no client-supplied
     * idempotency key. One stable key per study makes uncertain HTTP retries resolve the same
     * quarantine operation instead of creating a second erasure request.
     */
    public open fun scheduleImmediateStudyDeletion(studyId: UUID, userId: String): UUID =
        scheduleStudyDeletionAtomically(
            studyId = studyId,
            userId = userId,
            deleteAfter = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            idempotencyKey = studyErasureIdempotencyKey(studyId, null),
        )

    private fun scheduleStudyDeletionAtomically(
        studyId: UUID,
        userId: String,
        deleteAfter: OffsetDateTime,
        idempotencyKey: UUID,
    ): UUID {
        val aclKey = AclKey(studyId)
        val operationId = dataDeletionOrchestrator.quarantineStudyAtomically(
            studyId = studyId,
            requestedBy = userId,
            idempotencyKey = idempotencyKey,
            requestedDeleteAfter = deleteAfter,
        ) { connection, deletionOperationId, inserted ->
            val previousStatus = getCurrentStatusForUpdate(connection, studyId)
            if (previousStatus == StudyLifecycleStatus.SCHEDULED_FOR_DELETION) {
                check(!inserted) {
                    "Study is already scheduled for deletion; cancel it before choosing a new date"
                }
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
                "Scheduled for deletion after $deleteAfter",
            )
            connection.prepareStatement(INSERT_DELETION_SCHEDULE_SQL).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, userId)
                statement.setObject(3, deleteAfter)
                statement.setObject(4, deletionOperationId)
                statement.setString(5, previousStatus.name)
                statement.executeUpdate()
            }
            enqueueStudyStatusChanged(
                connection,
                studyId,
                StudyLifecycleStatus.SCHEDULED_FOR_DELETION,
                "Scheduled for deletion after $deleteAfter",
                previousStatus,
            )
            recordEvent(
                AuditableEvent(
                    aclKey,
                    eventType = AuditEventType.SCHEDULE_STUDY_DELETION,
                    description = "Scheduled for deletion after $deleteAfter",
                    study = studyId,
                    organization = IdConstants.UNINITIALIZED.id,
                    data = mapOf(
                        "deleteAfter" to deleteAfter.toString(),
                        "operationId" to deletionOperationId.toString(),
                    ),
                )
            )
        }
        logger.info("Study {} scheduled for deletion after {} by user {}", studyId, deleteAfter, userId)
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
                // Compatibility for quarantines created by the legacy direct-delete endpoint,
                // which did not update lifecycle state before this transaction boundary existed.
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
            recordEvent(
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
        logger.info("Scheduled deletion cancelled for study {} by user {}", studyId, userId)
    }

    public open fun getLifecycleStatus(studyId: UUID): StudyLifecycleStatus {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            getCurrentStatus(connection, studyId)
        }
    }

    public open fun executeScheduledDeletions() {
        data class DueStudy(val studyId: UUID, val requestedBy: String, val deleteAfter: OffsetDateTime?)
        val dueStudies = mutableListOf<DueStudy>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_STUDIES_DUE_FOR_DELETION_SQL).use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    dueStudies.add(
                        DueStudy(
                            studyId = rs.getObject(STUDY_ID.name, UUID::class.java),
                            requestedBy = rs.getString(SCHEDULED_BY.name) ?: "legacy-schedule",
                            deleteAfter = rs.getObject(DELETE_AFTER.name, OffsetDateTime::class.java),
                        )
                    )
                }
            }
        }

        if (dueStudies.isEmpty()) {
            logger.debug("No studies due for deletion")
            return
        }

        // Reconcile only legacy schedules that predate their durable operation link. Keep
        // the schedule row until cancellation or physical study erasure so its exact prior
        // lifecycle status remains available throughout the quarantine.
        dueStudies.forEach { due ->
            dataDeletionOrchestrator.quarantineStudyAtomically(
                studyId = due.studyId,
                requestedBy = due.requestedBy,
                idempotencyKey = studyErasureIdempotencyKey(due.studyId, due.deleteAfter),
                requestedDeleteAfter = due.deleteAfter,
            ) { connection, operationId, _ ->
                val linked = connection.prepareStatement(LINK_DELETION_SCHEDULE_OPERATION_SQL).use { statement ->
                    statement.setObject(1, operationId)
                    statement.setObject(2, due.studyId)
                    statement.executeUpdate() == 1
                }
                if (!linked) {
                    check(getDeletionScheduleOperation(connection, due.studyId) == operationId) {
                        "Study deletion schedule is linked to a different erasure operation"
                    }
                    return@quarantineStudyAtomically
                }
                recordEvent(
                    AuditableEvent(
                        AclKey(due.studyId),
                        eventType = AuditEventType.SCHEDULE_STUDY_DELETION,
                        description = "Linked legacy deletion schedule to verified erasure processing",
                        study = due.studyId,
                        organization = IdConstants.UNINITIALIZED.id,
                        data = mapOf("operationId" to operationId.toString()),
                    )
                )
            }
        }
        logger.info("Successfully reconciled {} scheduled study deletions", dueStudies.size)
    }

    private fun getDeletionScheduleOperation(connection: Connection, studyId: UUID): UUID? =
        connection.prepareStatement(GET_DELETION_SCHEDULE_OPERATION_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getObject(DELETION_OPERATION_ID, UUID::class.java) else null
            }
        }

    private fun getPreDeletionStatus(connection: Connection, studyId: UUID): StudyLifecycleStatus {
        connection.prepareStatement(GET_SCHEDULED_PREVIOUS_STATUS_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    resultSet.getString(PREVIOUS_STATUS.name)?.let { return StudyLifecycleStatus.valueOf(it) }
                }
            }
        }
        return connection.prepareStatement(GET_LEGACY_PRE_DELETION_STATUS_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Scheduled deletion has no restorable lifecycle state" }
                StudyLifecycleStatus.valueOf(resultSet.getString(PREVIOUS_STATUS.name))
            }
        }
    }

    private fun studyErasureIdempotencyKey(studyId: UUID, deleteAfter: OffsetDateTime?): UUID =
        UUID.nameUUIDFromBytes( // nosemgrep: chronicle-uuid-from-string -- deterministic retry idempotency key
            "study-erasure:$studyId:${deleteAfter?.toInstant() ?: "default"}".toByteArray(Charsets.UTF_8)
        )

    private fun transitionStatus(
        studyId: UUID, userId: String,
        expectedFrom: StudyLifecycleStatus, targetStatus: StudyLifecycleStatus,
        reason: String
    ) {
        val aclKey = AclKey(studyId)
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    val currentStatus = getCurrentStatusForUpdate(conn, studyId)
                    check(currentStatus == expectedFrom) {
                        "Cannot transition study $studyId from $currentStatus to $targetStatus (expected $expectedFrom)"
                    }
                    updateStatus(conn, studyId, targetStatus)
                    insertLifecycleEvent(conn, studyId, currentStatus, targetStatus, userId, reason)
                    enqueueStudyStatusChanged(conn, studyId, targetStatus, reason, currentStatus)
                }
                .audit {
                    listOf(
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
        }
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
            buildMap {
                previousStatus?.let { put("previousStatus", it.name) }
                put("newStatus", newStatus.name)
                put("reason", reason)
            },
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
            check(rs.next()) { "Study $studyId not found" }
            StudyLifecycleStatus.valueOf(rs.getString(LIFECYCLE_STATUS.name))
        }
    }

    private fun updateStatus(connection: Connection, studyId: UUID, status: StudyLifecycleStatus) {
        connection.prepareStatement(UPDATE_LIFECYCLE_STATUS_SQL).use { ps ->
            ps.setString(1, status.name)
            ps.setObject(2, studyId)
            val rs = ps.executeQuery()
            check(rs.next()) { "Study $studyId not found" }
        }
    }

    public open fun getStudyDataSummary(studyId: UUID): StudyDataSummary {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            val status = getCurrentStatus(connection, studyId)
            StudyDataSummary(
                studyId = studyId,
                participantCount = countRows(connection, "study_participants", studyId),
                usageEventsCount = countRows(connection, "chronicle_usage_events", studyId),
                preprocessedEventsCount = countRows(connection, "preprocessed_usage_events", studyId),
                sensorDataCount = countRows(connection, "sensor_data", studyId),
                androidSensorDataCount = countRows(connection, "android_sensor_data", studyId),
                appUsageSurveyCount = countRows(connection, "app_usage_survey", studyId),
                questionnaireSubmissionsCount = countRows(connection, "questionnaire_submissions", studyId),
                tudSubmissionsCount = countRows(connection, "time_use_diary_submissions", studyId),
                lifecycleStatus = status
            )
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
                transitionStatus(
                    studyId, "system",
                    StudyLifecycleStatus.ACTIVE, StudyLifecycleStatus.ARCHIVED,
                    "Auto-archived: study end date has passed"
                )
                logger.info("Auto-archived expired study {}", studyId)
            } catch (ex: Exception) {
                logger.warn("Failed to auto-archive study {}", studyId, ex)
            }
        }
    }

    private fun countRows(connection: Connection, tableName: String, studyId: UUID): Long {
        return connection.prepareStatement("SELECT COUNT(*) FROM $tableName WHERE study_id = ?").use { ps ->
            ps.setObject(1, studyId)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getLong(1) else 0L
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
