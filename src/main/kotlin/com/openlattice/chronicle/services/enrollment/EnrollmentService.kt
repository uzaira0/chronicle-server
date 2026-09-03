package com.openlattice.chronicle.services.enrollment

import com.geekbeast.controllers.exceptions.ResourceNotFoundException
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.sources.SourceDeviceType
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.DEVICES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATION_STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_PARTICIPANTS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CANDIDATE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_TOKEN
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPATION_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.observability.ChronicleMetrics
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.SQLException
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Suppress("DEPRECATION")
@Service
public open class EnrollmentService(
    private val storageResolver: StorageResolver,
    // reason: DI-injected dependency retained as part of the service's Spring bean wiring
    @Suppress("UnusedPrivateProperty")
    private val idGenerationService: HazelcastIdGenerationService,
    private val candidateManager: CandidateManager,
) : EnrollmentManager {

    public companion object {
        private val logger = LoggerFactory.getLogger(EnrollmentService::class.java)
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private val DEVICES_COLS = DEVICES.columns.joinToString(",") { it.name }
        private const val DISABLED_PUSH_DEVICE_TOKEN = ""
        // Jackson MixIns that exclude device-identifying fields from storage serialization.
        // Maintained here (not in shared GPL models) since the Android app needs these fields.
        public abstract class AndroidDeviceStorageMixIn {
            @get:JsonIgnore public abstract val deviceId: String
            @get:JsonIgnore public abstract val fcmRegistrationToken: String
        }

        public abstract class IOSDeviceStorageMixIn {
            @get:JsonIgnore public abstract val deviceId: String
            @get:JsonIgnore public abstract val apnDeviceToken: String
        }

        private val storageMapper: ObjectMapper = ObjectMappers.newJsonMapper().apply {
            addMixIn(AndroidDevice::class.java, AndroidDeviceStorageMixIn::class.java)
            addMixIn(IOSDevice::class.java, IOSDeviceStorageMixIn::class.java)
        }

        public fun serializeForStorage(sourceDevice: SourceDevice): String {
            return storageMapper.writeValueAsString(sourceDevice)
        }

        /**
         * 1. study id
         * 2. device id
         * 3. participant id
         * 4. device type
         * 5. source device (jsonb)
         * 6. deprecated push device token; intentionally stored blank because this deployment
         *    does not use Firebase/APN push identifiers.
         */
        private val INSERT_DEVICE = """
            INSERT INTO ${DEVICES.name} ($DEVICES_COLS) VALUES (?,?,?,?,?::jsonb,?)
            ON CONFLICT (${STUDY_ID.name}, ${DEVICE_ID.name}) DO UPDATE SET ${DEVICE_TOKEN.name} = EXCLUDED.${DEVICE_TOKEN.name}
            RETURNING ${DEVICE_ID.name}
        """

        /**
         * 1. study id
         * 2. participant id
         * 3. device id
         */
        private val COUNT_DEVICE_ID = """
            SELECT count(*) FROM ${DEVICES.name}
                WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ? AND ${DEVICE_ID.name} = ?
        """.trimIndent()

        private val COUNT_STUDY_PARTICIPANTS = """
            SELECT count(*) FROM ${STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         * 2. participant id
         * 3. candidate id
         * 4. participation status
         */
        private val INSERT_PARTICIPANT = """
            INSERT INTO ${STUDY_PARTICIPANTS.name} (${STUDY_ID.name},${PARTICIPANT_ID.name},${CANDIDATE_ID.name},${PARTICIPATION_STATUS.name}) VALUES (?,?,?,?)
        """.trimIndent()

        /**
         * 1. study id
         * 2. participant id
         */
        private val GET_PARTICIPANT = """
            SELECT * FROM ${STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         * 2. participant id
         */
        private val GET_PARTICIPATION_STATUS = """
            SELECT ${PARTICIPATION_STATUS.name} FROM ${STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         */
        private val GET_STUDY_PARTICIPANT_IDS = """
            SELECT ${PARTICIPANT_ID.name} FROM ${STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         */
        private val GET_STUDY_PARTICIPANTS = """
            SELECT * FROM ${STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         */
        private val COUNT_STUDY = """
            SELECT count(*) FROM ${STUDIES.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         */
        private val GET_ORGANIZATION_ID_FOR_STUDY = """
            SELECT ${ORGANIZATION_ID.name} FROM ${ORGANIZATION_STUDIES.name} WHERE ${STUDY_ID.name} = ? LIMIT 1
        """.trimIndent()

    }

    override fun registerDevice(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        sourceDevice: SourceDevice,
    ): UUID {
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        logger.info(
            "attempting to register data source - studyId = {}, participantRef = {}, dataSourceId = {}",
            studyId,
            participantRef,
            deviceId
        )

        if (!isKnownParticipant(studyId, participantId)) {
            logger.error(
                "unknown participant, unable to register datasource - studyId = {}, participantRef = {}, dataSourceId = {}",
                studyId,
                participantRef,
                deviceId
            )
            throw AccessDeniedException("unknown participant, unable to register datasource")
        }


        val resolvedDeviceId = when (sourceDevice) {
            is AndroidDevice -> registerDeviceOrGetId(
                studyId,
                participantId,
                SourceDeviceType.Android,
                deviceId,
                sourceDevice,
            )

            is IOSDevice -> registerDeviceOrGetId(
                studyId,
                participantId,
                SourceDeviceType.Ios,
                deviceId,
                sourceDevice,
            )

            else -> throw UnsupportedOperationException("${sourceDevice.javaClass.name} is not a supported datasource.")
        }

        // Shared self-host monitoring needs the aggregate rate, never a study identifier.
        ChronicleMetrics.enrollmentTotal.inc()

        return resolvedDeviceId
    }

    private fun registerDeviceOrGetId(
        studyId: UUID,
        participantId: String,
        deviceType: SourceDeviceType,
        deviceId: UUID,
        sourceDevice: SourceDevice,
    ): UUID {
        val hds = storageResolver.getPlatformStorage()
        val strippedDeviceJson = serializeForStorage(sourceDevice)
        return hds.connection.use { connection ->
            connection.prepareStatement(INSERT_DEVICE).use { ps ->
                ps.setObject(1, studyId)
                ps.setObject(2, deviceId)
                ps.setString(3, participantId)
                ps.setString(4, deviceType.name)
                ps.setString(5, strippedDeviceJson)
                ps.setString(6, DISABLED_PUSH_DEVICE_TOKEN)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT RETURNING produced no result" }
                    rs.getObject(DEVICE_ID.name, UUID::class.java)
                }
            }
        }
    }

    override fun registerParticipant(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        candidateId: UUID,
        participationStatus: ParticipationStatus,
    ) {
        connection.prepareStatement(INSERT_PARTICIPANT).use { ps ->
            ps.setObject(1, studyId)
            ps.setString(2, participantId)
            ps.setObject(3, candidateId)
            ps.setString(4, participationStatus.name)
            try {
                ps.executeUpdate()
            } catch (exception: SQLException) {
                if (exception.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    throw IllegalStateException("Participant is already registered for this study", exception)
                }
                throw exception
            }
        }
    }

    override fun isKnownDatasource(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
    ): Boolean {
        val hds = storageResolver.getPlatformStorage()

        return hds.connection.use { connection ->
            connection.prepareStatement(COUNT_DEVICE_ID).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.setObject(3, deviceId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "No count returned for study=$studyId, participant=$participantId, deviceId=$deviceId" }
                    ResultSetAdapters.count(rs) > 0 //could also check equal to one, but unique index exists in db
                }
            }
        }
    }

    override fun isKnownParticipant(studyId: UUID, participantId: String): Boolean {
        val hds = storageResolver.getPlatformStorage()

        return hds.connection.use { connection ->
            connection.prepareStatement(COUNT_STUDY_PARTICIPANTS).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "No count returned for study=$studyId, participant=$participantId" }
                    ResultSetAdapters.count(rs) > 0 //could also check equal to one, but unique index exists in db
                }
            }
        }
    }

    override fun getParticipant(studyId: UUID, participantId: String): Participant {
        val hds = storageResolver.getPlatformStorage()

        val (participationStatus, candidateId) = hds.connection.use { connection ->
            connection.prepareStatement(GET_PARTICIPANT).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "No row returned for study=$studyId, participant=$participantId" }
                    ResultSetAdapters.participantStatus(rs) to ResultSetAdapters.candidateId(rs)
                }
            }
        }
        return Participant(participantId, candidateManager.getCandidate(candidateId), participationStatus)
    }

    override fun getParticipationStatus(
        studyId: UUID,
        participantId: String,
    ): ParticipationStatus {
        logger.info(
            "getting participation status - studyId = {}, participantRef = {}",
            studyId,
            LogSanitizer.stableFingerprint(participantId, "participant"),
        )

        val hds = storageResolver.getPlatformStorage()

        return hds.connection.use { connection ->
            connection.prepareStatement(GET_PARTICIPATION_STATUS).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.executeQuery().use {
                    check(it.next()) { "No row returned for study=$studyId, participant=$participantId" }
                    ResultSetAdapters.participantStatus(it)
                }
            }
        }
    }

    override fun getStudyParticipantIds(studyId: UUID): Set<String> {
        val hds = storageResolver.getPlatformStorage()
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, GET_STUDY_PARTICIPANT_IDS) { ps ->
                ps.setObject(1, studyId)
            }
        ) { it.getString(PARTICIPANT_ID.name) }.toSet()
    }

    override fun getStudyParticipants(studyId: UUID): Set<Participant> {
        val hds = storageResolver.getPlatformStorage()
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, GET_STUDY_PARTICIPANTS) { ps ->
                ps.setObject(1, studyId)
            }
        ) { ResultSetAdapters.participant(it) }.toSet()
    }

    override fun studyExists(studyId: UUID): Boolean {
        val hds = storageResolver.getPlatformStorage()
        return hds.connection.use { connection ->
            connection.prepareStatement(COUNT_STUDY).use { ps ->
                ps.setObject(1, studyId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "No count returned for study=$studyId" }
                    ResultSetAdapters.count(rs) > 0
                }
            }
        }
    }

    // reason: nesting is the inherent connection.use/prepareStatement.use/executeQuery.use JDBC
    // resource scaffolding around the single-row lookup
    @Suppress("NestedBlockDepth")
    override fun getOrganizationIdForStudy(studyId: UUID): UUID {
        val hds = storageResolver.getPlatformStorage()
        return hds.connection.use { connection ->
            connection.prepareStatement(GET_ORGANIZATION_ID_FOR_STUDY).use { ps ->
                ps.setObject(1, studyId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getObject(ORGANIZATION_ID.name, UUID::class.java)
                    } else {
                        throw ResourceNotFoundException("Unable to find organization for study=$studyId")
                    }
                }
            }
        }
    }

}
