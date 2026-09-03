package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.query.Predicates
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.mapstores.UserMapstore
import com.openlattice.chronicle.constants.OutputConstants
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.import.ImportApi
import com.openlattice.chronicle.import.ImportApi.Companion.APP_USAGE_SURVEY
import com.openlattice.chronicle.import.ImportApi.Companion.CONTROLLER
import com.openlattice.chronicle.import.ImportApi.Companion.PARTICIPANTS
import com.openlattice.chronicle.import.ImportApi.Companion.PARTICIPANT_STATS
import com.openlattice.chronicle.import.ImportApi.Companion.PERMISSIONS
import com.openlattice.chronicle.import.ImportApi.Companion.STUDIES
import com.openlattice.chronicle.import.ImportApi.Companion.SYSTEM_APPS
import com.openlattice.chronicle.import.ImportApi.Companion.TIME_USE_DIARY
import com.openlattice.chronicle.import.ImportStudiesConfiguration
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.LEGACY_STUDY_IDS
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.SqlIdentifierValidator
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.NotImplementedException
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Array
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@RestController
@RequestMapping(CONTROLLER)
@Validated
@RateLimit(type = RateLimitType.ADMIN)
// reason: DI constructor signature consumed by Spring and by ImportControllerTest (positional);
// the migration-controller collaborators are wired here even where some are not yet referenced.
// reason(TooManyFunctions): REST routing controller — endpoint methods map 1:1 to the migration
// API surface; splitting the controller would fragment the route contract.
@Suppress("LongParameterList", "TooManyFunctions")
public open class ImportController(
    private val studyService: StudyService,
    // reason: DI-wired collaborator retained for the migration controller's public constructor
    @Suppress("UnusedPrivateProperty")
    private val candidateService: CandidateService,
    // reason: DI-wired collaborator retained for the migration controller's public constructor
    @Suppress("UnusedPrivateProperty")
    private val timeUseDiaryService: TimeUseDiaryService,
    // reason: DI-wired collaborator retained for the migration controller's public constructor
    @Suppress("UnusedPrivateProperty")
    private val appDataUploadService: AppDataUploadService,
    private val idGenerationService: HazelcastIdGenerationService,
    private val dataSourceManager: DataSourceManager,
    // reason: DI-wired collaborator retained for the migration controller's public constructor
    @Suppress("UnusedPrivateProperty")
    private val storageResolver: StorageResolver,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    hazelcast: HazelcastInstance,
) : ImportApi, AuthorizingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ImportController::class.java)
        private val mapper: ObjectMapper = ObjectMappers.newJsonMapper()

        /**
         * PreparedStatement bind order
         * 1) submission_id,
         * 3) study_id,
         * 4) participant_id
         * 5) submission_date,
         * 6) submission
         */
        private val INSERT_TUD_SUBMISSIONS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.TIME_USE_DIARY_SUBMISSIONS.name} values (?, ?, ?, ?, ?::jsonb)
        """.trimIndent()

        /**
         * PreparedStatement bind order
         * 1) studyId
         * 2) participantId
         * 3) submissionId
         * 4) date
         * 5) data
         */
        private val INSERT_INTO_TUD_SUMMARIZED_SQL = """
            INSERT INTO ${ChroniclePostgresTables.TIME_USE_DIARY_SUMMARIZED.name} values (?, ?, ?, ?, ?::jsonb)
        """.trimIndent()

        private val PARTICIPANT_STATS_COLUMNS = linkedSetOf(
            PostgresColumns.STUDY_ID,
            PostgresColumns.PARTICIPANT_ID,
            PostgresColumns.ANDROID_FIRST_DATE,
            PostgresColumns.ANDROID_LAST_DATE,
            PostgresColumns.ANDROID_UNIQUE_DATES,
            PostgresColumns.TUD_FIRST_DATE,
            PostgresColumns.TUD_LAST_DATE,
            PostgresColumns.TUD_UNIQUE_DATES
        )

        /**
         * PreparedStatement binding
         * 1) studyId
         * 2) participantId,
         * 3) androidFirstDate,
         * 4) androidLastDate,
         * 5) androidUniqueDates
         * 6) tudFirstDate,
         * 7) tudLastDate
         * 8) tudUniqueDates
         */
        private val INSERT_PARTICIPANT_STATS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.PARTICIPANT_STATS.name} (${PARTICIPANT_STATS_COLUMNS.joinToString { it.name }})
            VALUES (${PARTICIPANT_STATS_COLUMNS.joinToString { "?" }})
        """.trimIndent()
    }

    private val usersMap = HazelcastMap.USERS.getMap(hazelcast)

    @PostMapping(
        path = [STUDIES],
        produces = [MediaType.APPLICATION_JSON_VALUE],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importStudies(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        throw NotImplementedException("Migration endpoint has been removed")
    }

    @PostMapping(
        path = [PARTICIPANTS],
        produces = [MediaType.APPLICATION_JSON_VALUE],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importParticipants(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        throw NotImplementedException("Migration endpoint has been removed")
    }

    private fun tryGetPrincipal(principalId: String, principalEmail: String): List<Principal>? {
        if (usersMap.containsKey(principalId)) {
            val user = usersMap.getValue(principalId)
            if (user.email == principalEmail) {
                return listOf(Principal(PrincipalType.USER, principalId))
            }
        }

        val maybeUsers = usersMap.values(Predicates.equal(UserMapstore.EMAIL_INDEX, principalEmail))

        if (maybeUsers.size > 1) {
            logger.warn("Found more than 1 user with e-mail: $principalEmail, using the first")
        }

        return if (maybeUsers.isNotEmpty()) {
            maybeUsers.map { Principal(PrincipalType.USER, it.id) }
        } else {
            logger.warn("Didn't find any users with e-mail $principalEmail... skipping")
            null
        }
    }

    private fun getStudiesByLegacyStudyId(hds: HikariDataSource, candidatesTable: String): Map<UUID, UUID> {
        val table = SqlIdentifierValidator.validateImportTableName(candidatesTable)
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, getStudiesByLegacyStudyIdSql(table)) {}
        ) {
            it.getObject("legacy_study_id", UUID::class.java) to it.getObject("study_id", UUID::class.java)
        }.toMap()
    }

    private fun getStudiesByOrganizationId(hds: HikariDataSource, candidatesTable: String): Map<UUID, Set<UUID>> {
        val table = SqlIdentifierValidator.validateImportTableName(candidatesTable)
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, getStudiesByOrganizationIdSql(table)) {}
        ) {
            it.getObject("organization_id", UUID::class.java) to it.getObject("study_id", UUID::class.java)
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    @PostMapping(
        path = [PERMISSIONS],
        produces = [MediaType.APPLICATION_JSON_VALUE],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importUserPermissions(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        val hds = dataSourceManager.getDataSource(config.dataSourceName)

        logger.info("Starting to grant permissions.")
        val studiesByLegacyStudyId = getStudiesByLegacyStudyId(hds, config.candidatesTable)
        val studiesByOrganizationId = getStudiesByOrganizationId(hds, config.candidatesTable)

        grantLegacyUserPermissions(hds, config, studiesByLegacyStudyId)
        grantOrgUserPermissions(hds, config, studiesByOrganizationId)

        logger.info("Finished granting permissions!")
    }

    private fun grantLegacyUserPermissions(
        hds: HikariDataSource,
        config: ImportStudiesConfiguration,
        studiesByLegacyStudyId: Map<UUID, UUID>,
    ) {
        BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, getLegacyUserSql(config.legacyUsersTable!!)) {}
        ) {
            LegacyUser(
                it.getObject("participant_es_id", UUID::class.java),
                it.getString("principal_id"),
                it.getString("name"),
                it.getString("email")
            )
        }
            .filter { it.legacyEsName.startsWith("chronicle_participants_") }
            .forEach {
                it.legacyEsId = UUID.fromString(it.legacyEsName.removePrefix("chronicle_participants_"))
                val userStudyId = studiesByLegacyStudyId[it.legacyEsId]
                val principals = tryGetPrincipal(it.principalId, it.email)
                if (userStudyId != null && principals != null) {
                    val userStudy = studyService.getStudy(userStudyId)
                    principals.forEach { p ->
                        authorizationManager.addPermission(
                            AclKey(userStudy.id),
                            p,
                            EnumSet.allOf(Permission::class.java)
                        )
                    }
                } else {
                    logger.warn("Unable to resolve legacy study $it")
                }
            }
    }

    private fun grantOrgUserPermissions(
        hds: HikariDataSource,
        config: ImportStudiesConfiguration,
        studiesByOrganizationId: Map<UUID, Set<UUID>>,
    ) {
        BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, getUserSql(config.usersTable!!)) {}
        ) {
            val organizationId = it.getObject("organization_id", UUID::class.java)
            val principalId = it.getString("principal_id")
            val principalEmail = it.getString("principal_email")

            //Except for legacy org, lookup users and grant permission on studies in that org.
            //For legacy org we will rely on granting users permissions based off of what is in permissions table
            val orgStudies = studiesByOrganizationId[organizationId] ?: setOf()
            if (organizationId != LEGACY_ORG_ID && principalEmail != null) {
                val principals = tryGetPrincipal(principalId, principalEmail)
                if (principals != null) {
                    orgStudies.forEach { orgStudyId ->
                        principals.forEach { principal ->
                            authorizationManager.addPermission(
                                AclKey(orgStudyId),
                                principal,
                                EnumSet.allOf(Permission::class.java)
                            )
                        }
                    }
                }
            } else {
                if (principalEmail == null) {
                    logger.warn("Encountered missing e-mail for principal with id $principalId in org $organizationId")
                }
            }
        }.count()
    }

    private fun getStudiesByOrganizationIdSql(candidatesTable: String): String {
        return """
            SELECT distinct organization_id, study_id  FROM $candidatesTable
        """.trimIndent()
    }

    private fun getStudiesByLegacyStudyIdSql(candidateTable: String): String {
        return """
            SELECT distinct study_id, legacy_study_id  FROM $candidateTable
        """.trimIndent()
    }

    @PostMapping(
        path = [PARTICIPANT_STATS],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importParticipantStats(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        val hds = dataSourceManager.getDataSource(config.dataSourceName)
        val statsTable = SqlIdentifierValidator.validateImportTableName(config.participantStatsTable ?: "")
        val participantStats: List<ParticipantStats> = BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, "SELECT * FROM $statsTable") {}
        ) { ResultSetAdapters.participantStats(it) }
            .toList()
        logger.info("Retrieved ${participantStats.size} legacy participant stats entities")

        val inserts = hds.connection.use { connection ->
            insertParticipantStats(connection, participantStats)
        }
        logger.info("Inserted $inserts entities into participant_stats table")
    }

    private fun insertParticipantStats(connection: Connection, participantStats: List<ParticipantStats>): Int {
        return connection.prepareStatement(INSERT_PARTICIPANT_STATS_SQL).use { ps ->
            participantStats.forEach {
                val studyId = studyService.getStudyId(it.studyId)
                if (studyId == null) {
                    logger.warn("Missing study with legacy study ${it.studyId}. skipping insert")
                    return@forEach
                }
                var index = 0
                val androidUniqueDates =
                    connection.createArrayOf(PostgresDatatype.DATE.sql(), it.androidUniqueDates.toTypedArray())
                val tudUniqueDates =
                    connection.createArrayOf(PostgresDatatype.DATE.sql(), it.tudUniqueDates.toTypedArray())

                ps.setObject(++index, studyId)
                ps.setString(++index, it.participantId)
                ps.setObject(++index, it.androidFirstDate)
                ps.setObject(++index, it.androidLastDate)
                ps.setArray(++index, androidUniqueDates)
                ps.setObject(++index, it.tudFirstDate)
                ps.setObject(++index, it.tudLastDate)
                ps.setArray(++index, tudUniqueDates)
                ps.addBatch()
            }
            ps.executeBatch().sum()
        }
    }

    @PostMapping(
        path = [APP_USAGE_SURVEY],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importAppUsageSurvey(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        val hds = dataSourceManager.getDataSource(config.dataSourceName)
        val surveyTable = SqlIdentifierValidator.validateImportTableName(config.appUsageSurveyTable ?: "")
        val entities: List<V2AppUsageEntity> = BasePostgresIterable(
            PreparedStatementHolderSupplier(
                hds,
                "SELECT * FROM $surveyTable"
            ) {}
        ) {
            appUsageSurvey(it)
        }.toList()

        val legacyStudyIdMapping = getLegacyStudyIdMapping(hds)

        val inserts = hds.connection.use { connection ->
            insertAppUsageSurvey(connection, entities, legacyStudyIdMapping)
        }
        logger.info("inserted $inserts entities into app usage survey table")
    }

    private fun insertAppUsageSurvey(
        connection: Connection,
        entities: List<V2AppUsageEntity>,
        legacyStudyIdMapping: Map<UUID, UUID>,
    ): Int {
        return connection.prepareStatement(SurveysService.SUBMIT_APP_USAGE_SURVEY_SQL).use { ps ->
            entities.forEach {
                val studyId = legacyStudyIdMapping[it.studyId]
                if (studyId == null) {
                    logger.warn("Missing study with legacy studyId ${it.studyId}")
                    return@forEach
                }
                var index = 0
                ps.setObject(++index, studyId)
                ps.setString(++index, it.participantId)
                ps.setObject(++index, it.submissionDate)
                ps.setString(++index, it.applicationLabel)
                ps.setString(++index, it.appPackageName)
                ps.setObject(++index, it.timestamp)
                ps.setString(++index, it.timezone)
                ps.setArray(++index, it.users)
                ps.addBatch()
            }
            ps.executeBatch().sum()
        }
    }

    @PostMapping(
        path = [SYSTEM_APPS],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importSystemApps(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        // Defense-in-depth: validate table name even though Bean Validation already checked pattern
        val sourceTable = SqlIdentifierValidator.validateImportTableName(config.systemAppsTable ?: "")
        val hds = dataSourceManager.getDataSource(config.dataSourceName)
        hds.connection.createStatement().use { statement ->
            statement.execute("INSERT INTO ${ChroniclePostgresTables.SYSTEM_APPS.name} SELECT * FROM $sourceTable ON CONFLICT DO NOTHING")
        }

        // check inserts
        val inserted = BasePostgresIterable(
            PreparedStatementHolderSupplier(
                hds,
                "SELECT * FROM ${ChroniclePostgresTables.SYSTEM_APPS.name}"
            ) {}
        ) {
            ResultSetAdapters.systemApp(it)
        }.toList()

        logger.info("Inserted ${inserted.size} entities into system apps table")
    }

    @PostMapping(
        path = [TIME_USE_DIARY],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun importTimeUseDiarySubmissions(@Valid @RequestBody config: ImportStudiesConfiguration) {
        ensureAdminAccess()
        val hds = dataSourceManager.getDataSource(config.dataSourceName)
        val tudTable = SqlIdentifierValidator.validateImportTableName(config.timeUseDiaryTable ?: "")
        val tudEntities = BasePostgresIterable(
            PreparedStatementHolderSupplier(
                hds,
                "SELECT * FROM $tudTable"
            ) {}
        ) {
            tudSubmission(it)
        }.toList()

        val legacySubmissionIdMapping: MutableMap<UUID, UUID> = mutableMapOf()

        val inserted = insertTudSubmissions(hds, tudEntities, legacySubmissionIdMapping)
        logger.info("Imported $inserted time use diary submissions. Expected to import ${tudEntities.size}")

        val tudSubmissionById: Map<UUID, TudSubmission> = tudEntities.associateBy { it.submissionId }

        val tudSummarizedTable = SqlIdentifierValidator.validateImportTableName(config.timeUseDiarySummarizedTable ?: "")
        val summarizedData = BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, "SELECT * FROM $tudSummarizedTable") {}
        ) {
            tudSummarized(it)
        }.toList()

        val summaryInserts = insertTudSummaries(hds, summarizedData, tudSubmissionById, legacySubmissionIdMapping)
        logger.info("inserted $summaryInserts entities into time use diary summary table")
    }

    private fun insertTudSubmissions(
        hds: HikariDataSource,
        tudEntities: List<TudSubmission>,
        legacySubmissionIdMapping: MutableMap<UUID, UUID>,
    ): Int {
        return hds.connection.prepareStatement(INSERT_TUD_SUBMISSIONS_SQL).use { ps ->
            tudEntities.forEach {
                val realStudyId = studyService.getStudyId(it.studyId)
                if (realStudyId == null) {
                    logger.error("invalid study id ${it.studyId}")
                    return@forEach
                }
                var index = 0
                val submissionId = idGenerationService.getNextId()
                legacySubmissionIdMapping[it.submissionId] = submissionId
                ps.setObject(++index, submissionId)
                ps.setObject(++index, realStudyId)
                ps.setString(++index, it.participantId)
                ps.setObject(++index, it.submissionDate)
                ps.setString(++index, mapper.writeValueAsString(it.submission))
                ps.addBatch()
            }
            ps.executeBatch().sum()
        }
    }

    private fun insertTudSummaries(
        hds: HikariDataSource,
        summarizedData: List<TudSummarizedEntity>,
        tudSubmissionById: Map<UUID, TudSubmission>,
        legacySubmissionIdMapping: Map<UUID, UUID>,
    ): Int {
        return hds.connection.prepareStatement(INSERT_INTO_TUD_SUMMARIZED_SQL).use { ps ->
            summarizedData.forEach {
                val submissionId = legacySubmissionIdMapping[it.submissionId] ?: return@forEach
                val tudSubmission = tudSubmissionById.getValue(it.submissionId)
                val realStudyId = studyService.getStudyId(tudSubmission.studyId)
                if (realStudyId == null) {
                    logger.error("invalid study id ${tudSubmission.studyId}")
                    return@forEach
                }
                var index = 0
                ps.setObject(++index, realStudyId)
                ps.setString(++index, tudSubmission.participantId)
                ps.setObject(++index, submissionId)
                ps.setObject(++index, tudSubmission.submissionDate)
                ps.setString(++index, mapper.writeValueAsString(it.entities))
                ps.addBatch()
            }
            ps.executeBatch().sum()
        }
    }

    private fun tudSummarized(rs: ResultSet): TudSummarizedEntity {
        return TudSummarizedEntity(
            submissionId = rs.getObject(PostgresColumns.SUBMISSION_ID.name, UUID::class.java),
            entities = mapper.readValue(rs.getString("data"))
        )
    }

    private fun tudSubmission(rs: ResultSet): TudSubmission {
        return TudSubmission(
            studyId = rs.getObject(PostgresColumns.STUDY_ID.name, UUID::class.java),
            organizationId = rs.getObject(PostgresColumns.ORGANIZATION_ID.name, UUID::class.java),
            submissionId = rs.getObject(PostgresColumns.SUBMISSION_ID.name, UUID::class.java),
            participantId = rs.getString(PostgresColumns.PARTICIPANT_ID.name),
            submissionDate = rs.getObject(PostgresColumns.SUBMISSION_DATE.name, OffsetDateTime::class.java),
            submission = mapper.readValue(rs.getString(PostgresColumns.SUBMISSION.name))
        )
    }


    private fun study(rs: ResultSet, settings: StudySettings?): Study {

        val v2StudyId = rs.getString(V2_STUDY_ID)
        val v2StudyEkid = rs.getString(V2_STUDY_EK_ID)

        var description = rs.getString(LEGACY_DESC)
        if (StringUtils.isBlank(description)) {
            description = ""
        }

        var title = rs.getString(LEGACY_TITLE)
        if (StringUtils.isBlank(title)) {
            title = "NO TITLE - POSSIBLY DELETED STUDY"
            description = "study_id $v2StudyId study_ekid $v2StudyEkid"
        }

        return Study(
            title = title,
            description = description,
            settings = settings ?: StudySettings(),
            group = rs.getString(LEGACY_STUDY_GROUP) ?: "",
            version = rs.getString(LEGACY_STUDY_VERSION) ?: "",
            contact = rs.getString(LEGACY_STUDY_CONTACT) ?: "",
            updatedAt = rs.getObject(LEGACY_UPDATE_AT, OffsetDateTime::class.java)
        )
    }

    private fun getAppUsageTimestamp(rs: ResultSet): OffsetDateTime {
        val timezone: String = rs.getString(PostgresEventColumns.TIMEZONE.name) ?: OutputConstants.DEFAULT_TIMEZONE
        val timestamp = rs.getObject(PostgresEventColumns.TIMESTAMP.name, OffsetDateTime::class.java)
        val zoneId = ZoneId.of(timezone)
        return timestamp.toInstant().atZone(zoneId).toOffsetDateTime()
    }

    private fun appUsageSurvey(rs: ResultSet): V2AppUsageEntity {
        return V2AppUsageEntity(
            studyId = rs.getObject(V2_STUDY_ID, UUID::class.java),
            participantId = rs.getString(PARTICIPANT_ID),
            submissionDate = rs.getObject(PostgresColumns.SUBMISSION_DATE.name, OffsetDateTime::class.java),
            applicationLabel = rs.getString(PostgresEventColumns.APPLICATION_LABEL.name),
            appPackageName = rs.getString(PostgresEventColumns.APP_PACKAGE_NAME.name),
            timestamp = getAppUsageTimestamp(rs),
            timezone = rs.getString(PostgresEventColumns.TIMEZONE.name),
            users = rs.getArray(PostgresColumns.APP_USERS.name)
        )
    }

    private fun getLegacyStudyIdMapping(hds: HikariDataSource): Map<UUID, UUID> {
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, "SELECT * FROM ${LEGACY_STUDY_IDS.name}") {}
        ) { legacyStudyId(it) }
            .flatMap { it.asSequence() }
            .associate { it.key to it.value }
    }

    private fun legacyStudyId(rs: ResultSet): Map<UUID, UUID> {
        return mapOf(
            rs.getObject(
                PostgresColumns.LEGACY_STUDY_ID.name,
                UUID::class.java
            ) to rs.getObject(PostgresColumns.STUDY_ID.name, UUID::class.java)
        )
    }

    private fun v2StudyId(rs: ResultSet): UUID? {
        val v2StudyIdStr = rs.getString(V2_STUDY_ID)
        return if (StringUtils.isNotBlank(v2StudyIdStr)) UUID.fromString(v2StudyIdStr) else null
    }
}

private const val ANDROID_UNIQUE_DATES = "android_unique_dates"
private const val ANDROID_FIRST_DATE = "android_first_date"
private const val ANDROID_LAST_DATE = "android_last_date"
private const val LEGACY_DESC = "description"
private const val PARTICIPANT_ID = "participant_id"
private const val LEGACY_STUDY_CONTACT = "contact"
private const val LEGACY_STUDY_GROUP = "study_group"
private const val LEGACY_STUDY_ID = "legacy_study_id"
private const val LEGACY_STUDY_VERSION = "study_version"
private const val LEGACY_TITLE = "title"
private const val LEGACY_UPDATE_AT = "updated_at"
private const val TUD_UNIQUE_DATES = "tud_unique_dates"
private const val TUD_FIRST_DATE = "tud_first_date"
private const val TUD_LAST_DATE = "tud_last_date"
private const val V2_STUDY_EK_ID = "v2_study_ekid"
private const val V2_STUDY_ID = "v2_study_id"
private val LEGACY_ORG_ID = UUID.fromString("7349c446-2acc-4d14-b2a9-a13be39cff93")

private fun getUserSql(usersTable: String): String {
    val table = SqlIdentifierValidator.validateImportTableName(usersTable)
    return """
        SELECT * FROM $table
    """.trimIndent()
}

private fun getLegacyUserSql(legacyUsersTable: String): String {
    val table = SqlIdentifierValidator.validateImportTableName(legacyUsersTable)
    return """
        SELECT * FROM $table
    """.trimIndent()
}

private data class LegacyUser(
    val participantEsId: UUID,
    val principalId: String,
    val legacyEsName: String,
    val email: String,
) {
    var legacyEsId: UUID = UUID(0, 0)
}

private data class V2AppUsageEntity(
    val studyId: UUID,
    val participantId: String,
    val submissionDate: OffsetDateTime,
    val applicationLabel: String?,
    val appPackageName: String?,
    val timestamp: OffsetDateTime,
    val timezone: String?,
    val users: Array,
)

private data class TudSubmission(
    val studyId: UUID,
    val organizationId: UUID,
    val participantId: String,
    val submissionId: UUID,
    val submission: List<TimeUseDiaryResponse>,
    val submissionDate: OffsetDateTime,
)

private data class QuestionAnswer(
    val variable: String,
    val value: String,
)

private data class TudSummarizedEntity(
    val submissionId: UUID,
    val entities: Set<QuestionAnswer>,
)
