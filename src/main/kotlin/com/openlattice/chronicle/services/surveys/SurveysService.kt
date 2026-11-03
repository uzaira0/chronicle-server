package com.openlattice.chronicle.services.surveys

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.services.studies.StudySnapshotRepository
import com.openlattice.chronicle.services.studies.checkLockedStudyRevision
import com.openlattice.chronicle.services.studies.loadLockedStudySettings
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.codahale.metrics.annotation.Timed
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.StudyDeletionGuard
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_APP_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_BUNDLE_IDENTIFIER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_CATEGORY_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_DURATION_SECONDS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_OBSERVATION_END
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_WEB_DOMAIN
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.FILTERED_APPS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.QUESTIONNAIRES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.QUESTIONNAIRE_SUBMISSIONS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.SYSTEM_APPS
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.storage.SurveyEnrollment
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageBlock
import com.openlattice.chronicle.survey.AppUsageDayApp
import com.openlattice.chronicle.survey.AppUsageDayConflict
import com.openlattice.chronicle.survey.AppUsageDaySurvey
import com.openlattice.chronicle.survey.AppUsageSurveyDay
import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACTIVE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.COMPLETED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DESCRIPTION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.QUESTIONNAIRE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.QUESTIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.QUESTION_TITLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RECURRENCE_RULE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RESPONSES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TITLE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
internal data class FilteredAppsSnapshot(val appPackages: Collection<String>, val revision: Long)

@Service
public open class SurveysService(
    hazelcast: HazelcastInstance,
    private val storageResolver: StorageResolver,
    private val enrollmentManager: EnrollmentManager,
    private val scheduledTasksManager: ScheduledTasksManager,
    override val auditingManager: AuditingManager,
    public val idGenerationService: HazelcastIdGenerationService,
    private val clock: Clock = Clock.systemUTC(),
) : SurveysManager, AuditingComponent {
    private val filteredApps = HazelcastMap.FILTERED_APPS.getMap(hazelcast)

    internal companion object {
        private val logger = LoggerFactory.getLogger(SurveysService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()

        public const val UNKNOWN_BUNDLE = "Unknown"
        public const val TOTAL_USAGE_FIELD = "total_usage"
        public const val CATEGORIES_FIELD = "categories"

        private const val IOS_CATEGORY = "category"

        /**
         * iOS app usage per app and its Apple category (the app's own label when Apple names none): the newest
         * capture of each Screen Time window that ended inside the survey span, summed. Web-domain rows are not app usage.
         * 1. study id
         * 2. participant id
         * 3. start of time window
         * 4. end of time window
         */
        internal val GET_DEVICE_USAGE_IOS_SQL = """
            SELECT ${SCREEN_TIME_BUNDLE_IDENTIFIER.name},
                   COALESCE(${SCREEN_TIME_CATEGORY_NAME.name}, ${SCREEN_TIME_APP_NAME.name}, ${SCREEN_TIME_BUNDLE_IDENTIFIER.name})
                       AS $IOS_CATEGORY,
                   sum(${SCREEN_TIME_DURATION_SECONDS.name}) AS $TOTAL_USAGE_FIELD
            FROM (
                ${ScreenTimeUsageCaptures.newestCapturesSql(
                    "${PostgresColumns.STUDY_ID.name} = ? AND ${PostgresColumns.PARTICIPANT_ID.name} = ? " +
                        "AND ${SCREEN_TIME_OBSERVATION_END.name} > ? AND ${SCREEN_TIME_OBSERVATION_END.name} <= ?",
                )}
            ) AS newest
            WHERE ${SCREEN_TIME_WEB_DOMAIN.name} IS NULL
                AND ${SCREEN_TIME_DURATION_SECONDS.name} > 0
                AND COALESCE(${SCREEN_TIME_APP_NAME.name}, ${SCREEN_TIME_BUNDLE_IDENTIFIER.name}) IS NOT NULL
            GROUP BY ${SCREEN_TIME_BUNDLE_IDENTIFIER.name}, $IOS_CATEGORY
        """.trimIndent()

        private val QUESTIONNAIRE_COLUMNS = QUESTIONNAIRES.columns.joinToString { it.name }

        /**
         * PreparedStatement bind order
         * 1) studyId
         * 2) questionnaireId
         */
        private val GET_QUESTIONNAIRE_SQL = """
            SELECT $QUESTIONNAIRE_COLUMNS
            FROM ${QUESTIONNAIRES.name}
            WHERE ${STUDY_ID.name} = ? AND ${QUESTIONNAIRE_ID.name} = ?
        """.trimIndent()

        /**
         * PreparedStatement bind order
         * 1) studyId
         */
        private val GET_STUDY_QUESTIONNAIRES_SQL = """
            SELECT $QUESTIONNAIRE_COLUMNS
            FROM ${QUESTIONNAIRES.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        private val SUBMIT_QUESTIONNAIRE_COLS = QUESTIONNAIRE_SUBMISSIONS.columns.joinToString { it.name }
        private val SUBMIT_QUESTIONNAIRE_PARAMS = QUESTIONNAIRE_SUBMISSIONS.columns.joinToString {
            if (it.datatype == PostgresDatatype.JSONB) "?::jsonb" else "?"
        }

        /**
         * PreparedStatement bind order
         * 1) id
         * 2) studyId
         * 3) participantId
         * 4) questionnaireId
         * 5) completedAt
         * 6) questionTitle
         * 7) responses
         */
        private val SUBMIT_QUESTIONNAIRE_SQL = """
            INSERT INTO ${QUESTIONNAIRE_SUBMISSIONS.name} ($SUBMIT_QUESTIONNAIRE_COLS)
            VALUES ($SUBMIT_QUESTIONNAIRE_PARAMS)
        """.trimIndent()

        /**
         * 1. study id
         */
        private val DELETE_FILTERED_APPS_FOR_STUDY = """
            DELETE FROM ${FILTERED_APPS.name} WHERE ${STUDY_ID.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         * 2. app package name
         */
        private val DELETE_FILTERED_APP_FOR_STUDY = """
            DELETE FROM ${FILTERED_APPS.name} WHERE ${STUDY_ID.name} = ? AND ${APP_PACKAGE_NAME.name} = ?
        """.trimIndent()

        /**
         * 1. study id
         * 2. app package name
         */
        private val INSERT_FILTERED_APP_FOR_STUDY = """
            INSERT INTO ${FILTERED_APPS.name} (${STUDY_ID.name},${APP_PACKAGE_NAME.name}) VALUES(?, ?)
            ON CONFLICT (${STUDY_ID.name}, ${APP_PACKAGE_NAME.name}) DO NOTHING
        """.trimIndent()

        /**
         * 1. study id
         */
        private val INIT_FILTERED_APPS_FOR_STUDY = """
            INSERT INTO ${FILTERED_APPS.name} (${STUDY_ID.name},${APP_PACKAGE_NAME.name}) 
                SELECT ?,${APP_PACKAGE_NAME.name} FROM ${SYSTEM_APPS.name}
        """.trimIndent()

        /**
         * PreparedStatement bind order
         * 1) studyId
         * 2) questionnaireId
         */
        internal val GET_QUESTIONNAIRE_SUBMISSIONS_SQL = """
            SELECT ${PARTICIPANT_ID.name}, ${QUESTION_TITLE.name}, ${COMPLETED_AT.name}, ${RESPONSES.name}
            FROM ${QUESTIONNAIRE_SUBMISSIONS.name}
            WHERE ${STUDY_ID.name} = ? AND ${QUESTIONNAIRE_ID.name} = ?
        """.trimIndent()

        /**
         * PreparedStatement bind order
         * 1) studyId
         * 2) questionnaireId
         */
        private val DELETE_QUESTIONNAIRE_SQL = """
            DELETE FROM ${QUESTIONNAIRES.name}
            WHERE ${STUDY_ID.name} = ? AND ${QUESTIONNAIRE_ID.name} = ?
        """.trimIndent()

        private fun getUpdateQuestionnaireSql(update: QuestionnaireUpdate): String {
            val optionalColumns = buildString {
                update.description?.let { append(", ${DESCRIPTION.name} = ? ") }
                update.recurrenceRule?.let { append(", ${RECURRENCE_RULE.name} = ?") }
                update.active?.let { append(", ${ACTIVE.name} = ? ") }
                update.questions?.let { append(", ${QUESTIONS.name} = ?::jsonb") }
            }
            return """
            UPDATE ${QUESTIONNAIRES.name}
              SET ${TITLE.name} = ? $optionalColumns
            WHERE ${STUDY_ID.name} = ? AND ${QUESTIONNAIRE_ID.name} = ?
        """.trimIndent()
        }
    }






    private val surveyStore = AppUsageSurveyStore(storageResolver)

    private fun excludedApps(studyId: UUID): Collection<String> =
        filteredApps[studyId] ?: scheduledTasksManager.systemAppPackageNames

    /** Survey reads see the participant's admitted enrolled time (null when refused), in a transaction of their own. */
    private fun <T> surveyRead(studyId: UUID, participantId: String, read: (Connection, SurveyEnrollment?) -> T): T =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.withTransaction(rollbackOnSuccess = true) {
                read(connection, ParticipantCollectionGate(connection).surveyEnrollment(studyId, participantId))
            }
        }

    /** Each ended day's apps for [days], from one read of the span they cover. */
    private fun listings(studyId: UUID, participantId: String, enrollment: SurveyEnrollment, days: List<LocalDate>,
                         rules: AppUsageDayRules): Map<LocalDate, List<ListedApp>> {
        if (days.isEmpty()) return emptyMap()
        val span = AppUsageDays.bounds(days.first(), rules.zone).start..<AppUsageDays.bounds(days.last(), rules.zone).endExclusive
        val transitions = surveyStore.transitions(studyId, participantId, span, excludedApps(studyId))
        val intervals = AppUsageDays.foreground(transitions, span, enrollment.stops)
        val labels = transitions.filter { !it.label.isNullOrBlank() }.sortedBy { it.at }.associate { it.app to checkNotNull(it.label) }
        return days.associateWith { date ->
            AppUsageDays.listing(intervals, labels, checkNotNull(AppUsageDays.window(date, rules.zone, enrollment)),
                AppUsageDays.blocks(date, rules.zone, rules.frequency), rules.thresholdSeconds)
        }
    }

    override fun getAppUsageDays(studyId: UUID, participantId: String, rules: AppUsageDayRules): AppUsageSurveyDays? =
        surveyRead(studyId, participantId) { connection, admitted ->
            val enrollment = admitted ?: return@surveyRead null
            val now = clock.instant()
            val days = AppUsageDays.endedDays(rules.zone, enrollment, now)
            val stored = surveyStore.answers(connection, studyId, participantId)
            val listed = listings(studyId, participantId, enrollment, days, rules)
            AppUsageSurveyDays(rules.zone.id, now.atZone(rules.zone).toLocalDate(), days.map { date ->
                AppUsageSurveyDay(date, AppUsageDays.state(listed.getValue(date), AppUsageDays.answersOf(date, rules.zone, stored)))
            })
        }

    public fun getAppUsageDay(studyId: UUID, participantId: String, date: LocalDate, rules: AppUsageDayRules): AppUsageDaySurvey =
        surveyRead(studyId, participantId) { connection, admitted ->
            val enrollment = admitted ?: throw CollectionUnavailableException(CollectionUnavailableException.Scope.PARTICIPANT)
            val listed = endedDayListing(studyId, participantId, enrollment, date, rules)
            val answers = AppUsageDays.answersOf(date, rules.zone, surveyStore.answers(connection, studyId, participantId))
            AppUsageDaySurvey(date, rules.zone.id, AppUsageDays.state(listed, answers), listed.map { app ->
                AppUsageDayApp(app.app, app.label, app.blocks.map { block ->
                    AppUsageBlock(block.start.atZone(rules.zone).toOffsetDateTime(),
                        block.endExclusive.atZone(rules.zone).toOffsetDateTime(), app.answer(block, answers)?.users)
                })
            })
        }

    private fun endedDayListing(studyId: UUID, participantId: String, enrollment: SurveyEnrollment, date: LocalDate,
                                rules: AppUsageDayRules): List<ListedApp> {
        if (date !in AppUsageDays.endedDays(rules.zone, enrollment, clock.instant())) {
            throw AppUsageDayConflictException(AppUsageDayConflict.DAY_UNAVAILABLE)
        }
        return listings(studyId, participantId, enrollment, listOf(date), rules).getValue(date)
    }

    /** A researcher's submission: the same day rules in a transaction of its own. */
    public fun submitAppUsageDay(studyId: UUID, participantId: String, date: LocalDate, answers: List<AppUsageAnswer>,
                                 rules: AppUsageDayRules) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { submitAppUsageDay(it, studyId, participantId, date, answers, rules) }
                .audit { emptyList() }
                .buildAndRun()
        }
    }

    /**
     * Stores answers for unanswered blocks of one ended day on the caller's transaction. An empty list confirms a day
     * with no apps. Answering an answered block, or a block the day no longer lists, is a conflict for the page to show.
     */
    public fun submitAppUsageDay(connection: Connection, studyId: UUID, participantId: String, date: LocalDate,
                                 answers: List<AppUsageAnswer>, rules: AppUsageDayRules) {
        val enrollment = ParticipantCollectionGate(connection).surveyEnrollment(studyId, participantId)
            ?: throw CollectionUnavailableException(CollectionUnavailableException.Scope.PARTICIPANT)
        val listed = endedDayListing(studyId, participantId, enrollment, date, rules)
        val stored = AppUsageDays.answersOf(date, rules.zone, surveyStore.answers(connection, studyId, participantId))
        require(answers.distinctBy { it.appPackageName to it.blockStart.toInstant() }.size == answers.size) {
            "Each app and block is answered once"
        }
        val rows = AppUsageDays.rows(listed, stored, answers, AppUsageDays.bounds(date, rules.zone).start)
        if (!surveyStore.insert(connection, studyId, participantId, date, rules.zone, OffsetDateTime.now(clock), rows)) {
            throw AppUsageDayConflictException(AppUsageDayConflict.ALREADY_ANSWERED)
        }
    }

    /**
     * @param studyId The study id
     * @param participantId The id of the participant.
     * @param startDateTime The start date time for the beginning of the window to retrieve
     * @param endDateTime This parameter is currently ignroed because it maybe missing in early stages of data collection.
     */
    // reason: boundary catch — must log and rethrow any failure type from the DB read path
    @Suppress("TooGenericExceptionCaught")
    private fun getIosDeviceUsageData(
        studyId: UUID,
        participantId: String,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): DeviceUsage {
        try {

            //category = categoryByPackage[package]
            val categoryByPackage = mutableMapOf<String, String>()

            val result = BasePostgresIterable(
                PreparedStatementHolderSupplier(storageResolver.getPlatformReadStorage(), GET_DEVICE_USAGE_IOS_SQL) { ps ->
                    ps.setObject(1, studyId)
                    ps.setString(2, participantId)
                    ps.setObject(3, startDateTime)
                    ps.setObject(4, endDateTime)
                }
            ) { rs ->
                IosDeviceUsageByCategory(
                    rs.getString(SCREEN_TIME_BUNDLE_IDENTIFIER.name),
                    rs.getString(IOS_CATEGORY),
                    rs.getDouble(TOTAL_USAGE_FIELD),
                )
            }
                .groupBy { it.category }
                .mapValues { (category, usageByCategory) ->
                    usageByCategory.fold(0.0) { totalUsage, packageUsage ->
                        categoryByPackage[packageUsage.bundleIdentifier ?: UNKNOWN_BUNDLE] = category
                        totalUsage + packageUsage.usageInSeconds
                    }
                }


            logger.info(
                "fetched {} device usage categories spanning {} to {} studyId={} participant={}",
                result.size,
                startDateTime,
                endDateTime,
                studyId,
                LogSanitizer.stableFingerprint(participantId, "participant")
            )

            return DeviceUsage(result.values.sum(), result, categoryByPackage)
        } catch (ex: Exception) {
            logger.error("unable to fetch data for app usage survey")
            throw ex
        }
    }

    override fun getDeviceUsageData(
        realStudyId: UUID,
        participantId: String,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): DeviceUsage {
        val iosDeviceUsage = getIosDeviceUsageData(realStudyId, participantId, startDateTime, endDateTime)
        // Android foreground time follows the survey's one session rule, up to the server's now.
        val span = startDateTime.toInstant()..<minOf(endDateTime.toInstant(), clock.instant())
        val filtered = if (span.isEmpty()) emptyMap() else AppUsageDays.foreground(
            surveyStore.transitions(realStudyId, participantId, span, excludedApps(realStudyId)), span,
        ).mapValues { (_, used) -> used.sumOf { Duration.between(it.start, it.endExclusive).seconds.toDouble() } }
        val totalTime = filtered.values.sum()
        val androidDeviceUsage = DeviceUsage(totalTime, filtered, mapOf())
        return DeviceUsage(
            totalTime = androidDeviceUsage.totalTime + iosDeviceUsage.totalTime,
            iosDeviceUsage.usageByPackage + androidDeviceUsage.usageByPackage,
            androidDeviceUsage.categoryByPackage + iosDeviceUsage.categoryByPackage,
            androidDeviceUsage.users + iosDeviceUsage.users
        )
    }

    // reason: boundary catch — must log and rethrow any failure type from the audited transaction
    @Suppress("TooGenericExceptionCaught")
    override fun createQuestionnaire(studyId: UUID, questionnaire: Questionnaire): UUID {
        try {
            val questionnaireId = idGenerationService.getNextId()

            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<Unit>(connection, auditingManager)
                    .transaction { conn ->
                        QuestionnaireRepository(conn, mapper).insert(
                            studyId = studyId,
                            questionnaireId = questionnaireId,
                            questionnaire = questionnaire,
                        )
                    }
                    .audit {
                        listOf(
                            AuditableEvent(
                                AclKey(studyId),
                                eventType = AuditEventType.CREATE_QUESTIONNAIRE,
                                description = "Created questionnaire with id $questionnaireId",
                                study = studyId,
                            )
                        )
                    }
                    .buildAndRun()
            }

            return questionnaireId
        } catch (ex: Exception) {
            logger.error("unable to save questionnaire", ex)
            throw ex
        }
    }

    // reason: boundary catch — must log and rethrow any failure type from the audited transaction
    @Suppress("TooGenericExceptionCaught")
    override fun updateQuestionnaire(studyId: UUID, questionnaireId: UUID, update: QuestionnaireUpdate) {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<Int>(connection, auditingManager)
                    .transaction {
                        connection.prepareStatement(getUpdateQuestionnaireSql(update)).use { ps ->
                            var index = 0
                            ps.setString(++index, update.title)
                            update.description?.let { ps.setString(++index, it) }
                            update.recurrenceRule?.let { ps.setString(++index, it.orElse(null)) }
                            update.active?.let { ps.setBoolean(++index, it) }
                            update.questions?.let { ps.setString(++index, mapper.writeValueAsString(it)) }
                            ps.setObject(++index, studyId)
                            ps.setObject(++index, questionnaireId)

                            ps.executeUpdate()
                        }
                    }
                    .audit {
                        listOf(
                            AuditableEvent(
                                aclKey = AclKey(studyId),
                                eventType = AuditEventType.UPDATE_QUESTIONNAIRE,
                                description = "Updated questionnaire with id $questionnaireId",
                                study = studyId
                            )
                        )
                    }
                    .buildAndRun()
            }
        } catch (ex: Exception) {
            logger.error("unable to toggle questionnaire active status")
            throw ex
        }
    }

    // reason: boundary catch — must log and rethrow any failure type from the DB read path
    @Suppress("TooGenericExceptionCaught")
    override fun getQuestionnaire(studyId: UUID, questionnaireId: UUID): Questionnaire {
        try {
            val hds = storageResolver.getPlatformStorage()
            return BasePostgresIterable(
                PreparedStatementHolderSupplier(hds, GET_QUESTIONNAIRE_SQL) { ps ->
                    ps.setObject(1, studyId)
                    ps.setObject(2, questionnaireId)
                }
            ) {
                ResultSetAdapters.questionnaire(it)
            }.first()

        } catch (ex: Exception) {
            logger.error("unable to fetch questionnaire: id = $questionnaireId, studyId = $studyId")
            throw ex
        }
    }

    // reason: boundary catch — must log and rethrow any failure type from the audited transaction
    @Suppress("TooGenericExceptionCaught")
    override fun deleteQuestionnaire(studyId: UUID, questionnaireId: UUID) {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<Unit>(connection, auditingManager)
                    .transaction {
                        QuestionnaireRepository(connection, mapper).loadSnapshot(studyId = studyId, questionnaireId = questionnaireId)
                        connection.prepareStatement(
                            // Counts responses that a processing block or quarantine hides from this connection.
                            "SELECT public.chronicle_questionnaire_has_submissions(?, ?)",
                        ).use { statement ->
                            statement.setObject(1, studyId)
                            statement.setObject(2, questionnaireId)
                            statement.executeQuery().use { rows ->
                                check(rows.next())
                                if (rows.getBoolean(1)) throw org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.CONFLICT,
                                    Messages.get("error.questionnaire.responsesRetained"),
                                )
                            }
                        }
                        connection.prepareStatement(DELETE_QUESTIONNAIRE_SQL).use { ps ->
                            ps.setObject(1, studyId)
                            ps.setObject(2, questionnaireId)
                            ps.execute()
                        }
                    }.audit {
                        listOf(
                            AuditableEvent(
                                aclKey = AclKey(studyId),
                                eventType = AuditEventType.DELETE_QUESTIONNAIRE,
                                description = "Deleted questionnaire of id $questionnaireId",
                                study = studyId
                            )
                        )
                    }.buildAndRun()
            }

        } catch (ex: Exception) {
            logger.info("error deleting questionnaire $questionnaireId in study $studyId")
            throw ex
        }
    }

    // reason: boundary catch — must log and rethrow any failure type from the DB read path
    @Suppress("TooGenericExceptionCaught")
    override fun getStudyQuestionnaires(studyId: UUID): List<Questionnaire> {
        try {
            val hds = storageResolver.getPlatformStorage()
            return BasePostgresIterable(PreparedStatementHolderSupplier(hds, GET_STUDY_QUESTIONNAIRES_SQL) { ps ->
                ps.setObject(1, studyId)
            }) {
                ResultSetAdapters.questionnaire(it)
            }.toList()
        } catch (ex: Exception) {
            logger.error("unable fetching study $studyId questionnaires")
            throw ex
        }
    }

    // reason: boundary catch — must log and rethrow any failure type from the audited submission path
    @Suppress("TooGenericExceptionCaught")
    override fun submitQuestionnaireResponses(
        studyId: UUID,
        participantId: String,
        questionnaireId: UUID,
        responses: List<QuestionnaireResponse>,
    ) {
        try {
            val isKnownParticipant = enrollmentManager.isKnownParticipant(studyId, participantId)
            if (!isKnownParticipant) {
                logger.error(
                    "cannot submit questionnaire because participant was not found studyId={} participant={}",
                    studyId,
                    LogSanitizer.stableFingerprint(participantId, "participant")
                )
                throw IllegalArgumentException("participant not found")
            }

            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<UUID>(connection, auditingManager)
                    .transaction { submitQuestionnaireResponses(it, studyId, participantId, questionnaireId, responses) }
                    .audit { emptyList() }
                    .buildAndRun()
            }
            logger.info(
                "recorded {} questionnaire responses studyId={} participant={}",
                responses.size,
                studyId,
                LogSanitizer.stableFingerprint(participantId, "participant")
            )

        } catch (ex: Exception) {
            logger.error("error submitting questionnaire responses")
            throw ex
        }
    }

    /** Transaction-participating adapter used by the participant idempotency boundary. */
    public fun submitQuestionnaireResponses(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        questionnaireId: UUID,
        responses: List<QuestionnaireResponse>,
    ): UUID {
        ParticipantCollectionGate(connection).requireActive(studyId, participantId)
        requireNotNull(QuestionnaireRepository(connection, mapper).loadSnapshot(studyId = studyId, questionnaireId = questionnaireId)) {
            Messages.get("error.questionnaire.unavailable")
        }
        val submissionId = idGenerationService.getNextId()
        connection.prepareStatement(SUBMIT_QUESTIONNAIRE_SQL).use { statement ->
            statement.setObject(1, submissionId)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.setObject(4, questionnaireId)
            statement.setObject(5, OffsetDateTime.now())
            responses.forEach { response ->
                statement.setString(6, response.questionTitle)
                statement.setArray(7, PostgresArrays.createTextArray(connection, response.value))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        return submissionId
    }


    @Timed
    override fun getAppsFilteredForStudyAppUsageSurvey(studyId: UUID): Collection<String> =
        getFilteredAppsSnapshot(studyId).appPackages

    internal fun getFilteredAppsSnapshot(studyId: UUID): FilteredAppsSnapshot {
        if (StudyDeletionGuard(storageResolver)
                .blockedStudyIds(listOf(studyId)).isNotEmpty()) {
            try { filteredApps.evict(studyId) } catch (failure: Exception) {
                logger.error("Study {} is erased or quarantined, but filtered-app eviction failed", studyId, failure)
            }
            throw NoSuchElementException("No study found for id $studyId")
        }
        return storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<FilteredAppsSnapshot>(connection, auditingManager)
                .transaction { transaction ->
                    loadLockedStudySettings(transaction, studyId)
                    val apps = transaction.prepareStatement(
                        "SELECT app_package_name FROM filtered_apps WHERE study_id = ? ORDER BY app_package_name",
                    ).use { statement ->
                        statement.setObject(1, studyId)
                        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                    }
                    FilteredAppsSnapshot(apps.ifEmpty { scheduledTasksManager.systemAppPackageNames.toList() },
                        StudySnapshotRepository(transaction).settingsRevision(studyId))
                }.audit {
                    listOf(AuditableEvent(aclKey = AclKey(studyId), eventType = AuditEventType.GET_FILTERED_APPS,
                        description = "Retrieved list of filtered applications."))
                }.buildAndRun()
        }
    }

    @Timed
    override fun setAppsFilteredForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>, expectedRevision: Long): Long {
        val revision = storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Long>(connection, auditingManager)
                .transaction { conn ->
                    checkLockedStudyRevision(conn, studyId, expectedRevision)
                    val psDel = conn.prepareStatement(DELETE_FILTERED_APPS_FOR_STUDY)
                    psDel.setObject(1, studyId)
                    psDel.executeUpdate()

                    val psInsert = conn.prepareStatement(INSERT_FILTERED_APP_FOR_STUDY)
                    psInsert.setObject(1, studyId)
                    appPackages.forEach {
                        psInsert.setString(2, it)
                        psInsert.addBatch()
                    }
                    psInsert.executeBatch()
                    StudySnapshotRepository(conn).bumpSettingsRevision(studyId)
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            aclKey = AclKey(studyId),
                            eventType = AuditEventType.GET_FILTERED_APPS,
                            description = "Retrieved list of filtered applications."
                        )
                    )
                }.buildAndRun()
        }
        refreshMapstore(studyId)
        return revision
    }

    @Timed
    override fun filterAppForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    loadLockedStudySettings(conn, studyId)
                    val psInsert = conn.prepareStatement(INSERT_FILTERED_APP_FOR_STUDY)
                    psInsert.setObject(1, studyId)
                    appPackages.forEach {
                        psInsert.setString(2, it)
                        psInsert.addBatch()
                    }
                    psInsert.executeBatch()
                    StudySnapshotRepository(conn).bumpSettingsRevision(studyId)
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            aclKey = AclKey(studyId),
                            eventType = AuditEventType.FILTER_APPS,
                            description = "Retrieved list of filtered applications."
                        )
                    )
                }.buildAndRun()
        }
        refreshMapstore(studyId)
    }

    @Timed
    override fun allowAppForStudyAppUsageSurvey(studyId: UUID, appPackages: Set<String>) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    loadLockedStudySettings(conn, studyId)
                    val psInsert = conn.prepareStatement(DELETE_FILTERED_APP_FOR_STUDY)
                    psInsert.setObject(1, studyId)
                    appPackages.forEach {
                        psInsert.setString(2, it)
                        psInsert.addBatch()
                    }
                    psInsert.executeBatch()
                    StudySnapshotRepository(conn).bumpSettingsRevision(studyId)
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            aclKey = AclKey(studyId),
                            eventType = AuditEventType.ALLOW_APPS,
                            description = "Retrieved list of filtered applications."
                        )
                    )
                }.buildAndRun()
        }
        refreshMapstore(studyId)
    }

    public fun refreshMapstore(studyId: UUID) {
        PinnedPlatformConnection.afterCommitOrNow(storageResolver.getPlatformStorage(), "filtered-apps:$studyId") {
            filteredApps.loadAll(setOf(studyId), true)
        }
    }

    override fun initializeFilterdApps(connection: Connection, studyId: UUID) {
        val inserted = connection.prepareStatement(INIT_FILTERED_APPS_FOR_STUDY).use { ps ->
            ps.setObject(1, studyId)
            ps.executeUpdate()
        }

        logger.info("Initialized $inserted filtered apps for study $studyId based off defaults.")
    }
}
