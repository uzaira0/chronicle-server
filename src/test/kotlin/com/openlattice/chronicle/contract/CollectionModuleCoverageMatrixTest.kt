package com.openlattice.chronicle.contract

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.study.ParticipantDataType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.filter.AnnotationTypeFilter

// =============================================================================
// The matrix covers every active CollectionModuleId and enforces four contracts:
//
//   Test A (CollectionModuleCoverageMatrixTest)   — static completeness:
//       active enum <-> matrix is exactly 1:1; retired/reserved ids never
//       appear. Adding or retiring a module fails this test until the matrix
//       row is added/removed.
//   Test B (CollectionModuleCoverageMatrixTest)   — handler existence/wiring:
//       every row's handler class loads on the classpath, is registered in
//       a services pod (when it is a Spring bean), and its
//       controller entry points exist in StudyController / StudyV4Controller /
//       SurveyController source.
//   Test C (CollectionModuleCoverageMatrixDbTest) — DB reality, Testcontainers:
//       framework tables + the full db/migration corpus are applied to a real
//       Percona PG 17.5 (prod image) and each row's table, scoping columns, timestamp columns,
//       idempotency constraint (pg_constraint) and RLS state (pg_class /
//       pg_policies) are asserted.
//   Test D (CollectionModuleCoverageMatrixTest)   — export eligibility:
//       the ParticipantDataType export lanes (StudyController export -> DataDownload*)
//       are pinned to their physical tables, and each row's exportEligible
//       flag must equal "its table is served by an export lane".
//
// The matrix values were derived by reading the upload services
// (services/upload/*), StudyController / StudyV4Controller / SurveyController,
// ChroniclePostgresTables / PostgresEventTables, the movers
// (MoveAndroidSensorDataToStorageTask, MoveToEventStorageTask) and the Flyway corpus under
// src/main/resources/db/migration — not guessed.
// =============================================================================

/** How duplicate ingestion is prevented for a module's table. */
internal sealed class Idempotency {
    /** A UNIQUE / PRIMARY KEY constraint on exactly [columns] (asserted against pg_constraint). */
    data class UniqueKey(val columns: Set<String>, val description: String) : Idempotency()

    /**
     * No unique constraint by design; dedup is logical. Test C asserts the table
     * really has NO pk/unique constraint, so silently adding one (or relying on a
     * phantom one) breaks the contract loudly.
     */
    data class Logical(val reason: String) : Idempotency()
}

/** RLS expectation for a module's table. */
internal sealed class RlsExpectation {
    /** RLS must be ENABLEd + FORCEd and [policyName] must exist in pg_policies. */
    data class Policy(val policyName: String) : RlsExpectation()

    /** Documented not-applicable reason (only legal for rows without a table). */
    data class NotApplicable(val reason: String) : RlsExpectation()
}

/** One row of the required module contract matrix. */
internal data class ModuleContractRow(
    val module: CollectionModuleId,
    /** FQCN of the ingesting handler; null only for documented no-upload modules. */
    val handlerClass: String?,
    /** Is the handler a bean declared by a services pod (vs a Kotlin object / controller)? */
    val handlerIsPodBean: Boolean,
    /** Ingesting method that must exist on StudyController (or SurveyController). */
    val controllerMethod: String?,
    /** Controller class (FQCN) that must declare [controllerMethod]. */
    val controllerClass: String,
    /** V4 endpoint method that must exist on StudyV4Controller; null when the surface is not V4. */
    val v4EndpointMethod: String?,
    /** Human-readable upload endpoint (documentation field). */
    val endpoint: String,
    /** Physical table; null only with [noTableReason]. */
    val table: String?,
    val noTableReason: String? = null,
    val idempotency: Idempotency?,
    val studyScopeColumn: String?,
    val participantScopeColumn: String?,
    val deviceScopeColumn: String? = null,
    val eventTimestampColumn: String?,
    /** Ingestion (server-arrival) timestamp column; null with [ingestionTimestampNote]. */
    val ingestionTimestampColumn: String?,
    val ingestionTimestampNote: String? = null,
    /** Extra module-specific columns that must exist (e.g. in_app_activity_class's payload column). */
    val extraColumns: Set<String> = emptySet(),
    val rls: RlsExpectation,
    val exportEligible: Boolean,
    val exportNote: String,
)

internal object CollectionModuleContractMatrix {

    // Shared row shapes -------------------------------------------------------

    /** The (study_id, participant_id, event_id) per-event dedup key used by the ten V31–V43 tables. */
    private val EVENT_PK = Idempotency.UniqueKey(
        setOf("study_id", "participant_id", "event_id"),
        "PRIMARY KEY (study_id, participant_id, event_id); upload uses ON CONFLICT ... DO NOTHING",
    )

    private fun eventTableRow(
        module: CollectionModuleId,
        handlerSimpleName: String,
        controllerMethod: String,
        v4Method: String,
        endpointSuffix: String,
        table: String,
    ): ModuleContractRow = ModuleContractRow(
        module = module,
        handlerClass = "com.openlattice.chronicle.services.upload.$handlerSimpleName",
        handlerIsPodBean = true,
        controllerMethod = controllerMethod,
        controllerClass = STUDY_CONTROLLER,
        v4EndpointMethod = v4Method,
        endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android$endpointSuffix",
        table = table,
        idempotency = EVENT_PK,
        studyScopeColumn = "study_id",
        participantScopeColumn = "participant_id",
        eventTimestampColumn = "sample_timestamp",
        ingestionTimestampColumn = "uploaded_at",
        rls = RlsExpectation.Policy("study_isolation_$table"),
        exportEligible = true,
        exportNote = "ParticipantDataType.${module.exportLaneName()} lane exports $table through the " +
            "generic study/participant/time-bounded collection-table download path.",
    )

    /**
     * The 12 active per-sensor hardware modules.
     * All land directly in android_sensor_data through AndroidSensorDataUploadService, with
     * idempotent ON CONFLICT (sample_id) DO NOTHING semantics against the PK.
     */
    private val ANDROID_SENSOR_MODULES = listOf(
        CollectionModuleId.SENSOR_ACCELEROMETER,
        CollectionModuleId.SENSOR_GYROSCOPE,
        CollectionModuleId.SENSOR_MAGNETOMETER,
        CollectionModuleId.SENSOR_GRAVITY,
        CollectionModuleId.SENSOR_LINEAR_ACCELERATION,
        CollectionModuleId.SENSOR_ROTATION_VECTOR,
        CollectionModuleId.SENSOR_STEP_COUNTER,
        CollectionModuleId.SENSOR_LIGHT,
        CollectionModuleId.SENSOR_PROXIMITY,
        CollectionModuleId.SENSOR_SIGNIFICANT_MOTION,
        CollectionModuleId.SENSOR_TILT_DETECTOR,
        CollectionModuleId.SENSOR_SCREEN_ORIENTATION,
    )

    private fun androidSensorRow(module: CollectionModuleId): ModuleContractRow = ModuleContractRow(
        module = module,
        handlerClass = "com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService",
        handlerIsPodBean = true,
        controllerMethod = "uploadAndroidSensorDataV4",
        controllerClass = STUDY_CONTROLLER,
        v4EndpointMethod = "uploadAndroidSensorDataV4",
        endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android/sensors",
        table = "android_sensor_data",
        idempotency = Idempotency.UniqueKey(
            setOf("sample_id"),
            "PRIMARY KEY (sample_id); AndroidSensorDataUploadService inserts with " +
                "ON CONFLICT (sample_id) DO NOTHING",
        ),
        studyScopeColumn = "study_id",
        participantScopeColumn = "participant_id",
        deviceScopeColumn = "random_device_id",
        eventTimestampColumn = "sample_timestamp",
        ingestionTimestampColumn = null,
        ingestionTimestampNote = "Direct ingestion commits the final table synchronously; " +
            "android_sensor_data intentionally carries no uploaded_at column.",
        rls = RlsExpectation.Policy("study_isolation_android_sensor_data"),
        exportEligible = true,
        exportNote = "ParticipantDataType.AndroidSensor lane (StudyController export -> " +
            "DataDownloadService.getParticipantsAndroidSensorData).",
    )

    // Compiled classes asserted by Test B (never their source text) -------------

    const val STUDY_CONTROLLER = "com.openlattice.chronicle.controllers.StudyController"
    const val STUDY_V4_CONTROLLER = "com.openlattice.chronicle.controllers.StudyV4Controller"
    const val SURVEY_CONTROLLER = "com.openlattice.chronicle.controllers.SurveyController"
    // Bean registration is checked from compiled pod declarations below.
    const val DOWNLOAD_SERVICE = "com.openlattice.chronicle.services.download.DataDownloadService"

    // The matrix --------------------------------------------------------------

    val rows: List<ModuleContractRow> = listOf(

        // ---- usage-event family: three modules share chronicle_usage_events ----
        ModuleContractRow(
            module = CollectionModuleId.USAGE_EVENTS,
            handlerClass = "com.openlattice.chronicle.services.upload.AppDataUploadService",
            handlerIsPodBean = true,
            controllerMethod = "uploadAndroidUsageEventDataV4",
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = "uploadAndroidUsageEventDataV4",
            endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android",
            table = "chronicle_usage_events",
            idempotency = Idempotency.UniqueKey(setOf("study_id", "participant_id", "client_event_id"),
                "A retried upload stores each phone-assigned client event ID once (ON CONFLICT DO NOTHING); " +
                    "the scheduled dedup pass still merges rows that repeat the same content.",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            eventTimestampColumn = "event_timestamp",
            ingestionTimestampColumn = "uploaded_at",
            rls = RlsExpectation.Policy("study_isolation_usage_events"),
            exportEligible = true,
            exportNote = "ParticipantDataType.UsageEvents lane (StudyController export -> " +
                "DataDownloadService.getParticipantsUsageEventsData).",
        ),
        ModuleContractRow(
            module = CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            // Shared-row module: the foreground Activity class rides each usage event as the
            // activity_class column; there is no separate payload, handler, or table.
            handlerClass = "com.openlattice.chronicle.services.upload.AppDataUploadService",
            handlerIsPodBean = true,
            controllerMethod = "uploadAndroidUsageEventDataV4",
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = "uploadAndroidUsageEventDataV4",
            endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android " +
                "(activity_class field of each usage event; stripped on-device when the module is off)",
            table = "chronicle_usage_events",
            idempotency = Idempotency.UniqueKey(setOf("study_id", "participant_id", "client_event_id"),
                "Shares the usage-events row and its client event ID index.",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            eventTimestampColumn = "event_timestamp",
            ingestionTimestampColumn = "uploaded_at",
            extraColumns = setOf("activity_class"),
            rls = RlsExpectation.Policy("study_isolation_usage_events"),
            exportEligible = true,
            exportNote = "Exported with the UsageEvents lane; DataDownloadService includes the " +
                "activity_class column in the usage-events download projection.",
        ),
        ModuleContractRow(
            module = CollectionModuleId.DEVICE_LIFECYCLE,
            // Shared-row module: DeviceLifecycleCollectionModule (Android) writes lifecycle
            // events into the same ChronicleDb upload queue as usage events; they arrive in the
            // ChronicleData payload of the usage-event endpoint and land in chronicle_usage_events
            // distinguished by event type.
            handlerClass = "com.openlattice.chronicle.services.upload.AppDataUploadService",
            handlerIsPodBean = true,
            controllerMethod = "uploadAndroidUsageEventDataV4",
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = "uploadAndroidUsageEventDataV4",
            endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android " +
                "(lifecycle events ride the ChronicleData usage-event payload)",
            table = "chronicle_usage_events",
            idempotency = Idempotency.UniqueKey(setOf("study_id", "participant_id", "client_event_id"),
                "Shares the usage-events row and its client event ID index " +
                    "(plus on-device dedup via PrefsLifecycleDedupeStore).",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            eventTimestampColumn = "event_timestamp",
            ingestionTimestampColumn = "uploaded_at",
            rls = RlsExpectation.Policy("study_isolation_usage_events"),
            exportEligible = true,
            exportNote = "Exported with the UsageEvents lane (lifecycle rows are usage-event rows).",
        ),

        ModuleContractRow(
            module = CollectionModuleId.USER_IDENTIFICATION,
            // Shared-row module: the phone's current device user (participant or someone else)
            // rides each usage event as its username column; there is no separate payload or table.
            handlerClass = "com.openlattice.chronicle.services.upload.AppDataUploadService",
            handlerIsPodBean = true,
            controllerMethod = "uploadAndroidUsageEventDataV4",
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = "uploadAndroidUsageEventDataV4",
            endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android " +
                "(username field of each usage event)",
            table = "chronicle_usage_events",
            idempotency = Idempotency.UniqueKey(setOf("study_id", "participant_id", "client_event_id"),
                "Shares the usage-events row and its client event ID index.",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            eventTimestampColumn = "event_timestamp",
            ingestionTimestampColumn = "uploaded_at",
            extraColumns = setOf("username"),
            rls = RlsExpectation.Policy("study_isolation_usage_events"),
            exportEligible = true,
            exportNote = "Exported with the UsageEvents lane; the username column is part of the usage-events download.",
        ),

        // ---- documented no-upload exception ----
        ModuleContractRow(
            module = CollectionModuleId.UPLOAD_TELEMETRY,
            handlerClass = null,
            handlerIsPodBean = false,
            controllerMethod = null,
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = null,
            endpoint = "none",
            table = null,
            noTableReason = "Device-local, read-only diagnostics module (OPERATIONAL_DIAGNOSTICS): " +
                "the Android app only records the on-device upload subsystem and renders " +
                "redaction-safe telemetry in the app. It uploads no payload, so there is no endpoint, " +
                "handler, table, RLS policy, or export lane.",
            idempotency = null,
            studyScopeColumn = null,
            participantScopeColumn = null,
            eventTimestampColumn = null,
            ingestionTimestampColumn = null,
            rls = RlsExpectation.NotApplicable("No server-side payload or table (device-local diagnostics)."),
            exportEligible = false,
            exportNote = "No server-side data to export.",
        ),

        // ---- snapshot upsert ----
        ModuleContractRow(
            module = CollectionModuleId.SENSOR_AVAILABILITY,
            // Ingested directly by StudyController.reportAndroidSensorAvailability via
            // UPSERT_SENSOR_AVAILABILITY_SQL (no services.upload class exists for it).
            handlerClass = "com.openlattice.chronicle.controllers.StudyController",
            handlerIsPodBean = false,
            controllerMethod = "reportAndroidSensorAvailability",
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = "reportAndroidSensorAvailabilityV4",
            endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android/sensors/availability",
            table = "android_device_sensor_availability",
            idempotency = Idempotency.UniqueKey(
                setOf("study_id", "participant_id", "random_device_id"),
                "PRIMARY KEY (study_id, participant_id, random_device_id); latest-snapshot upsert via " +
                    "ON CONFLICT ... DO UPDATE (overwriteOnConflict)",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            deviceScopeColumn = "random_device_id",
            eventTimestampColumn = "reported_at",
            ingestionTimestampColumn = "reported_at",
            ingestionTimestampNote = "Snapshot table: reported_at is set to now() at ingestion and " +
                "doubles as the event time of the latest report.",
            rls = RlsExpectation.Policy("study_isolation_android_device_sensor_availability"),
            exportEligible = true,
            exportNote = "ParticipantDataType.SensorAvailability exports the latest device-capability snapshot.",
        ),

        // ---- questionnaire ----
        ModuleContractRow(
            module = CollectionModuleId.QUESTIONNAIRE,
            handlerClass = "com.openlattice.chronicle.services.surveys.SurveysService",
            handlerIsPodBean = true,
            controllerMethod = "submitQuestionnaireResponses",
            controllerClass = SURVEY_CONTROLLER,
            v4EndpointMethod = null,
            endpoint = "POST /v3/survey/{studyId}/participant/{participantId}/questionnaire/{questionnaireId}",
            table = "questionnaire_submissions",
            idempotency = Idempotency.UniqueKey(
                setOf("submission_id", "question_title"),
                "PRIMARY KEY (submission_id, question_title); duplicate submissions are rejected by the " +
                    "PK (SurveysService inserts without ON CONFLICT).",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            eventTimestampColumn = "completed_at",
            ingestionTimestampColumn = null,
            ingestionTimestampNote = "questionnaire_submissions carries only the participant-supplied " +
                "completed_at; there is no server-arrival column.",
            extraColumns = setOf("questionnaire_id"),
            rls = RlsExpectation.Policy("study_isolation_questionnaire_submissions"),
            exportEligible = false,
            exportNote = "Questionnaire responses are served by the survey APIs " +
                "(SurveyController.getQuestionnaireResponses), not the ParticipantDataType export lanes.",
        ),

        // ---- battery ----
        ModuleContractRow(
            module = CollectionModuleId.BATTERY_TELEMETRY,
            handlerClass = "com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService",
            handlerIsPodBean = true,
            controllerMethod = "uploadBatteryTelemetryV4",
            controllerClass = STUDY_CONTROLLER,
            v4EndpointMethod = "uploadBatteryTelemetryV4",
            endpoint = "POST /v4/study/{studyId}/participant/{participantId}/android/battery",
            table = "battery_telemetry",
            idempotency = Idempotency.UniqueKey(
                setOf("study_id", "participant_id", "sample_id"),
                "PRIMARY KEY (study_id, participant_id, sample_id); upload uses ON CONFLICT ... DO NOTHING",
            ),
            studyScopeColumn = "study_id",
            participantScopeColumn = "participant_id",
            eventTimestampColumn = "sample_timestamp",
            ingestionTimestampColumn = "uploaded_at",
            rls = RlsExpectation.Policy("study_isolation_battery_telemetry"),
            exportEligible = true,
            exportNote = "ParticipantDataType.BatteryTelemetry exports battery_telemetry.",
        ),
    ) +
        // ---- 12 per-sensor hardware modules -> android_sensor_data ----
        ANDROID_SENSOR_MODULES.map(::androidSensorRow) +
        listOf(
            // ---- the ten dedicated (study_id, participant_id, event_id) collection tables ----
            eventTableRow(
                CollectionModuleId.INTERACTION_EVENTS, "InteractionEventsUploadService",
                "uploadInteractionEventsV4", "uploadInteractionEventsV4", "/interaction", "interaction_events",
            ),
            eventTableRow(
                CollectionModuleId.AUDIO_ACTIVITY, "AppAudioActivityUploadService",
                "uploadAudioActivity", "uploadAudioActivityV4", "/audio-activity", "app_audio_activity",
            ),
            eventTableRow(
                CollectionModuleId.AUDIO_CONTENT, "AppAudioContentUploadService",
                "uploadAudioContent", "uploadAudioContentV4", "/audio-content", "app_audio_content",
            ),
            eventTableRow(
                CollectionModuleId.NOTIFICATION_ACTIVITY, "NotificationActivityUploadService",
                "uploadNotificationActivity", "uploadNotificationActivityV4", "/notification-activity",
                "notification_activity",
            ),
            eventTableRow(
                CollectionModuleId.SLEEP, "SleepEventsUploadService",
                "uploadSleepEvents", "uploadSleepEventsV4", "/sleep", "sleep_events",
            ),
            eventTableRow(
                CollectionModuleId.ACTIVITY_RECOGNITION, "ActivityRecognitionEventsUploadService",
                "uploadActivityRecognitionEvents", "uploadActivityRecognitionEventsV4",
                "/activity-recognition", "activity_recognition_events",
            ),
            eventTableRow(
                CollectionModuleId.HEALTH_CONNECT, "HealthMetricsUploadService",
                "uploadHealthMetrics", "uploadHealthMetricsV4", "/health-connect", "health_metrics",
            ),
            eventTableRow(
                CollectionModuleId.CONNECTIVITY_STATE, "ConnectivityStateEventsUploadService",
                "uploadConnectivityStateEvents", "uploadConnectivityStateEventsV4", "/connectivity-state",
                "connectivity_state_events",
            ),
            eventTableRow(
                CollectionModuleId.APP_NETWORK_USAGE, "AppNetworkUsageUploadService",
                "uploadAppNetworkUsage", "uploadAppNetworkUsageV4", "/app-network-usage", "app_network_usage",
            ),
            eventTableRow(
                CollectionModuleId.DEVICE_SETTINGS, "DeviceSettingsUploadService",
                "uploadDeviceSettings", "uploadDeviceSettingsV4", "/device-settings", "device_settings",
            ),

        )

    /**
     * The export lanes as implemented today: ParticipantDataType -> physical table
     * (the StudyController export's exhaustive `when` dispatches each lane to DataDownloadService).
     * Test D pins this map against the enum, then derives every row's exportEligible from it.
     */
    val exportLaneToTable: Map<String, String> = mapOf(
        "UsageEvents" to "chronicle_usage_events",
        "AppUsageSurvey" to "app_usage_survey",
        "IOSScreenTime" to "ios_screen_time_usage",
        "AndroidSensor" to "android_sensor_data",
        "SensorAvailability" to "android_device_sensor_availability",
        "BatteryTelemetry" to "battery_telemetry",
        "InteractionEvents" to "interaction_events",
        "AudioActivity" to "app_audio_activity",
        "AudioContent" to "app_audio_content",
        "NotificationActivity" to "notification_activity",
        "SleepEvents" to "sleep_events",
        "ActivityRecognition" to "activity_recognition_events",
        "HealthMetrics" to "health_metrics",
        "ConnectivityState" to "connectivity_state_events",
        "AppNetworkUsage" to "app_network_usage",
        "DeviceSettings" to "device_settings",
        "UploadDiagnostics" to "upload_diagnostics",
    )

    /** Public method names the compiled class declares or inherits. */
    fun methodNames(className: String): Set<String> = Class.forName(className).methods.mapTo(mutableSetOf()) { it.name }
}

// =============================================================================
// Tests A, B, D — static (no database required).
// =============================================================================
class CollectionModuleCoverageMatrixTest {

    private val rows = CollectionModuleContractMatrix.rows

    // ---- Test A: static completeness ----------------------------------------

    @Test
    fun `every active module appears exactly once in the matrix`() {
        val active = CollectionModuleId.activeModules
        val declared = rows.map { it.module }

        val duplicates = declared.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            "Matrix declares duplicate rows for: ${duplicates.joinToString { it.id }}",
            duplicates.isEmpty(),
        )

        val missing = active - declared.toSet()
        val stale = declared.toSet() - active
        assertTrue(
            buildString {
                if (missing.isNotEmpty()) {
                    append(
                        "ACTIVE modules missing a contract-matrix row (a new module was activated — add its " +
                            "handler/table/idempotency/RLS/export row here AND satisfy Tests B–D): " +
                            missing.joinToString { it.id },
                    )
                }
                if (stale.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(
                        "Matrix rows for modules that are NOT active (retired/reserved — remove or the module " +
                            "was deactivated without cleaning the matrix): " + stale.joinToString { it.id },
                    )
                }
            },
            missing.isEmpty() && stale.isEmpty(),
        )
    }

    @Test
    fun `no inactive or retired module appears in the matrix`() {
        val inactive = CollectionModuleId.entries.filterNot { it.active }.toSet()
        val offenders = rows.map { it.module }.filter { it in inactive }
        assertTrue(
            "Retired/reserved module ids must not have active matrix rows: " +
                offenders.joinToString { it.id },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `documented exceptions are actually documented`() {
        for (row in rows) {
            if (row.table == null) {
                assertTrue(
                    "${row.module.id}: rows without a table need a non-blank noTableReason",
                    !row.noTableReason.isNullOrBlank(),
                )
                assertTrue(
                    "${row.module.id}: no-table rows must document RLS as NotApplicable",
                    row.rls is RlsExpectation.NotApplicable,
                )
            } else {
                assertTrue(
                    "${row.module.id}: table-backed rows must declare idempotency",
                    row.idempotency != null,
                )
                assertTrue(
                    "${row.module.id}: table-backed rows must declare an RLS policy " +
                        "(participant/study-scoped data is never exempt)",
                    row.rls is RlsExpectation.Policy,
                )
                assertTrue(
                    "${row.module.id}: table-backed rows must declare study + participant scope columns",
                    row.studyScopeColumn != null && row.participantScopeColumn != null,
                )
                assertTrue(
                    "${row.module.id}: table-backed rows must declare an event timestamp column",
                    row.eventTimestampColumn != null,
                )
                assertTrue(
                    "${row.module.id}: missing ingestion timestamp column requires a documented note",
                    row.ingestionTimestampColumn != null || !row.ingestionTimestampNote.isNullOrBlank(),
                )
            }
            assertTrue(
                "${row.module.id}: exportNote must always be documented",
                row.exportNote.isNotBlank(),
            )
        }
    }

    // ---- Test B: handler existence + wiring ----------------------------------

    @Test
    fun `every matrix handler class exists on the classpath`() {
        for (row in rows) {
            val handler = row.handlerClass ?: continue
            try {
                Class.forName(handler)
            } catch (e: ClassNotFoundException) {
                throw AssertionError(
                    "${row.module.id}: handler class $handler not found on the classpath", e,
                )
            }
        }
    }

    @Test
    fun `every pod-bean handler has exactly one compiled bean declaration`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Configuration::class.java))
        val beanMethods = scanner.findCandidateComponents("com.openlattice.chronicle.pods")
            .flatMap { candidate ->
                Class.forName(requireNotNull(candidate.beanClassName)).declaredMethods.toList()
            }
            .filter { it.isAnnotationPresent(Bean::class.java) }
        assertTrue("No compiled pod beans discovered", beanMethods.isNotEmpty())
        for (row in rows.filter { it.handlerIsPodBean }) {
            val handler = Class.forName(requireNotNull(row.handlerClass))
            val declarations = beanMethods.filter { it.returnType.isAssignableFrom(handler) }
            assertEquals(
                "${row.module.id}: ${handler.simpleName} must have exactly one pod bean declaration",
                1,
                declarations.size,
            )
        }
    }

    @Test
    fun `every matrix controller method exists on its compiled controller`() {
        for (row in rows) {
            row.controllerMethod?.let { method ->
                assertTrue(
                    "${row.module.id}: $method is not a public method of ${row.controllerClass}",
                    method in CollectionModuleContractMatrix.methodNames(row.controllerClass),
                )
            }
            row.v4EndpointMethod?.let { v4 ->
                assertTrue(
                    "${row.module.id}: $v4 is not a public method of StudyV4Controller " +
                        "(V4 upload surface is missing or was renamed without updating the matrix)",
                    v4 in CollectionModuleContractMatrix.methodNames(CollectionModuleContractMatrix.STUDY_V4_CONTROLLER),
                )
            }
        }
    }

    @Test
    fun `controllers are constructed with the handler that ingests their modules`() {
        for (row in rows) {
            val handler = Class.forName(row.handlerClass ?: continue)
            val controller = Class.forName(row.controllerClass)
            // StudyController is itself the handler for sensor_availability.
            if (controller == handler) continue
            assertTrue(
                "${row.module.id}: no ${controller.simpleName} constructor takes a ${handler.simpleName} — the handler " +
                    "exists but Spring never gives it to the controller",
                controller.constructors.any { constructor -> constructor.parameterTypes.any { it.isAssignableFrom(handler) } },
            )
        }
    }

    // ---- Test D: export eligibility ------------------------------------------

    @Test
    fun `participant data type lanes match the declared lane-table map`() {
        // If a new ParticipantDataType lane is added (or one is removed) this fails, forcing the
        // exportLaneToTable map — and every row's exportEligible flag — to be revisited.
        assertEquals(
            "ParticipantDataType enum drifted from the matrix's export-lane map — update " +
                "CollectionModuleContractMatrix.exportLaneToTable and re-derive exportEligible flags",
            CollectionModuleContractMatrix.exportLaneToTable.keys,
            ParticipantDataType.entries.map { it.name }.toSet(),
        )

        // Pin each lane's table-definition symbol to its physical table name at runtime, so the
        // lane->table map cannot silently rot if a table is renamed.
        assertEquals("chronicle_usage_events", PostgresEventTables.CHRONICLE_USAGE_EVENTS.name)
        assertEquals("ios_screen_time_usage", ChroniclePostgresTables.IOS_SCREEN_TIME_USAGE.name)
        assertEquals("app_usage_survey", ChroniclePostgresTables.APP_USAGE_SURVEY.name)
        assertEquals("android_sensor_data", ChroniclePostgresTables.ANDROID_SENSOR_DATA.name)
    }

    @Test
    fun `export eligibility of every module equals export-lane coverage of its table`() {
        val laneTables = CollectionModuleContractMatrix.exportLaneToTable.values.toSet()
        val failures = mutableListOf<String>()
        for (row in rows) {
            val covered = row.table != null && row.table in laneTables
            if (covered != row.exportEligible) {
                failures += "${row.module.id}: table=${row.table} laneCovered=$covered but " +
                    "exportEligible=${row.exportEligible} (${row.exportNote})"
            }
        }
        assertTrue(
            "Export-eligibility drift between the matrix and the export lanes:\n  " +
                failures.joinToString("\n  "),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every module with server data has a researcher download path`() {
        val unsupported = rows.filterNot { row ->
            row.exportEligible ||
                row.module == CollectionModuleId.QUESTIONNAIRE ||
                (row.module == CollectionModuleId.UPLOAD_TELEMETRY && row.table == null)
        }
        assertTrue(
            "Modules silently collect data without a researcher download path: " +
                unsupported.joinToString { "${it.module.id}:${it.table}" },
            unsupported.isEmpty(),
        )

        assertTrue(
            "Questionnaire is the only Play table outside ParticipantDataType and must retain its dedicated download",
            "getQuestionnaireResponses" in CollectionModuleContractMatrix.methodNames(CollectionModuleContractMatrix.DOWNLOAD_SERVICE),
        )
    }
}

private fun CollectionModuleId.exportLaneName(): String = when (this) {
    CollectionModuleId.INTERACTION_EVENTS -> "InteractionEvents"
    CollectionModuleId.AUDIO_ACTIVITY -> "AudioActivity"
    CollectionModuleId.AUDIO_CONTENT -> "AudioContent"
    CollectionModuleId.NOTIFICATION_ACTIVITY -> "NotificationActivity"
    CollectionModuleId.SLEEP -> "SleepEvents"
    CollectionModuleId.ACTIVITY_RECOGNITION -> "ActivityRecognition"
    CollectionModuleId.HEALTH_CONNECT -> "HealthMetrics"
    CollectionModuleId.CONNECTIVITY_STATE -> "ConnectivityState"
    CollectionModuleId.APP_NETWORK_USAGE -> "AppNetworkUsage"
    CollectionModuleId.DEVICE_SETTINGS -> "DeviceSettings"
    else -> error("No dedicated export lane for $id")
}

// =============================================================================
// Test C — DB reality against a real PostgreSQL with the framework schema plus
// the full Flyway corpus applied (mirrors FlywayMigrationCorpusTest's approach of
// creating the ChroniclePostgresTables/PostgresEventTables base schema first,
// then executing the production SQL verbatim off the migration corpus).
// =============================================================================
class CollectionModuleCoverageMatrixDbTest {

    companion object {
        private lateinit var hds: HikariDataSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            // Shared bootstrapped container (framework schema + chronicle role + full
            // Flyway corpus): see ChronicleContractTestSchema.sharedPostgres. This suite
            // only reads pg_catalog/information_schema, so sharing with the ingestion
            // suite is order-independent.
            val postgres = ChronicleContractTestSchema.sharedPostgres
            hds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            })
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            // Only the pool is ours; the shared container is reaped by Ryuk at JVM exit.
            if (::hds.isInitialized) hds.close()
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T = hds.connection.use(block)

    private fun tableExists(table: String): Boolean = withConnection { c ->
        c.prepareStatement("SELECT 1 FROM pg_class WHERE relname = ? AND relkind = 'r'").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { it.next() }
        }
    }

    private fun columnNames(table: String): Set<String> = withConnection { c ->
        c.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                val out = mutableSetOf<String>()
                while (rs.next()) out += rs.getString(1)
                out
            }
        }
    }

    /** All PRIMARY KEY / UNIQUE constraints on [table] as sets of column names. */
    private fun uniqueConstraintColumnSets(table: String): List<Set<String>> = withConnection { c ->
        c.prepareStatement(
            """
            SELECT c.conname, array_agg(a.attname) AS cols
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN unnest(c.conkey) AS k(attnum) ON TRUE
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE t.relname = ? AND c.contype IN ('p', 'u')
            GROUP BY c.conname
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Set<String>>()
                while (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val cols = (rs.getArray("cols").array as Array<String>).toSet()
                    out += cols
                }
                out
            }
        }
    }

    /** Also accept a plain UNIQUE INDEX (not constraint-backed) as an idempotency target. */
    private fun uniqueIndexColumnSets(table: String): List<Set<String>> = withConnection { c ->
        c.prepareStatement(
            """
            SELECT i.indexrelid::regclass::text AS idx, array_agg(a.attname) AS cols
            FROM pg_index i
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN unnest(i.indkey) AS k(attnum) ON TRUE
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE t.relname = ? AND i.indisunique
            GROUP BY i.indexrelid
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Set<String>>()
                while (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    out += (rs.getArray("cols").array as Array<String>).toSet()
                }
                out
            }
        }
    }

    private data class RlsState(val enabled: Boolean, val forced: Boolean, val policies: Set<String>)

    private fun rlsState(table: String): RlsState = withConnection { c ->
        val (enabled, forced) = c.prepareStatement(
            "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?",
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                assertTrue("$table missing from pg_class", rs.next())
                rs.getBoolean(1) to rs.getBoolean(2)
            }
        }
        val policies = c.prepareStatement(
            "SELECT policyname FROM pg_policies WHERE tablename = ?",
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                val out = mutableSetOf<String>()
                while (rs.next()) out += rs.getString(1)
                out
            }
        }
        RlsState(enabled, forced, policies)
    }

    private val tableRows = CollectionModuleContractMatrix.rows.filter { it.table != null }

    @Test
    fun `every matrix table exists after framework schema plus migrations`() {
        val missing = tableRows.map { it.table!! }.distinct().filterNot { tableExists(it) }
        assertTrue(
            "Matrix tables missing from the migrated database: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `scoping and timestamp columns exist for every matrix row`() {
        val failures = mutableListOf<String>()
        for (row in tableRows) {
            val cols = columnNames(row.table!!)
            val expected = listOfNotNull(
                row.studyScopeColumn,
                row.participantScopeColumn,
                row.deviceScopeColumn,
                row.eventTimestampColumn,
                row.ingestionTimestampColumn,
            ) + row.extraColumns
            for (col in expected) {
                if (col !in cols) failures += "${row.module.id}: ${row.table}.$col missing (has $cols)"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `idempotency constraints match the matrix`() {
        val failures = mutableListOf<String>()
        for (row in tableRows) {
            val table = row.table!!
            val constraintSets = uniqueConstraintColumnSets(table) + uniqueIndexColumnSets(table)
            when (val idem = row.idempotency) {
                is Idempotency.UniqueKey -> {
                    if (constraintSets.none { it == idem.columns }) {
                        failures += "${row.module.id}: $table has no PRIMARY KEY/UNIQUE constraint or " +
                            "unique index on ${idem.columns} (found: $constraintSets). " +
                            "Expected: ${idem.description}"
                    }
                }
                is Idempotency.Logical -> {
                    if (constraintSets.isNotEmpty()) {
                        failures += "${row.module.id}: $table is documented as LOGICAL dedup " +
                            "(${idem.reason}) but a unique constraint/index exists: $constraintSets. " +
                            "Update the matrix row to UniqueKey if this is now the dedup mechanism."
                    }
                }
                null -> failures += "${row.module.id}: table-backed row without idempotency declaration"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `rls is enabled forced and the expected policy exists for every matrix table`() {
        val failures = mutableListOf<String>()
        for (row in tableRows) {
            val policy = (row.rls as? RlsExpectation.Policy)?.policyName
            if (policy == null) {
                failures += "${row.module.id}: table-backed row must expect an RLS policy"
                continue
            }
            val state = rlsState(row.table!!)
            if (!state.enabled) failures += "${row.module.id}: RLS not ENABLEd on ${row.table}"
            if (!state.forced) failures += "${row.module.id}: RLS not FORCEd on ${row.table} (owner bypass)"
            if (policy !in state.policies) {
                failures += "${row.module.id}: policy $policy missing on ${row.table} " +
                    "(present: ${state.policies})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
