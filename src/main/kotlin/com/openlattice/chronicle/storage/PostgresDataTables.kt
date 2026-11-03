package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresTableDefinition

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
        public val CHRONICLE_USAGE_STATS = PostgresTableDefinition(PostgresEventTables.CHRONICLE_USAGE_STATS.name)
            .addColumns(*PostgresEventTables.CHRONICLE_USAGE_STATS.columns.toTypedArray())
            .primaryKey(*PostgresEventTables.CHRONICLE_USAGE_STATS.columns.toTypedArray())
            .addDataSourceNames(PostgresEventTables.POSTGRES_EVENT_DATASOURCE_NAME)

        @JvmField
        public val AUDIT = PostgresTableDefinition(PostgresEventTables.AUDIT.name)
            .addColumns(*PostgresEventTables.AUDIT.columns.toTypedArray())
            .primaryKey(PostgresEventColumns.AUDIT_EVENT_ID)
            .addDataSourceNames(PostgresEventTables.POSTGRES_EVENT_DATASOURCE_NAME)

    }
}
