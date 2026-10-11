package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import org.flywaydb.core.api.callback.BaseCallback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event

/** Preserve V111's published checksum, enrolled device keys and deferred name mappings. */
internal class IosDeviceKeyBackfillCallback : BaseCallback() {
    override fun supports(event: Event, context: Context?): Boolean =
        context?.migrationInfo?.version?.version == "111" &&
            event in setOf(Event.BEFORE_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE)

    override fun handle(event: Event, context: Context) {
        context.connection.createStatement().use { statement ->
            if (event == Event.BEFORE_EACH_MIGRATE) {
                statement.execute("""
                    CREATE TEMP VIEW sensor_data WITH (security_invoker = true) AS
                    SELECT sensor.* FROM public.sensor_data sensor
                    WHERE NOT pg_input_is_valid(sensor.device_name, 'uuid')
                      AND public.chronicle_participant_data_visible(sensor.study_id::text, sensor.participant_id);
                    CREATE TEMP VIEW devices WITH (security_invoker = true) AS
                    SELECT device.* FROM public.devices device
                    WHERE public.chronicle_participant_data_visible(device.study_id, device.participant_id)
                """.trimIndent())
            } else {
                statement.execute("DROP VIEW IF EXISTS pg_temp.sensor_data; DROP VIEW IF EXISTS pg_temp.devices")
                RLSConnectionCustomizer.withRestoredAdminTransactionContext(context.connection) {
                    statement.execute("""
                        UPDATE public.sensor_data SET device_system_name = ''
                        WHERE device_system_name = device_name AND pg_input_is_valid(device_name, 'uuid')
                          AND public.chronicle_participant_data_visible(study_id::text, participant_id)
                          AND public.chronicle_participant_mutation_allowed(study_id::text, participant_id)
                    """.trimIndent())
                }
            }
        }
    }
}
