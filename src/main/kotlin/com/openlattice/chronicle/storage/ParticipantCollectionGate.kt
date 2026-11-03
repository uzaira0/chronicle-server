package com.openlattice.chronicle.storage

import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ParticipantConsentState
import com.openlattice.chronicle.collection.participantConsentState
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.EnrollmentAttemptRepository
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.study.StudyLifecycleStatus
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Enrolled time a survey may ask about: from the first enrollment key ([from], null before a device enrolls, so there
 * are no days yet) to the stop ([until], null while collecting), minus past pause and archive intervals.
 */
private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal data class SurveyEnrollment(val from: Instant?, val until: Instant?, val closed: List<OpenEndRange<Instant>>) {
    /** Where collection stopped: each past pause or archive began, and the stop of collection. */
    val stops: List<Instant> get() = closed.map { it.start } + listOfNotNull(until)
}

/**
 * Locks the participant's collection state until the caller's write commits. Uploads are admitted by server facts
 * only: the phone decides which rows fall inside a closed capture window, on its
 * own clock, from the windows [captureWindows] publishes. The server never compares a phone capture time with one
 * of its own instants.
 */
internal class ParticipantCollectionGate(private val connection: Connection) {
    private val lockRows = !connection.isReadOnly
    companion object {
        private const val CLOSED_WINDOWS =
            "studies.closed_capture_windows + COALESCE(participant.closed_capture_windows, '{}'::tstzmultirange)"
        internal val WINDOW_QUERY = windowQuery(true)
        // A cancelled attempt's key never enrolled anyone: the first survey day is the enrollment in force.
        private val SURVEY_ENROLLMENT_QUERY = """
            SELECT (SELECT min(created_at) FROM api_keys WHERE study_id = ? AND participant_id = ?
                      AND NOT ${EnrollmentAttemptRepository.cancelledKeyCondition("api_keys.key_hash")}) AS enrolled_from,
                   EXISTS (SELECT 1 FROM mobile_withdrawal_requests WHERE study_id = ? AND participant_id = ?) AS withdrawn
        """

        private fun windowQuery(lockRows: Boolean): String {
            val lock = if (lockRows) "FOR SHARE" else ""
            val studyLock = if (lockRows) "$lock OF studies" else ""
            return """
            SELECT lifecycle_status, collection_closed_at, participant.participation_status, participant.capture_cutoff,
                   participant.processing_blocked_at,
                   ARRAY(SELECT lower(closed) FROM unnest($CLOSED_WINDOWS) closed) AS closed_from,
                   ARRAY(SELECT upper(closed) FROM unnest($CLOSED_WINDOWS) closed) AS closed_until
            FROM studies LEFT JOIN LATERAL (
                SELECT participation_status, capture_cutoff, processing_blocked_at, closed_capture_windows FROM study_participants
                WHERE study_id = studies.study_id AND participant_id = ? $lock
            ) participant ON true
            WHERE study_id = ? $studyLock
        """.trimIndent()
        }
    }

    private sealed interface Window {
        /** Past pause, completion, archive and processing-block intervals (the survey's enrolled time excludes them). */
        val closed: List<OpenEndRange<Instant>> get() = emptyList()

        data class Open(override val closed: List<OpenEndRange<Instant>>) : Window
        data class ClosedAt(val cutoff: Instant, override val closed: List<OpenEndRange<Instant>>) : Window
        data object Unavailable : Window
    }

    private data class State(
        val lifecycle: StudyLifecycleStatus,
        val participation: ParticipationStatus?,
        val studyCutoff: Instant?,
        val participantCutoff: Instant?,
        val processingBlockedAt: Instant?,
        val closed: List<OpenEndRange<Instant>>,
    ) {
        /** Withdrawal and Mark not enrolled are a hard stop: NOT_ENROLLED is never collecting. */
        fun window(participantRequired: Boolean): Window {
            if (processingBlockedAt != null) return Window.Unavailable
            val studyClose = when (lifecycle) {
                StudyLifecycleStatus.ACTIVE -> null
                StudyLifecycleStatus.ARCHIVED -> studyCutoff ?: return Window.Unavailable
                StudyLifecycleStatus.SCHEDULED_FOR_DELETION -> return Window.Unavailable
            }
            val participantClose = when (participation) {
                ParticipationStatus.ENROLLED -> null
                ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED ->
                    participantCutoff ?: return Window.Unavailable
                ParticipationStatus.NOT_ENROLLED, ParticipationStatus.UNKNOWN, ParticipationStatus.ERASED ->
                    return Window.Unavailable
                null -> if (participantRequired) return Window.Unavailable else null
            }
            return listOfNotNull(studyClose, participantClose).minOrNull()?.let { Window.ClosedAt(it, closed) } ?: Window.Open(closed)
        }

        /** Every past window, then the ones still open: a pause or completion, an archive, a processing block. */
        fun captureWindows(): List<CaptureWindow> =
            (closed.map { CaptureWindow(it.start.utc(), it.endExclusive.utc()) } +
                listOfNotNull(participantCutoff, studyCutoff, processingBlockedAt).map { CaptureWindow(it.utc()) })
                .sortedBy { it.start }
    }

    /**
     * [allowQueuedCaptures]: a paused, completed or archived participant still uploads what it queued;
     * which of those rows were captured inside a window is the phone's decision, not this gate's.
     */
    fun isActive(studyId: UUID, participantId: String? = null, allowQueuedCaptures: Boolean = false,
                 checkConsent: Boolean = true): Boolean =
        when (loadLockedWindow(studyId, participantId)) {
            is Window.Open -> !checkConsent || participantId == null || !consentHalted(studyId, participantId)
            is Window.ClosedAt -> allowQueuedCaptures
            Window.Unavailable -> false
        }

    /**
     * Refuses the whole upload once collection is unavailable (withdrawn, not enrolled, erased, processing blocked,
     * study scheduled for deletion). Which rows a decision authorizes is the upload recheck's integer rule.
     */
    fun requireCollectionAvailable(studyId: UUID, participantId: String) {
        if (loadLockedWindow(studyId, participantId) == Window.Unavailable) {
            throw CollectionUnavailableException(CollectionUnavailableException.Scope.PARTICIPANT)
        }
    }

    /** The windows the phone's status poll publishes, read without row locks. */
    fun captureWindows(studyId: UUID, participantId: String): List<CaptureWindow> =
        loadState(studyId, participantId)?.captureWindows().orEmpty()

    /**
     * Survey admission for days the participant was enrolled, whatever the current status. The daily app-usage survey
     * is for shared devices only: a study that does not collect user identification has no survey.
     * Refuses an erased, withdrawn, not-enrolled, processing-blocked or required-module-declined participant and a
     * study scheduled for deletion.
     */
    fun surveyEnrollment(studyId: UUID, participantId: String): SurveyEnrollment? {
        val until = when (val window = loadLockedWindow(studyId, participantId)) {
            is Window.Open -> null to window.closed
            is Window.ClosedAt -> window.cutoff to window.closed
            Window.Unavailable -> return null
        }
        // A standing required decline is a withdrawal: no survey either.
        if (consentHalted(studyId, participantId)) return null
        // Read after the window, which takes the deletion fence and study lock first.
        val sharedDevices = CollectionSettingsRepository(connection).latest(studyId)
            ?.effectiveModules()?.get(CollectionModuleId.USER_IDENTIFICATION)?.enabled == true
        if (!sharedDevices) return null
        // The cancel record is admin-only; the query names its own study and participant.
        return RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
            connection.prepareStatement(SURVEY_ENROLLMENT_QUERY).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.setObject(3, studyId)
                statement.setString(4, participantId)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    val from = rows.getObject("enrolled_from", OffsetDateTime::class.java)?.toInstant()
                    if (rows.getBoolean("withdrawn")) null else SurveyEnrollment(from, until.first, until.second)
                }
            }
        }
    }

    /** One admission rule per form: app-usage days outlive the stop of collection, every other form does not. */
    fun admitsForm(kind: ParticipantFormKind, studyId: UUID, participantId: String): Boolean =
        if (kind == ParticipantFormKind.APP_USAGE) surveyEnrollment(studyId, participantId) != null
        else isActive(studyId, participantId)

    private fun loadLockedWindow(studyId: UUID, participantId: String?): Window {
        if (lockRows) DeletionStudyFence.shared(connection, studyId)
        return loadState(studyId, participantId, lockRows)?.window(participantId != null) ?: Window.Unavailable
    }

    private fun loadState(studyId: UUID, participantId: String?, lock: Boolean = false): State? =
        connection.prepareStatement(if (lock) WINDOW_QUERY else windowQuery(false)).use { statement ->
            statement.setString(1, participantId)
            statement.setObject(2, studyId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else State(
                    StudyLifecycleStatus.valueOf(rows.getString("lifecycle_status")),
                    rows.getString("participation_status")?.let(ParticipationStatus::valueOf),
                    rows.getObject("collection_closed_at", OffsetDateTime::class.java)?.toInstant(),
                    rows.getObject("capture_cutoff", OffsetDateTime::class.java)?.toInstant(),
                    rows.getObject("processing_blocked_at", OffsetDateTime::class.java)?.toInstant(),
                    instants(rows, "closed_from").zip(instants(rows, "closed_until")) { from, until -> from..<until },
                )
            }
        }

    /**
     * The participant's newest live enrollment key decides, with the same device-scoped decision order uploads and
     * reminders use. The read shares the caller's transaction with its form write, and an acknowledgment never
     * reads form rows, so a shared study lock suffices; an exclusive one deadlocked code issuance against code exchange.
     */
    private fun consentHalted(studyId: UUID, participantId: String): Boolean =
        consentState(studyId, participantId)?.declinedRequiredModules?.isNotEmpty() == true

    /** What those decisions leave for the participants page; null before any device recorded a decision. */
    fun consentState(studyId: UUID, participantId: String): ParticipantConsentState? {
        val repository = CollectionSettingsRepository(connection)
        val latest = repository.latest(studyId) ?: return null
        val history = CollectionDecisionRepository(connection).history(studyId, participantId)
        if (history.isEmpty()) return null
        return participantConsentState(latest, repository.through(studyId, latest.settingsVersion), history.current())
    }

    private fun instants(rows: ResultSet, column: String): List<Instant> =
        (rows.getArray(column).array as Array<*>).map { (it as Timestamp).toInstant() }

    fun requireActive(studyId: UUID, participantId: String? = null) {
        if (!isActive(studyId, participantId)) throw CollectionUnavailableException(
            if (participantId == null) CollectionUnavailableException.Scope.STUDY else CollectionUnavailableException.Scope.PARTICIPANT,
        )
    }
}
