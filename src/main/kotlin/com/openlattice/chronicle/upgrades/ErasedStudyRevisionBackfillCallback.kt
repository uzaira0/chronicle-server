package com.openlattice.chronicle.upgrades

import org.flywaydb.core.api.callback.BaseCallback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event

/** Keep V93's published checksum while hiding erased subjects from its legacy source. */
internal class ErasedStudyRevisionBackfillCallback : BaseCallback() {
    // Flyway probes supports() with a null context while registering callbacks.
    override fun supports(event: Event, context: Context?): Boolean =
        context?.migrationInfo?.version?.version == "93" &&
            event in setOf(Event.BEFORE_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE)

    override fun handle(event: Event, context: Context) {
        context.connection.createStatement().use { statement ->
            statement.execute(if (event == Event.BEFORE_EACH_MIGRATE) """
                CREATE TEMP VIEW study_settings_audit AS
                SELECT audit.* FROM public.study_settings_audit audit
                WHERE EXISTS (SELECT 1 FROM public.studies study WHERE study.study_id = audit.study_id)
            """.trimIndent() else "DROP VIEW IF EXISTS pg_temp.study_settings_audit")
        }
    }
}
