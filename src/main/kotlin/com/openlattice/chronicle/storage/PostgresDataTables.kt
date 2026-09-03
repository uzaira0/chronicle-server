package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PostgresDataTables private constructor() {
    // reason: vararg addColumns/primaryKey APIs require spread; the column arrays are small and static table definitions
    @Suppress("SpreadOperator")
    internal companion object {
        public const val POSTGRES_DATA_ENVIRONMENT = "postgres_data"

        @JvmField
        public val CHRONICLE_USAGE_EVENTS = PostgresTableDefinition(PostgresEventTables.CHRONICLE_USAGE_EVENTS.name)
            .addColumns(*PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.toTypedArray())
            .primaryKey(*(PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns - ACTIVITY_CLASS).toTypedArray())
            .addDataSourceNames(PostgresEventTables.POSTGRES_EVENT_DATASOURCE_NAME)

        @JvmField
        public val CHRONICLE_USAGE_STATS = PostgresTableDefinition(PostgresEventTables.CHRONICLE_USAGE_STATS.name)
            .addColumns(*PostgresEventTables.CHRONICLE_USAGE_STATS.columns.toTypedArray())
            .primaryKey(*PostgresEventTables.CHRONICLE_USAGE_STATS.columns.toTypedArray())
            .addDataSourceNames(PostgresEventTables.POSTGRES_EVENT_DATASOURCE_NAME)

        @JvmField
        public val AUDIT = PostgresTableDefinition(PostgresEventTables.AUDIT.name)
            .addColumns(*PostgresEventTables.AUDIT.columns.toTypedArray())
            .primaryKey(*PostgresEventTables.AUDIT.columns.toTypedArray())
            .addDataSourceNames(PostgresEventTables.POSTGRES_EVENT_DATASOURCE_NAME)

        @JvmField
        public val IOS_SENSOR_DATA = PostgresTableDefinition(PostgresEventTables.IOS_SENSOR_DATA.name)
            .addColumns(*PostgresEventTables.IOS_SENSOR_DATA.columns.toTypedArray())
            .primaryKey(*PostgresEventTables.IOS_SENSOR_DATA.columns.toTypedArray().sliceArray(0 until 32))
            .addDataSourceNames(PostgresEventTables.POSTGRES_EVENT_DATASOURCE_NAME)

    }
}
