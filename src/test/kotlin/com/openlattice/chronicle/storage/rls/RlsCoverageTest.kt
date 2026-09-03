package com.openlattice.chronicle.storage.rls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard: every participant-data collection table MUST be study-isolated by a
 * Row-Level Security policy defined in the migration corpus.
 *
 * This is the automated form of the gap that shipped silently for `android_sensor_data`
 * (and `android_device_sensor_availability`): they were created by the PostgresTables
 * framework but never got an RLS policy, so they were the only collection tables without
 * DB-level study isolation. This test reads the actual `db/migration` SQL corpus and
 * asserts each required table has BOTH `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY` and a
 * `CREATE POLICY study_isolation_* ON <t>`. If a new collection table is added, add it to
 * [REQUIRED_PARTICIPANT_DATA_TABLES] — and the test then forces a matching RLS migration.
 *
 * Complements [RLSStudyIsolationTest] (which proves the policy MECHANISM isolates at
 * runtime); this proves COVERAGE — that no participant-data table is left out.
 */
class RlsCoverageTest {

    private companion object {
        // Tables that hold participant / study-scoped data and therefore MUST be RLS-isolated.
        val REQUIRED_PARTICIPANT_DATA_TABLES = listOf(
            "chronicle_usage_events",
            "chronicle_usage_stats",
            "sensor_data",                          // iOS
            "android_sensor_data",
            "android_device_sensor_availability",
            "battery_telemetry",
            "interaction_events",
            "app_audio_activity",
            "app_audio_content",
            "ambient_audio_events",
            "notification_activity",
            "sleep_events",
            "activity_recognition_events",
            "health_metrics",
            "connectivity_state_events",
            "app_network_usage",
            "device_settings",
            "encrypted_payloads",
            "app_usage_survey",
            "questionnaire_submissions",
            "time_use_diary_submissions",
            "time_use_diary_summarized",
            "upload_buffer",
            "study_participants",
        )

        fun migrationCorpus(): String {
            val dir = sequenceOf(
                File("src/main/resources/db/migration"),
                File("chronicle-server/src/main/resources/db/migration"),
            ).firstOrNull { it.isDirectory }
                ?: error("Could not locate db/migration directory from cwd=${File(".").absolutePath}")
            return dir.listFiles { f -> f.extension == "sql" }
                ?.joinToString("\n") { it.readText() }
                ?: error("No migration .sql files found in ${dir.absolutePath}")
        }
    }

    @Test
    fun everyParticipantDataTableHasRlsEnabledAndAStudyIsolationPolicy() {
        val corpus = migrationCorpus()
        val missing = mutableListOf<String>()

        for (table in REQUIRED_PARTICIPANT_DATA_TABLES) {
            val enabled = corpus.contains("ALTER TABLE $table ENABLE ROW LEVEL SECURITY")
            // Policy line form: "CREATE POLICY study_isolation_<anything> ON <table>"
            val policy = Regex("CREATE POLICY study_isolation_\\w+ ON $table\\b").containsMatchIn(corpus)
            if (!enabled || !policy) {
                missing += "$table (ENABLE RLS=${enabled}, study_isolation policy=${policy})"
            }
        }

        assertTrue(
            "Participant-data tables missing DB-level study isolation (add the RLS migration, " +
                "mirroring V24/V37): \n  " + missing.joinToString("\n  "),
            missing.isEmpty(),
        )
    }

    @Test
    fun everyRlsEnabledCollectionTableAlsoForcesRls() {
        // FORCE ROW LEVEL SECURITY is required so the table owner (and the superuser bootstrap
        // path) cannot silently bypass the policy. Assert the participant-data tables force it.
        val corpus = migrationCorpus()
        val notForced = REQUIRED_PARTICIPANT_DATA_TABLES.filterNot { table ->
            corpus.contains("ALTER TABLE $table FORCE ROW LEVEL SECURITY")
        }
        assertTrue(
            "Participant-data tables that enable RLS but do not FORCE it (owner can bypass): \n  " +
                notForced.joinToString("\n  "),
            notForced.isEmpty(),
        )
    }
}
