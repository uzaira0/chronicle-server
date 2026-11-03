package com.openlattice.chronicle.services.studies

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.query.Predicates
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.sources.SourceDeviceType
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.DEVICES
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyComplianceManager
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.ViolationReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@getmethodic.com&gt;
 */
@Service
public open class StudyComplianceService(
    private val storageResolver: StorageResolver,
    override val auditingManager: AuditingManager,
    hazelcast: HazelcastInstance,
) : StudyComplianceManager, AuditingComponent {
    private val studies = HazelcastMap.STUDIES.getMap(hazelcast)
    private val quarantineAwareStudies = QuarantineAwareStudyCache(studies, StudyDeletionGuard(storageResolver))

    public companion object {
        private val logger = LoggerFactory.getLogger(StudyComplianceService::class.java)


        private val ACTIVE_PARTICIPANTS = """
            SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, array_agg(distinct coalesce(${DEVICE_TYPE.name},'${SourceDeviceType.None}')) as ${DEVICE_TYPE.name}
            FROM (
                SELECT * FROM ${ChroniclePostgresTables.STUDY_PARTICIPANTS.name}
                WHERE ${PostgresColumns.PARTICIPATION_STATUS.name} = '${ParticipationStatus.ENROLLED}'
                  AND ${STUDY_ID.name} = ANY(?)
                ORDER BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name} COLLATE "C" LIMIT ? OFFSET ?
            ) ${ChroniclePostgresTables.STUDY_PARTICIPANTS.name}
            LEFT JOIN ${DEVICES.name} USING(${STUDY_ID.name}, ${PARTICIPANT_ID.name})
            GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
        """.trimIndent()

        /** A storage stage holding research rows, and the predicate selecting the accepted ones. */
        private data class ResearchStore(val table: String, val acceptedRows: String = "true")

        /**
         * Every storage stage whose rows show that a phone's research data reached the server. Each records the
         * server's receipt in `uploaded_at`. An accepted Android usage or sensor upload waits in the buffer until
         * the mover materializes it; rows the sensor mover quarantined carry another type and do not count.
         * Availability reports and diagnostics are not research data.
         */
        private val RESEARCH_STORES: Map<SourceDeviceType, List<ResearchStore>> = mapOf(
            SourceDeviceType.Android to listOf(
                PostgresEventTables.CHRONICLE_USAGE_EVENTS.name,
                ChroniclePostgresTables.ANDROID_SENSOR_DATA.name,
            ).plus(DataDownloadService.COLLECTION_EXPORTS
                .filterKeys { it != ParticipantDataType.SensorAvailability && it != ParticipantDataType.IOSScreenTime }
                .values.map { it.table.name })
                .map { ResearchStore(it) } + ResearchStore(ChroniclePostgresTables.UPLOAD_BUFFER.name,
                "${PostgresColumns.UPLOAD_TYPE.name} IN (${UploadType.entries
                    .filter { it.countsAsResearchReceipt }.joinToString { "'${it.name}'" }})"),
            SourceDeviceType.Ios to listOf(ResearchStore(ChroniclePostgresTables.IOS_SCREEN_TIME_USAGE.name)),
        )

        /**
         * The one rule for upload compliance on every platform: the participants with a research row the
         * server received inside their study's no-data window. Receipt, not capture: a phone that was
         * offline and drains old captures today is compliant. Ingestion already enforces module consent.
         */
        internal fun recentResearchUploadsSql(
            platform: SourceDeviceType,
            settings: Map<UUID, Map<StudySettingType, StudySetting>>,
        ): String = RESEARCH_STORES.getValue(platform).joinToString(" UNION ") { store ->
            val windows = settings.keys.joinToString(" OR ") { studyId ->
                val window = getDurationPolicy(studyId, settings)
                "(${PostgresEventColumns.STUDY_ID.name} = '$studyId' AND ${PostgresColumns.UPLOADED_AT.name} >= now() - " +
                    "'${window.years} years ${window.months} months ${window.days} days'::interval)"
            }
            """
            SELECT DISTINCT ${PostgresEventColumns.STUDY_ID.name}::text AS ${PostgresEventColumns.STUDY_ID.name}, ${PostgresEventColumns.PARTICIPANT_ID.name}
            FROM ${store.table}
            WHERE ${store.acceptedRows} AND ($windows)
            """.trimIndent()
        }

        public fun getDurationPolicy(
            studyId: UUID,
            enabledStudiesSettings: Map<UUID, Map<StudySettingType, StudySetting>>,
        ): StudyDuration {
            return (enabledStudiesSettings
                .getValue(studyId)
                .getValue(StudySettingType.Notifications) as StudyNotificationSettings).noDataUploaded
        }
    }

    override fun getStudyCompliancePage(studyId: UUID, limit: Int, offset: Int): com.openlattice.chronicle.study.StudyCompliancePage {
        val safeLimit = com.openlattice.chronicle.util.PaginationDefaults.clampLimit(limit)
        val safeOffset = com.openlattice.chronicle.util.PaginationDefaults.clampOffset(offset)
        val settings = getVisibleStudySettings(listOf(studyId))
        if (settings.isEmpty()) return com.openlattice.chronicle.study.StudyCompliancePage(emptyMap(), null)
        val candidates = getActiveStudyParticipants(settings.keys, safeLimit + 1, safeOffset)
        val participantIds = candidates.values.flatMap { it[studyId].orEmpty() }.toSortedSet()
        val selected = participantIds.take(safeLimit).toSet()
        val page = candidates.mapValues { (_, studies) -> studies.mapValues { (_, ids) -> ids.intersect(selected) } }
        return com.openlattice.chronicle.study.StudyCompliancePage(
            complianceViolations(settings, page),
            if (participantIds.size > safeLimit) safeOffset.toLong() + safeLimit else null,
        )
    }

    override fun getNonCompliantStudies(studies: Collection<UUID>): Map<UUID, Map<String, List<ComplianceViolation>>> {
        return if (studies.isEmpty()) mapOf() else getNonCompliantParticipantsByStudy(studies)

    }

    override fun getAllNonCompliantStudies(): Map<UUID, Map<String, List<ComplianceViolation>>> {
        return getNonCompliantParticipantsByStudy(emptyList())
    }

    private fun getNonCompliantParticipantsByStudy(studies: Collection<UUID>): Map<UUID, Map<String, List<ComplianceViolation>>> {
        logger.info("Getting non-compliant participants for the following studies: $studies")
        //Studies being empty in this function means that it will return all studies.
        val enabledStudiesSettings = getVisibleStudySettings(studies)
        if (enabledStudiesSettings.isEmpty()) {
            logger.info("No partcipants without data uploads in the specified timeframes.")
            return mapOf()
        }

        return complianceViolations(enabledStudiesSettings, getActiveStudyParticipants(enabledStudiesSettings.keys))
    }

    private fun complianceViolations(
        enabledStudiesSettings: Map<UUID, Map<StudySettingType, StudySetting>>,
        activeParticipants: Map<SourceDeviceType, Map<UUID, Set<String>>>,
    ): Map<UUID, Map<String, List<ComplianceViolation>>> = activeParticipants.flatMap { (sourceDeviceType, participants) ->
            when (sourceDeviceType) {
                SourceDeviceType.Android, SourceDeviceType.Ios -> getStudyParticipantsWithoutRecentUploads(
                    recentResearchUploadsSql(sourceDeviceType, enabledStudiesSettings),
                    enabledStudiesSettings,
                    participants,
                    sourceDeviceType
                ).asSequence()

                SourceDeviceType.None -> getStudyParticipantsWithoutEnrolledDevices(
                    enabledStudiesSettings,
                    participants
                ).asSequence()
            }
        }
            .groupBy({ it.key }, { it.value })
            .mapValues { participantViolations ->
                participantViolations.value.flatMap { it.entries }.groupBy({ it.key }, { it.value })
            }

    private fun getStudyParticipantsWithoutEnrolledDevices(
        enabledStudiesSettings: Map<UUID, Map<StudySettingType, StudySetting>>,
        activeParticipants: Map<UUID, Set<String>>,
    ): Map<UUID, Map<String, ComplianceViolation>> {

        return activeParticipants.mapValues { (studyId, participants) ->
            val violation = ComplianceViolation(
                ViolationReason.NOT_ENROLLED,
                buildDescriptionNotEnrolled(studyId, getDurationPolicy(studyId, enabledStudiesSettings))
            )
            participants.associateWith { violation }
        }
    }

    private fun getStudyParticipantsWithoutRecentUploads(
        sql: String,
        enabledStudiesSettings: Map<UUID, Map<StudySettingType, StudySetting>>,
        activeParticipants: Map<UUID, Set<String>>,
        sourceDeviceType: SourceDeviceType,
    ): Map<UUID, Map<String, ComplianceViolation>> {
        val studyParticipants = mutableMapOf<UUID, MutableSet<String>>()
        val participants = activeParticipants.values.flatten().toSet()
        if (participants.isEmpty()) return emptyMap()
        // Restrict receipt materialization to the source participant page before collecting rows.
        BasePostgresIterable(
            PreparedStatementHolderSupplier(
                storageResolver.getEventStorageWithFlavor(),
                "SELECT * FROM ($sql) receipts WHERE participant_id = ANY(?)",
            ) { statement -> statement.setArray(1, PostgresArrays.createTextArray(statement.connection, participants)) }
        ) { rs ->
            UUID.fromString(rs.getString(PostgresEventColumns.STUDY_ID.name)) to rs.getString(PostgresEventColumns.PARTICIPANT_ID.name)
        }
            .forEach { (studyId, participantId) ->
                studyParticipants.getOrPut(studyId) { mutableSetOf() }.add(participantId)
            }

        //Take all active participants and remove any who have recently uploaded data.
        return activeParticipants.mapValues { (studyId, participantIds) ->
            val violation = ComplianceViolation(
                ViolationReason.NO_RECENT_DATA_UPLOADED,
                buildDescriptionNoUploads(studyId, getDurationPolicy(studyId, enabledStudiesSettings), sourceDeviceType)
            )

            (participantIds - (studyParticipants[studyId] ?: emptySet()).toSet())
                .associateWith { violation }

        }
    }

    private fun getActiveStudyParticipants(
        studyFilter: Set<UUID>, limit: Int = Int.MAX_VALUE, offset: Int = 0,
    ): Map<SourceDeviceType, Map<UUID, Set<String>>> {
        val studyParticipants = mutableMapOf<SourceDeviceType, MutableMap<UUID, MutableSet<String>>>()
        BasePostgresIterable(
            StreamingReadTransaction(
                storageResolver.getPlatformStorage(),
                ACTIVE_PARTICIPANTS,
                1024
            ) { ps ->
                ps.setArray(1, PostgresArrays.createUuidArray(ps.connection, studyFilter))
                ps.setInt(2, limit)
                ps.setInt(3, offset)
            }
        ) { rs ->
            Triple(
                ResultSetAdapters.deviceTypes(rs),
                rs.getObject(
                    STUDY_ID.name,
                    UUID::class.java
                ),
                rs.getString(PARTICIPANT_ID.name)
            )
        }
            .forEach { (sourceDeviceTypes, studyId, participantId) ->
                sourceDeviceTypes.forEach { sourceDeviceType ->
                    studyParticipants
                        .getOrPut(sourceDeviceType) { mutableMapOf() }
                        .getOrPut(studyId) { mutableSetOf() }.add(participantId)
                }
            }
        return studyParticipants
    }

    private fun buildDescriptionNotEnrolled(
        studyId: UUID,
        studyDuration: StudyDuration
    ): String {
        return """
            Study policy for $studyId requires enrollment within the last ${studyDuration.years} years ${studyDuration.months} months ${studyDuration.days} days.
        """.trimIndent()
    }

    private fun buildDescriptionNoUploads(
        studyId: UUID,
        studyDuration: StudyDuration,
        sourceDeviceType: SourceDeviceType
    ): String {
        return """
            Study policy for $studyId requires ${sourceDeviceType.name} research data received within the last ${studyDuration.years} years ${studyDuration.months} months ${studyDuration.days} days.
        """.trimIndent()
    }

    // reason: vararg Hazelcast Predicates.in API requires the spread; studyId arrays are small
    @Suppress("SpreadOperator")
    private fun getVisibleStudySettings(studyIds: Collection<UUID>): Map<UUID, Map<StudySettingType, StudySetting>> {
        val candidates = if (studyIds.isEmpty()) studies.values else {
            studies.values(Predicates.`in`<UUID, Study>("__key", *studyIds.toTypedArray()))
        }
        return quarantineAwareStudies.filterVisible(candidates).associateBy({ it.id }, {
            it.settings + (StudySettingType.Notifications to
                (it.settings[StudySettingType.Notifications] ?: StudyNotificationSettings("", it.title)))
        })
    }


}
