package com.openlattice.chronicle.storage

import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.postgres.PostgresTables
import com.zaxxer.hikari.HikariDataSource

/** Checks the authenticated login, which RESET ROLE or a raw borrow cannot escape. */
internal class RuntimeDatabaseRoles(
    private val dataSourceManager: DataSourceManager,
    private val configuration: ChronicleStorageConfiguration,
) {
    fun validate() {
        val bootstrap = dataSourceManager.dataSources[configuration.bootstrapStorage]
        val runtime = runtimePoolsByAlias()
        aliases().forEach(::requireRuntimeAlias)
        val owner = bootstrap?.connection?.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT session_user").use { rows -> check(rows.next()); rows.getString(1) }
            }
        }
        runtime.forEach { (alias, pool) ->
            check(bootstrap == null || !RLSDataSources.samePool(pool, bootstrap)) { "Runtime storage '$alias' shares its bootstrap pool" }
            validateLogin(alias, pool, owner)
        }
    }

    fun registerTables(tables: Collection<PostgresTables>) {
        val definitions = tables.flatMap { it.tables().use { definitions -> definitions.toList() } }
        definitions.forEach { dataSourceManager.registerTables(configuration.bootstrapStorage, it) }
    }

    fun runtimePools(): List<HikariDataSource> = runtimePoolsByAlias().values.distinct()

    private fun runtimePoolsByAlias(): Map<String, HikariDataSource> =
        dataSourceManager.dataSources.filterKeys { it != configuration.bootstrapStorage }

    fun requireRuntimeAlias(name: String) {
        require(name != configuration.bootstrapStorage) { "Bootstrap storage cannot serve runtime data" }
        val pools = dataSourceManager.dataSources.orEmpty()
        val owner = pools[configuration.bootstrapStorage] ?: return
        val runtime = pools[name] ?: return
        require(!RLSDataSources.samePool(owner, runtime)) { "Runtime storage cannot share the bootstrap pool" }
    }

    private fun aliases(): Set<String> = setOf(
        configuration.platformStorage, configuration.platformReadStorage,
        configuration.defaultEventStorage, configuration.auditStorage,
    )

    private fun validateLogin(alias: String, pool: HikariDataSource, owner: String?) {
        pool.connection.use { connection ->
            connection.prepareStatement(ROLE_SQL).use { statement ->
                statement.setString(1, owner)
                statement.setArray(2, connection.createArrayOf("text", UNSAFE_BUILTIN_ROLES.toTypedArray()))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Runtime storage '$alias' has no authenticated database role" }
                    check(!rows.getBoolean("privileged") && !rows.getBoolean("privileged_access")) {
                        "Runtime storage '$alias' must use a non-owner NOSUPERUSER NOBYPASSRLS login without privileged role membership"
                    }
                }
            }
        }
    }

    companion object {
        val UNSAFE_BUILTIN_ROLES = listOf(
            "pg_read_all_data", "pg_write_all_data", "pg_read_server_files",
            "pg_write_server_files", "pg_execute_server_program",
        )

        private val ROLE_SQL = """
            WITH unsafe_roles AS (
                SELECT oid FROM pg_roles WHERE rolname = ?
                    OR rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication
                    OR rolname::text = ANY(?)
                UNION SELECT datdba FROM pg_database WHERE datname = current_database()
                UNION SELECT nspowner FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname <> 'information_schema'
                UNION SELECT relation.relowner FROM pg_class relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname !~ '^pg_' AND namespace.nspname <> 'information_schema'
            )
            SELECT role.rolsuper OR role.rolbypassrls OR role.rolcreatedb OR role.rolcreaterole OR role.rolreplication AS privileged,
                   EXISTS (SELECT 1 FROM unsafe_roles WHERE pg_has_role(role.oid, unsafe_roles.oid, 'USAGE')
                       OR pg_has_role(role.oid, unsafe_roles.oid, 'SET')) AS privileged_access
            FROM pg_roles role WHERE role.rolname = session_user
        """.trimIndent()
    }
}
