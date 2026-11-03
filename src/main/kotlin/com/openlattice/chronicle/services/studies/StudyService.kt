package com.openlattice.chronicle.services.studies

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Permission.READ
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.SystemUser
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.withoutConsentEpochs
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionCadenceModules
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.participantaccess.EnrollmentAccessCodeRepository
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.webhooks.ParticipantRef
import com.openlattice.chronicle.services.webhooks.WebhookPayload
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATION_STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_STATS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PERMISSIONS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_PARTICIPANTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_PARTICIPANTS_WITH_WITHDRAWALS
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACL_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONTACT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DESCRIPTION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LAT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LON
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_IDS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPATION_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STORAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_GROUP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_PHONE_NUMBER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TITLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPDATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.USER_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.ensureVanilla
import com.openlattice.chronicle.util.validateParticipantId
import com.openlattice.chronicle.validation.validateParticipantIdNotPii
import com.openlattice.chronicle.webhooks.WebhookEventType
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.*

/** Assigns the authoritative revision for DataCollection settings, ignoring client-supplied revisions. */
internal fun stampDataCollectionSettingsVersion(
    priorSettings: StudySettings?,
    requestedUpdate: StudyUpdate,
    priorFeatures: Set<String>,
    requestedFeatures: Set<String>,
): StudyUpdate {
    val requestedSettings = requestedUpdate.settings ?: return requestedUpdate
    val requested = (requestedSettings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting
        ?: return requestedUpdate).withoutConsentEpochs()
    validateClientSupportedCollectionPolicy(requested)
    val normalizedSettings = StudySettings(requestedSettings + (StudySettingType.DataCollection to requested))
    val prior = priorSettings?.get(StudySettingType.DataCollection) as? AndroidDataCollectionSetting
    // Compare effective choices: the settings read answers the complete map, storage keeps it sparse.
    val unchangedChoices = prior != null && requested.copy(
        modules = requested.effectiveModules(CollectionModuleId.activeModules),
        settingsVersion = prior.settingsVersion,
    ) == prior.copy(modules = prior.effectiveModules(CollectionModuleId.activeModules))
    val unchangedEffective = priorSettings != null &&
        collectionSettingsForFeatures(priorSettings, priorFeatures).androidDataCollection() ==
        collectionSettingsForFeatures(normalizedSettings, requestedFeatures).androidDataCollection()
            .copy(settingsVersion = prior?.settingsVersion ?: requested.settingsVersion)
    val stampedVersion = when {
        prior == null -> AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION
        unchangedChoices && unchangedEffective -> prior.settingsVersion
        else -> prior.settingsVersion + 1
    }
    // Preserve stored set order when choices compare equal; revision evidence is immutable.
    val authoritative = (if (unchangedChoices) prior else requested).copy(settingsVersion = stampedVersion)
    return requestedUpdate.copy(settings = StudySettings(
        requestedSettings + mapOf(StudySettingType.DataCollection to authoritative),
    ))
}

/** Ignores caller-supplied collection revisions when a study is first created. */
internal fun stampInitialDataCollectionSettings(study: Study): Study {
    val initialSettings = StudySettings(
        study.settings + (StudySettingType.DataCollection to study.settings.androidDataCollection()),
    )
    val stampedSettings = checkNotNull(
        stampDataCollectionSettingsVersion(
            priorSettings = null,
            requestedUpdate = StudyUpdate(settings = initialSettings),
            priorFeatures = emptySet(),
            requestedFeatures = study.modules.keys.map { it.name }.toSet(),
        ).settings,
    )
    if (stampedSettings == study.settings) return study
    return Study(
        studyId = study.id,
        title = study.title,
        description = study.description,
        createdAt = study.createdAt,
        updatedAt = study.updatedAt,
        startedAt = study.startedAt,
        endedAt = study.endedAt,
        lat = study.lat,
        lon = study.lon,
        group = study.group,
        version = study.version,
        contact = study.contact,
        organizationIds = study.organizationIds,
        storage = study.storage,
        settings = stampedSettings,
        modules = study.modules,
        phoneNumber = study.phoneNumber,
    )
}

internal data class LockedStudyUpdate(
    val priorSettings: StudySettings?,
    val stampedStudy: StudyUpdate,
    /** Settings revision after the write, for the response `ETag`. */
    val settingsRevision: Long = 0L,
)

internal class EnrollmentDisclosurePendingException(val blockingParticipantIds: List<String>) : ResponseStatusException(
    HttpStatus.CONFLICT,
    Messages.format("error.study.enrollmentDisclosurePending", blockingParticipantIds.joinToString(", ")),
)

/** Raised when an `If-Match` settings precondition does not match the row-locked revision. */
public class StudySettingsRevisionMismatchException(
    public val currentRevision: Long,
    public val currentSettings: StudySettings,
) : RuntimeException("Study settings were modified by another client")

/** Merges the settings patch over the row-locked prior settings, then stamps the collection version. */
internal fun mergeAndStampDataCollectionSettingsVersionLocked(
    connection: Connection,
    studyId: UUID,
    requestedUpdate: StudyUpdate,
): LockedStudyUpdate {
    if (requestedUpdate.settings == null && requestedUpdate.modules == null) return LockedStudyUpdate(null, requestedUpdate)
    val priorSettings = loadLockedStudySettings(connection, studyId)
    val priorFeatures = StudySnapshotRepository(connection).loadLockedStudyFeatures(studyId)
    val features = requestedUpdate.modules?.keys?.mapTo(mutableSetOf()) { it.name } ?: priorFeatures
    val settings = StudySettings(priorSettings + requestedUpdate.settings.orEmpty())
    return LockedStudyUpdate(
        priorSettings,
        stampDataCollectionSettingsVersion(
            priorSettings, requestedUpdate.copy(settings = settings),
            priorFeatures = priorFeatures, requestedFeatures = features,
        ),
    )
}

internal fun loadLockedStudySettings(
    connection: Connection,
    studyId: UUID,
): StudySettings = StudySnapshotRepository(connection).loadLockedStudySettings(studyId)
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.study.notFound"))

internal fun checkLockedStudyRevision(
    connection: Connection,
    studyId: UUID,
    expectedRevision: Long?,
) {
    if (expectedRevision == null) return
    val settings = loadLockedStudySettings(connection, studyId)
    requireSettingsRevision(expectedRevision, StudySnapshotRepository(connection).settingsRevision(studyId), settings)
}

/** The one `If-Match` comparison: a supplied revision must equal the row-locked one; absent means no precondition. */
private fun requireSettingsRevision(expectedRevision: Long?, currentRevision: Long, lockedSettings: StudySettings) {
    if (expectedRevision != null && expectedRevision != currentRevision) {
        throw StudySettingsRevisionMismatchException(currentRevision, lockedSettings)
    }
}

/**
 * The row-locked body of a settings write: read the authoritative map, check the optional
 * `If-Match` precondition, apply the per-type delta, persist, and report the new revision.
 *
 * The mutation only ever sees the row-locked map, so a write of one setting type can never rewrite
 * another type from a client-supplied merge. Everything happens on [transaction], which holds the
 * `FOR UPDATE` lock taken by [loadLockedStudySettings], so a concurrent write of a different type
 * is serialized behind this one rather than clobbering it.
 */
internal fun applyLockedSettingsMutation(
    transaction: Connection,
    studyId: UUID,
    expectedRevision: Long?,
    studyService: StudyManager,
    mutation: (StudySettings) -> StudySettings,
): LockedStudyUpdate {
    val priorSettings = loadLockedStudySettings(transaction, studyId)
    requireSettingsRevision(expectedRevision, studyService.getStudySettingsRevision(transaction, studyId), priorSettings)
    val features = StudySnapshotRepository(transaction).loadLockedStudyFeatures(studyId)
    val requestedStudy = StudyUpdate(settings = mutation(priorSettings))
    val lockedUpdate = LockedStudyUpdate(
        priorSettings,
        stampDataCollectionSettingsVersion(
            priorSettings, requestedStudy, priorFeatures = features, requestedFeatures = features,
        ),
    )
    ensureParticipantPolicyMutable(
        transaction,
        studyId,
        priorSettings,
        checkNotNull(lockedUpdate.stampedStudy.settings),
    )
    studyService.updateStudy(transaction, studyId, lockedUpdate.stampedStudy)
    return lockedUpdate.copy(settingsRevision = studyService.getStudySettingsRevision(transaction, studyId))
}

/** Applies a single-setting endpoint delta to the authoritative row-locked settings map. */
internal fun mergeStudySetting(
    priorSettings: StudySettings,
    settingType: StudySettingType,
    setting: StudySetting,
): StudySettings = StudySettings(priorSettings + (settingType to setting))


/** Keeps the signed manifest within the policy controls the Android app actually enforces. */
internal fun validateClientSupportedCollectionPolicy(setting: AndroidDataCollectionSetting) {
    // The Play and Amazon clients refuse to enroll (requireSupportedCollectionPolicies) when
    // upload diagnostics are not disclosed; the participant only sees a generic enrollment
    // failure. Reject the setting here, where the researcher can read the reason.
    require(setting.effectiveModules().getValue(CollectionModuleId.UPLOAD_TELEMETRY).enabled) {
        "upload_telemetry cannot be disabled: Play and Amazon builds refuse to enroll a study " +
            "that does not disclose upload diagnostics"
    }
    setting.modules.forEach { (moduleId, moduleSetting) ->
        require(!moduleSetting.required || moduleId.consentGated) {
            "${moduleId.id} cannot be required: this module has no per-module consent decision"
        }
        if (moduleId in CollectionCadenceModules.intervalGated) {
            require(moduleSetting.collectionCadence.jitterSeconds == 0L) {
                "${moduleId.id}.collectionCadence.jitterSeconds is not supported by the Android app"
            }
        } else {
            require(
                moduleSetting.collectionCadence ==
                    CollectionCadence.DEFAULT_COLLECTION,
            ) {
                "${moduleId.id}.collectionCadence is not supported by the Android app"
            }
        }
        require(
            moduleId == CollectionModuleId.HEALTH_CONNECT || moduleSetting.healthConnectRecordTypes.isEmpty(),
        ) {
            "healthConnectRecordTypes may only be configured for the health_connect module"
        }
    }

    val healthConnect = setting.modules[CollectionModuleId.HEALTH_CONNECT]
    require(healthConnect == null || !healthConnect.enabled || healthConnect.healthConnectRecordTypes.isNotEmpty()) {
        "An enabled health_connect module requires at least one healthConnectRecordType"
    }
}

/** Prevents changing the legal disclosure after a participant or device has entered the study. */
internal fun ensureParticipantPolicyMutable(
    connection: Connection,
    studyId: UUID,
    priorSettings: StudySettings?,
    requestedSettings: StudySettings,
) {
    rejectDataCollectionRemoval(priorSettings, requestedSettings)
    val policyChanged = StudySettingType.entries.any { type ->
        type.lockedAfterEnrollment && priorSettings?.get(type) != requestedSettings[type]
    }
    val collectionChanged = priorSettings?.get(StudySettingType.DataCollection) !=
        requestedSettings[StudySettingType.DataCollection]
    if (!policyChanged && !collectionChanged) return

    val activity = EnrollmentAccessCodeRepository(connection).lockActivity(studyId)
    if (activity.pendingParticipantIds.isNotEmpty()) throw EnrollmentDisclosurePendingException(activity.pendingParticipantIds)
    if (policyChanged && activity.hasActiveEnrollment) {
        throw ResponseStatusException(HttpStatus.CONFLICT, Messages.get("error.study.participantPolicyLocked"))
    }
}

private fun rejectDataCollectionRemoval(
    priorSettings: StudySettings?,
    requestedSettings: StudySettings,
) {
    val hadVersionedSettings = priorSettings?.get(StudySettingType.DataCollection) is AndroidDataCollectionSetting
    val retainsVersionedSettings = requestedSettings[StudySettingType.DataCollection] is AndroidDataCollectionSetting
    if (hadVersionedSettings && !retainsVersionedSettings) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.versionedSettingsRemoval"),
        )
    }
}




/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
@Service
public open class StudyService(
    private val storageResolver: StorageResolver,
    private val authorizationService: AuthorizationManager,
    private val candidateService: CandidateManager,
    private val enrollmentService: EnrollmentManager,
    private val surveysManager: SurveysManager,
    private val idGenerationService: HazelcastIdGenerationService,
    private val studyLimitsMgr: StudyLimitsManager,
    override val auditingManager: AuditingManager,
    hazelcast: HazelcastInstance,
    private val participantStatsCache: ParticipantStatsCache,
    private val webhookService: WebhookService,
) : StudyManager, AuditingComponent {
    private val studies = HazelcastMap.STUDIES.getMap(hazelcast)
    private val quarantineAwareStudies = QuarantineAwareStudyCache(studies, StudyDeletionGuard(storageResolver))

    @Inject
    @org.springframework.context.annotation.Lazy
    private lateinit var notificationService: NotificationService

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()

        private val STUDY_COLUMNS_LIST = listOf(
            STUDY_ID,
            TITLE,
            DESCRIPTION,
            LAT,
            LON,
            STUDY_GROUP,
            STUDY_VERSION,
            CONTACT,
            STORAGE,
            SETTINGS,
            MODULES,
            STUDY_PHONE_NUMBER
        )
        private val STUDY_COLUMNS = STUDY_COLUMNS_LIST.joinToString(",") { it.name }
        private val STUDY_COLUMNS_BIND = STUDY_COLUMNS_LIST.joinToString(",") {
            if (it.datatype == PostgresDatatype.JSONB) "?::jsonb" else "?"
        }

        private val UPDATE_STUDY_COLUMNS_LIST = listOf(
            TITLE,
            DESCRIPTION,
            UPDATED_AT,
            LAT,
            LON,
            STUDY_GROUP,
            STUDY_VERSION,
            CONTACT,
            STORAGE,
            SETTINGS,
            MODULES
        )

        private val UPDATE_STUDY_COLUMNS = UPDATE_STUDY_COLUMNS_LIST.joinToString(",") { it.name }
        private val COALESCED_STUDY_COLUMNS_BIND = UPDATE_STUDY_COLUMNS_LIST
            .joinToString(",") {
                "coalesce(?${if (it.datatype == PostgresDatatype.JSONB) "::jsonb" else ""}, stored.${it.name}) AS ${it.name}"
            }
        // The study content an update can change; updated_at only records that it did.
        private val COMPARED_STUDY_COLUMNS = UPDATE_STUDY_COLUMNS_LIST - UPDATED_AT

        private val ORG_STUDIES_COLS = listOf(
            ORGANIZATION_ID,
            STUDY_ID,
            USER_ID,
            SETTINGS
        ).joinToString(",") { it.name }

        /**
         * 1. study id
         * 2. title
         * 3. description
         * 4. lat
         * 5. lon
         * 6. study group
         * 7. study version
         * 8. contact
         * 9. storage
         * 10. settings
         * 11. modules
         * 12. phone number
         */
        internal val INSERT_STUDY_SQL = """
            INSERT INTO ${STUDIES.name} ($STUDY_COLUMNS) VALUES ($STUDY_COLUMNS_BIND)
        """.trimIndent()

        /**
         * 1. organization_id
         * 2. study_id
         * 3. user_id
         * 4. settings
         */
        private val INSERT_ORG_STUDIES_SQL =
            """
                INSERT INTO ${ORGANIZATION_STUDIES.name} (${ORG_STUDIES_COLS}) VALUES (?,?,?,?::jsonb)
            """.trimIndent()

        internal val GET_STUDIES_SQL = """
            SELECT * FROM ${STUDIES.name} 
            LEFT JOIN (
                SELECT ${STUDY_ID.name}, array_agg(${ORGANIZATION_ID.name}) as ${ORGANIZATION_IDS.name} 
                    FROM ${ORGANIZATION_STUDIES.name}
                    GROUP BY ${STUDY_ID.name}
                ) as org_studies 
            USING (${STUDY_ID.name}) 
            WHERE ${STUDY_ID.name} = ANY(?)
        """.trimIndent()

        internal val LOAD_STUDY_IDS = """
            SELECT ${STUDY_ID.name} FROM ${STUDIES.name} 
        """.trimIndent()

        private val DELETE_STUDIES_SQL = """
            DELETE FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ANY(?)
        """.trimIndent()

        private val REMOVE_STUDIES_FROM_ORGS_SQL = """
            DELETE FROM ${ORGANIZATION_STUDIES.name} WHERE ${STUDY_ID.name} = ANY(?)
        """.trimIndent()

        private val REMOVE_ALL_PARTICIPANTS_FROM_STUDIES_SQL = """
            DELETE FROM ${STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ANY(?)
        """.trimIndent()

        private val REMOVE_PARTICIPANTS_FROM_STUDY_SQL = """
            DELETE FROM ${STUDY_PARTICIPANTS.name}
            WHERE ${STUDY_ID.name} = ?
            AND ${PARTICIPANT_ID.name} = ANY(?)
        """.trimIndent()

        /**
         * 1. title,
         * 2. description,
         * 3. updated_at
         * 4. lat,
         * 5. lon,
         * 6. study_group,
         * 7. study_version,
         * 8. contact,
         * 9. storage
         * 10. settings
         * 11. modules
         * 12. study_id
         * 13. study_id
         * An update equal to the stored study matches no row, so it keeps updated_at and the revision.
         */

        private val UPDATE_STUDY_SQL = """
            UPDATE ${STUDIES.name}
            SET (${UPDATE_STUDY_COLUMNS}) = (${UPDATE_STUDY_COLUMNS_LIST.joinToString(",") { "requested.${it.name}" }})
            FROM (
                SELECT ${COALESCED_STUDY_COLUMNS_BIND} FROM ${STUDIES.name} stored WHERE stored.${STUDY_ID.name} = ?
            ) requested
            WHERE ${STUDIES.name}.${STUDY_ID.name} = ?
                AND (${COMPARED_STUDY_COLUMNS.joinToString(",") { "${STUDIES.name}.${it.name}" }})
                    IS DISTINCT FROM (${COMPARED_STUDY_COLUMNS.joinToString(",") { "requested.${it.name}" }})
        """.trimIndent()

        /**
         * get studies that belong to the provided organizationId where the
         * current user has read access
         * include list of all organizationIds each study is a part of.
         * sort by most recently created
         * 1. current user principal id
         * 2. organization id
         */
        // Erased studies are excluded before LIMIT/OFFSET so every page holds `limit` visible studies.
        internal val GET_ORG_STUDIES_SQL = """
            SELECT ${STUDIES.name}.*, org_studies.${ORGANIZATION_IDS.name}
            FROM ${STUDIES.name}
                INNER JOIN ${PERMISSIONS.name}
                ON ${STUDIES.name}.${STUDY_ID.name} = ${PERMISSIONS.name}.${ACL_KEY.name}[1]
                    AND ${PRINCIPAL_ID.name} = ?
                    AND '${READ.name}' = ANY(${PostgresColumns.PERMISSIONS.name})
                LEFT JOIN (
                    SELECT ${STUDY_ID.name}, array_agg(${ORGANIZATION_ID.name}) as ${ORGANIZATION_IDS.name} 
                        FROM ${ORGANIZATION_STUDIES.name}
                        GROUP BY ${STUDY_ID.name}
                    ) as org_studies
                USING (${STUDY_ID.name}) 
            WHERE ${STUDY_ID.name}
            IN (
                SELECT ${STUDY_ID.name} FROM ${ORGANIZATION_STUDIES.name}
                WHERE ${ORGANIZATION_ID.name} = ?
            )
            AND NOT ${StudyDeletionGuard.blockedStudySql("${STUDIES.name}.${STUDY_ID.name}")}
            ORDER BY ${CREATED_AT.name} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        /**
         * PreparedStatement bind order:
         * 1) StudyId
         */
        private val GET_STUDY_SETTINGS_SQL = """
            SELECT ${SETTINGS.name}
            FROM ${STUDIES.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        /**
         * PreparedStatement bind order:
         * 1) StudyIds
         */
        private val GET_STUDIES_SETTINGS_SQL = """
            SELECT ${STUDY_ID.name},${SETTINGS.name}
            FROM ${STUDIES.name}
            WHERE ${STUDY_ID.name} = ANY(?)
        """.trimIndent()

        internal val SELECT_STUDY_PARTICIPANTS_SQL = """
            SELECT * FROM $STUDY_PARTICIPANTS_WITH_WITHDRAWALS WHERE ${STUDY_ID.name} = ?
            ORDER BY ${PARTICIPANT_ID.name}
            LIMIT ? OFFSET ?
        """.trimIndent()

        private val SELECT_STUDY_ID_SQL = "SELECT ${STUDY_ID.name} FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ?"

        private val PARTICIPANT_STATS_COLUMNS = PARTICIPANT_STATS.columns.joinToString { it.name }

        /**
         * PreparedStatement bind order
         * 1) studyId
         */
        internal val GET_STUDY_PARTICIPANT_STATS = """
            SELECT $PARTICIPANT_STATS_COLUMNS
            FROM ${PARTICIPANT_STATS.name}
            WHERE ${STUDY_ID.name} = ?
            ORDER BY ${PARTICIPANT_ID.name}
            LIMIT ? OFFSET ?
        """.trimIndent()

        /**
         * PreparedStatement bind order
         * 1) participationStatus
         * 2) studyId
         * 3) particpantId
         */
        internal val SET_PARTICIPATION_STATUS_SQL = """
            UPDATE ${STUDY_PARTICIPANTS.name}
            SET ${PARTICIPATION_STATUS.name} = ?
            WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ?
        """.trimIndent()

        /** The record_capture_cutoff trigger advances status_revision on every status change, whoever writes it. */
        private val LOCK_PARTICIPATION_STATUS_SQL = """
            SELECT ${PARTICIPATION_STATUS.name}, status_revision FROM ${STUDY_PARTICIPANTS.name}
            WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ?
            FOR UPDATE
        """.trimIndent()

        private val PAUSE_ENROLLED_PARTICIPANTS_SQL = """
            UPDATE ${STUDY_PARTICIPANTS.name}
            SET ${PARTICIPATION_STATUS.name} = '${ParticipationStatus.PAUSED.name}'
            WHERE ${STUDY_ID.name} = ? AND ${PARTICIPATION_STATUS.name} = '${ParticipationStatus.ENROLLED.name}'
        """.trimIndent()

    }

    internal fun persistLockedStudySettingsMutation(
        studyId: UUID,
        expectedRevision: Long?,
        settingsAudit: StudySettingsAuditService,
        mutation: (StudySettings) -> StudySettings,
    ): LockedStudyUpdate = storageResolver.getPlatformStorage().connection.use { connection ->
        AuditedTransactionBuilder<LockedStudyUpdate>(connection, auditingManager)
            .transaction { transaction ->
                applyLockedSettingsMutation(transaction, studyId, expectedRevision, this, mutation).also { update ->
                    settingsAudit.recordSettingsDiff(studyId, checkNotNull(update.priorSettings),
                        checkNotNull(update.stampedStudy.settings), transaction)
                }
            }
            .audit { _ ->
                listOf(
                    AuditableEvent(
                        aclKey = AclKey(studyId),
                        eventType = AuditEventType.UPDATE_STUDY_SETTINGS,
                    ),
                )
            }
            .buildAndRun()
    }

    override fun createStudy(study: Study): UUID = createStudy(study) { _, _ -> }

    internal fun createStudy(study: Study, beforeCommit: (Connection, UUID) -> Unit): UUID {
        val storage = storageResolver.getDefaultPlatformStorage()
        val flavor = storage.flavor
        val baseStorage = storage.dataSource
        val hds = PinnedPlatformConnection.resolve(baseStorage)
        check(flavor == PostgresFlavor.VANILLA) { "Only vanilla postgres supported for studies." }
        val creator = Principals.getCurrentUser()
        val adminRole = Principals.getAdminRole()
        study.id = idGenerationService.getNextId()
        val aclKey = AclKey(study.id)
        hds.connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction {
                    createStudy(it, study)
                    beforeCommit(it, study.id)
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            aclKey,
                            eventType = AuditEventType.CREATE_STUDY,
                            description = "",
                            study = study.id,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = mapOf()
                        )
                    ) + study.organizationIds.map { organizationId ->
                        AuditableEvent(
                            aclKey,
                            eventType = AuditEventType.ASSOCIATE_STUDY,
                            description = "",
                            study = study.id,
                            organization = organizationId,
                            data = mapOf()
                        )
                    }
                }
                .buildAndRun()
        }

        // The database commit is the authoritative outcome. Cache warming must
        // not turn a durable success into a failed create response, because a
        // retry would create a second study. Specific-key reads remain
        // read-through and can recover from a missed warm.
        val warmCaches = {
        listOf(creator, adminRole).forEach { principal ->
            try {
                authorizationService.ensureAceIsLoaded(aclKey, principal)
            } catch (ex: Exception) {
                logger.error(
                    "Study {} committed, but the {} ACE cache warm failed.",
                    study.id,
                    principal.type,
                    ex,
                )
            }
        }
        }
        PinnedPlatformConnection.afterCommitOrNow(baseStorage, aclKey, warmCaches)
        return study.id
    }

    override fun createStudy(connection: Connection, study: Study) {
        RLSConnectionCustomizer.authorizeCreatedStudy(connection, study.id)
        insertStudy(connection, study)
        insertOrgStudy(connection, study)
        studyLimitsMgr.initializeStudyLimits(connection, study.id)
        surveysManager.initializeFilterdApps(connection, study.id)
        val aclKey = AclKey(study.id)
        authorizationService.createUnnamedSecurableObject(
            connection = connection,
            aclKey = aclKey,
            principal = Principals.getCurrentUser(),
            objectType = SecurableObjectType.Study
        )

        // Persist the admin ACE on the caller's transaction as well. Publishing
        // it through Hazelcast here would expose uncommitted ACL state and could
        // survive a rollback on the study transaction.
        authorizationService.createUnnamedSecurableObject(
            connection = connection,
            aclKey = aclKey,
            principal = Principals.getAdminRole(),
            permissions = EnumSet.allOf(Permission::class.java),
            objectType = SecurableObjectType.Study,
        )
    }

    private fun insertStudy(connection: Connection, study: Study): Int {
        return connection.prepareStatement(INSERT_STUDY_SQL).use { ps ->
            ps.setObject(1, study.id)
            ps.setObject(2, study.title)
            ps.setObject(3, study.description)
            ps.setObject(4, study.lat)
            ps.setObject(5, study.lon)
            ps.setObject(6, study.group)
            ps.setObject(7, study.version)
            ps.setObject(8, study.contact)
            ps.setObject(9, study.storage)
            ps.setString(10, mapper.writeValueAsString(study.settings))
            ps.setString(11, mapper.writeValueAsString(study.modules))
            ps.setString(12, study.phoneNumber)
            return ps.executeUpdate()
        }
    }

    private fun insertOrgStudy(connection: Connection, study: Study): Int {
        return connection.prepareStatement(INSERT_ORG_STUDIES_SQL).use { ps ->
            study.organizationIds.forEach { organizationId ->
                var params = 1
                ps.setObject(params++, organizationId)
                ps.setObject(params++, study.id)
                ps.setObject(params++, Principals.getCurrentUser().id)
                ps.setString(
                    params,
                    mapper.writeValueAsString(mapOf<String, Any>())
                ) //Per org settings for studies aren't really defined yet.
                ps.addBatch()
            }
            return ps.executeBatch().sum()
        }
    }

    override fun getStudy(studyId: UUID): Study {
        return quarantineAwareStudies.get(studyId)
            ?: throw NoSuchElementException("No study found for id $studyId")
    }

    override fun getStudies(studyIds: Collection<UUID>): Iterable<Study> {
        return quarantineAwareStudies.getAll(studyIds)
//        return BasePostgresIterable(
//            PreparedStatementHolderSupplier(storageResolver.getPlatformStorage(), GET_STUDIES_SQL, 256) { ps ->
//                val pgStudyIds = PostgresArrays.createUuidArray(ps.connection, studyIds)
//                ps.setArray(1, pgStudyIds)
//                ps.executeQuery()
//            }
//        ) { ResultSetAdapters.study(it) }
    }

    override fun getOrgStudies(organizationId: UUID, limit: Int, offset: Int): List<Study> =
        BasePostgresIterable(
            StreamingReadTransaction(storageResolver.getPlatformStorage(), GET_ORG_STUDIES_SQL, 256) { ps ->
                ps.setObject(1, Principals.getCurrentUser().id)
                ps.setObject(2, organizationId)
                ps.setInt(3, limit)
                ps.setInt(4, offset)
            }
        ) { ResultSetAdapters.study(it) }.toList()

    override fun updateStudy(connection: Connection, studyId: UUID, study: StudyUpdate) {
        connection.prepareStatement(UPDATE_STUDY_SQL).use { ps ->
            ps.setString(1, study.title)
            ps.setString(2, study.description)
            ps.setObject(3, OffsetDateTime.now()) //Set last updated field.
            ps.setObject(4, study.lat)
            ps.setObject(5, study.lon)
            ps.setString(6, study.group)
            ps.setString(7, study.version)
            ps.setString(8, study.contact)
            ps.setString(9, study.storage)
            ps.setObject(10, if (study.settings == null) null else mapper.writeValueAsString(study.settings))
            ps.setString(11, if (study.modules == null) null else mapper.writeValueAsString(study.modules))
            ps.setObject(12, studyId)
            ps.setObject(13, studyId)
            // Details and settings share one revision, so a stale dashboard edit cannot overwrite either.
            if (ps.executeUpdate() > 0) bumpStudySettingsRevision(connection, studyId)
        }
    }

    override fun bumpStudySettingsRevision(connection: Connection, studyId: UUID): Long =
        StudySnapshotRepository(connection).bumpSettingsRevision(studyId)

    override fun getStudySettingsRevision(connection: Connection, studyId: UUID): Long =
        StudySnapshotRepository(connection).settingsRevision(studyId)
    override fun getStudyPhoneNumber(studyId: UUID): String? {
        val realStudyId = requireStudyId(studyId)
        return getStudy(realStudyId).phoneNumber
    }

    /** Job runners already own a connection; bypass cold Hazelcast mapstore borrowing. */
    internal fun getStudyPhoneNumber(connection: Connection, studyId: UUID): String? =
        PinnedPlatformConnection.pinning(storageResolver.getPlatformStorage(), connection) {
            val resolvedStudyId = requireStudyId(studyId)
            connection.prepareStatement("SELECT ${STUDY_PHONE_NUMBER.name} FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ?")
                .use { statement ->
                    statement.setObject(1, resolvedStudyId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) throw StudyNotFoundException(studyId)
                        rows.getString(1)
                    }
                }
        }

    override fun pauseEnrolledParticipants(studyId: UUID): Int =
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Int>(connection, auditingManager)
                .transaction { conn ->
                    DeletionStudyFence.shared(conn, studyId)
                    loadLockedStudySettings(conn, studyId)
                    if (!studyHasExpired(conn, studyId)) return@transaction 0
                    conn.prepareStatement(PAUSE_ENROLLED_PARTICIPANTS_SQL).use { statement ->
                        statement.setObject(1, studyId)
                        statement.executeUpdate()
                    }
                }
                .audit { count ->
                    if (count == 0) emptyList() else listOf(
                        AuditableEvent(
                            aclKey = AclKey(studyId),
                            securablePrincipalId = IdConstants.CHRONICLE.id,
                            principal = SystemUser.CHRONICLE.principal,
                            eventType = AuditEventType.UPDATE_PARTICIPATION_STATUS,
                            description = "Paused $count enrolled participants after study duration elapsed",
                            study = studyId,
                            data = mapOf("affectedParticipants" to count),
                        ),
                    )
                }
                .buildAndRun()
        }

    /**
     * A researcher's status change applies only to the status revision their dashboard showed; a stale tab or a
     * retried request gets 412 instead of undoing a later change. Re-sending the status already in force is a no-op.
     */
    override fun updateParticipationStatus(
        studyId: UUID,
        participantId: String,
        participationStatus: ParticipationStatus,
        expectedRevision: Long,
    ): Boolean = storageResolver.getPlatformStorage().connection.use { connection ->
        AuditedTransactionBuilder<AuditableEvent?>(connection, auditingManager)
            .transaction { conn ->
                DeletionStudyFence.shared(conn, studyId)
                val (current, revision) = conn.prepareStatement(LOCK_PARTICIPATION_STATUS_SQL).use { ps ->
                    ps.setObject(1, studyId)
                    ps.setString(2, participantId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.participant.unavailable"))
                        }
                        rs.getString(1) to rs.getLong(2)
                    }
                }
                when {
                    // Already in force: an identical replay or a second tab; nothing is written or audited.
                    current == participationStatus.name -> null
                    revision != expectedRevision -> throw ResponseStatusException(
                        HttpStatus.PRECONDITION_FAILED,
                        Messages.get("error.participant.statusChanged"),
                    )
                    else -> updateParticipationStatus(conn, studyId, participantId, participationStatus, current).let { event ->
                        // Mark not enrolled is the researcher's withdrawal; every other change is a status update.
                        if (participationStatus == ParticipationStatus.NOT_ENROLLED) {
                            event.copy(eventType = AuditEventType.PARTICIPANT_WITHDRAW)
                        } else event
                    }
                }
            }
            .audit { listOfNotNull(it) }
            .buildAndRun() != null
    }

    /** Unconditional: only for callers that own the participant outright (deletion). Uses the caller's transaction. */
    internal fun updateParticipationStatus(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        participationStatus: ParticipationStatus,
        previousStatus: String? = null,
    ): AuditableEvent {
        logger.info(
            "Updating participation status for study {} and {}",
            studyId,
            LogSanitizer.stableFingerprint(participantId, "participant"),
        )
        DeletionStudyFence.shared(connection, studyId)
        connection.prepareStatement(SET_PARTICIPATION_STATUS_SQL).use { ps ->
            ps.setString(1, participationStatus.name)
            ps.setObject(2, studyId)
            ps.setString(3, participantId)
            ps.executeUpdate()
        }
        return AuditableEvent(
            aclKey = AclKey(studyId),
            eventType = AuditEventType.UPDATE_PARTICIPATION_STATUS,
            description =
                "Set participation status of participant ${AuditService.participantReference(participantId)} " +
                    "in study $studyId to $participationStatus",
            study = studyId,
            data = listOfNotNull(previousStatus?.let { "oldStatus" to it }, "newStatus" to participationStatus.name).toMap(),
        )
    }

    override fun getParticipantAnnotations(studyId: UUID, participantId: String): String? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            StudySnapshotRepository(connection).loadLockedParticipantAnnotations(studyId, participantId)
        }

    override fun updateParticipantAnnotations(
        studyId: UUID,
        participantId: String,
        annotations: ParticipantAnnotationsUpdate,
    ) {
        logger.info(
            "Updating participant annotations for study {} and {}",
            studyId,
            LogSanitizer.stableFingerprint(participantId, "participant"),
        )

        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    StudySnapshotRepository(conn).updateParticipantAnnotations(studyId, participantId, annotations)
                }.audit {
                    listOf(
                        AuditableEvent(
                            aclKey = AclKey(studyId),
                            eventType = AuditEventType.UPDATE_PARTICIPANT_ANNOTATIONS,
                            description =
                                "Updated annotations for participant ${AuditService.participantReference(participantId)} in study $studyId"
                        )
                    )
                }.buildAndRun()
        }
    }

    override fun registerParticipant(studyId: UUID, participant: Participant): UUID {
        val candidateId = storageResolver.getPlatformStorage().connection.use { conn ->
            AuditedTransactionBuilder<UUID>(conn, auditingManager)
                .transaction { connection -> registerParticipant(connection, studyId, participant) }
                .audit { candidateId ->
                    listOf(
                        AuditableEvent(
                            AclKey(candidateId),
                            eventType = AuditEventType.REGISTER_CANDIDATE,
                            description = "Registering participant with $candidateId for study $studyId."
                        )
                    )
                }
                .buildAndRun()
        }
        authorizationService.ensureAceIsLoaded(AclKey(candidateId), Principals.getCurrentUser())
        return candidateId
    }

    override fun registerParticipant(connection: Connection, studyId: UUID, participant: Participant): UUID {
        validateParticipantIdNotPii(participant.participantId)
        // Enrollment codes, form links and uploads all require this format; reject it here, not after registration.
        validateParticipantId(participant.participantId)
        val candidateId = candidateService.registerCandidate(connection, participant.candidate)
        enrollmentService.registerParticipant(
            connection,
            studyId,
            participant.participantId,
            candidateId,
            participant.participationStatus
        )
        val deliveryTypes = EnumSet.noneOf(DeliveryType::class.java)

        try {
            val studySettings =
                getStudy(studyId).settings.getValue(StudySettingType.Notifications) as StudyNotificationSettings

            if (studySettings.notifyOnEnrollment) {
                notificationService.sendNotifications(
                    connection,
                    studyId,
                    listOf(
                        ParticipantNotification(
                            participant.participantId,
                            NotificationType.ENROLLMENT,
                            deliveryTypes,
                            message = studySettings.getEnrollmentMessage()
                        )
                    )
                )
            }
        } catch (ex: NoSuchElementException) {
            //Notification settings not configured for this study — safe to continue enrollment.
            logger.error(
                "Unable to send enrollment notification for studyId={}, participantRef={}",
                studyId,
                LogSanitizer.stableFingerprint(participant.participantId, "participant"),
                ex,
            )
        } catch (ex: IllegalStateException) {
            //Notification precondition not met (e.g., missing phone/email) — safe to continue enrollment.
            logger.error(
                "Unable to send enrollment notification for studyId={}, participantRef={}",
                studyId,
                LogSanitizer.stableFingerprint(participant.participantId, "participant"),
                ex,
            )
        } catch (ex: IllegalArgumentException) {
            //Invalid notification parameters — safe to continue enrollment.
            logger.error(
                "Unable to send enrollment notification for studyId={}, participantRef={}",
                studyId,
                LogSanitizer.stableFingerprint(participant.participantId, "participant"),
                ex,
            )
        }
        if (participant.participationStatus == ParticipationStatus.ENROLLED) {
            webhookService.enqueueEvent(
                connection,
                studyId,
                WebhookEventType.PARTICIPANT_ENROLLED,
                WebhookPayload.ParticipantEnrolled(
                    ParticipantRef.fromParticipantId(participant.participantId), participant.participationStatus.name,
                ),
            )
        }
        return candidateId
    }

    override fun refreshStudyCache(studyIds: Set<UUID>) {
        // Evict stale entries — the next read will trigger a MapStore load on-demand.
        // Using loadAll() here caused a 2-second blocking sleep in
        // Hazelcast's MapProxySupport.waitUntilLoaded() which polls with Thread.sleep().
        studyIds.forEach { studies.evict(it) }
    }

    override fun deleteStudies(connection: Connection, studyIds: Collection<UUID>): Int {
        return connection.prepareStatement(DELETE_STUDIES_SQL).use { ps ->
            val pgStudyIds = PostgresArrays.createUuidArray(ps.connection, studyIds)
            ps.setArray(1, pgStudyIds)
            return ps.executeUpdate()
        }
    }

    override fun removeStudiesFromOrganizations(connection: Connection, studyIds: Collection<UUID>): Int {
        return connection.prepareStatement(REMOVE_STUDIES_FROM_ORGS_SQL).use { ps ->
            val pgStudyIds = PostgresArrays.createUuidArray(ps.connection, studyIds)
            ps.setArray(1, pgStudyIds)
            return ps.executeUpdate()
        }
    }

    override fun removeAllParticipantsFromStudies(connection: Connection, studyIds: Collection<UUID>): Int {
        return connection.prepareStatement(REMOVE_ALL_PARTICIPANTS_FROM_STUDIES_SQL).use { ps ->
            val pgStudyIds = PostgresArrays.createUuidArray(ps.connection, studyIds)
            ps.setArray(1, pgStudyIds)
            return ps.executeUpdate()
        }
    }

    override fun removeParticipantsFromStudy(
        connection: Connection,
        studyId: UUID,
        participantIds: Collection<String>,
    ): Int {
        return connection.prepareStatement(REMOVE_PARTICIPANTS_FROM_STUDY_SQL).use { ps ->
            ps.setObject(1, studyId)
            val pgParticipantIds = PostgresArrays.createTextArray(ps.connection, participantIds)
            ps.setObject(2, pgParticipantIds)
            return ps.executeUpdate()
        }
    }

    override fun getStudySettings(studyId: UUID): Map<StudySettingType, StudySetting> {
        val settings: Map<StudySettingType, StudySetting> =
            storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_STUDY_SETTINGS_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        mapper.readValue<Map<StudySettingType, StudySetting>>(rs.getString(SETTINGS.name))
                    }
                    else mapOf()
                }
            }
        }
        requireStudyVisible(studyId)
        return settings
    }

    override fun getStudySettings(studyIds: Collection<UUID>): Map<UUID, Map<StudySettingType, StudySetting>> {
        val settings = BasePostgresIterable(StreamingReadTransaction(
            storageResolver.getPlatformStorage(),
            GET_STUDIES_SETTINGS_SQL,
            1024
        ) { ps -> ps.setArray(1, PostgresArrays.createUuidArray(ps.connection, studyIds)) }) { rs ->
            rs.getObject(
                STUDY_ID.name,
                UUID::class.java
            ) to mapper.readValue<Map<StudySettingType, StudySetting>>(rs.getString(SETTINGS.name))
        }.toMap()
        val visibleStudyIds = quarantineAwareStudies.filterVisibleStudyIds(settings.keys).toSet()
        return settings.filterKeys { it in visibleStudyIds }
    }

    override fun getStudyParticipantStats(studyId: UUID, limit: Int, offset: Int): Map<String, ParticipantStats> {
        val hds = storageResolver.getPlatformStorage()
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, GET_STUDY_PARTICIPANT_STATS) { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
            }
        ) { ResultSetAdapters.participantStats(it) }
            .associateBy { it.participantId }
    }

    override fun getParticipantStats(studyId: UUID, participantId: String): ParticipantStats? {
        return participantStatsCache.get(studyId, participantId)
//        val hds = storageResolver.getPlatformStorage()
//        return try {
//            BasePostgresIterable(
//                PreparedStatementHolderSupplier(
//                    hds, GET_PARTICIPANT_STATS
//                ) { ps ->
//                    ps.setObject(1, studyId)
//                    ps.setString(2, participantId)
//                }
//            ) { ResultSetAdapters.participantStats(it) }.first()
//        } catch (ex: Exception) {
//            return null
//        }
    }

    override fun insertOrUpdateParticipantStats(stats: ParticipantStats) {
        participantStatsCache.merge(stats)
    }

    override fun getStudyParticipants(studyId: UUID, limit: Int, offset: Int): Iterable<Participant> {
        return selectStudyParticipants(studyId, limit, offset)
    }

    override fun countStudyParticipants(studyId: UUID): Long {
        return studyLimitsMgr.countStudyParticipants(studyId)
    }

    override fun countStudyParticipants(connection: Connection, studyIds: Set<UUID>): Map<UUID, Long> {
        return studyLimitsMgr.countStudyParticipants(connection, studyIds)
    }

    override fun countStudyParticipants(studyIds: Set<UUID>): Map<UUID, Long> {
        return studyLimitsMgr.countStudyParticipants(studyIds)
    }

    private fun selectStudyParticipants(studyId: UUID, limit: Int, offset: Int): Iterable<Participant> {
        val storage = storageResolver.getDefaultPlatformStorage()
        val flavor = storage.flavor
        val hds = storage.dataSource
        ensureVanilla(flavor)
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, SELECT_STUDY_PARTICIPANTS_SQL) { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
            }
        ) { ResultSetAdapters.participant(it) }
    }

    override fun getStudyId(studyId: UUID): UUID? {
        val resolvedStudyId = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(SELECT_STUDY_ID_SQL).use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { rows -> if (rows.next()) ResultSetAdapters.studyId(rows) else null }
            }
        }
        return resolvedStudyId?.takeIf(quarantineAwareStudies::isVisible)
    }

    override fun getAllStudyIds(): Iterable<UUID> {
        val hds = storageResolver.getDefaultPlatformStorage().dataSource
        val studyIds = BasePostgresIterable(
            StreamingReadTransaction(
                hds,
                LOAD_STUDY_IDS,
                fetchSize = 65536
            )
        ) { ResultSetAdapters.studyId(it) }.toList()
        return quarantineAwareStudies.filterVisibleStudyIds(studyIds)
    }

    override fun isValidStudy(studyId: UUID): Boolean {
        return getStudyId(studyId) != null
    }

    private fun requireStudyVisible(studyId: UUID) {
        if (!quarantineAwareStudies.isVisible(studyId)) {
            throw NoSuchElementException("No study found for id $studyId")
        }
    }
}

internal fun StudyService.requireStudyId(studyId: UUID): UUID =
    getStudyId(studyId) ?: throw StudyNotFoundException(studyId)
