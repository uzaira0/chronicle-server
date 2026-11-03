package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import org.junit.Assert.assertEquals

internal fun executeSingleUpdate(
    connection: Connection,
    sql: String,
    expected: Int? = 1,
    bind: (PreparedStatement) -> Unit,
) {
    connection.prepareStatement(sql).use { statement ->
        bind(statement)
        val updated = statement.executeUpdate()
        expected?.let { assertEquals(it, updated) }
    }
}

internal fun deleteById(connection: Connection, table: String, column: String, id: UUID) {
    executeSingleUpdate(connection, "DELETE FROM $table WHERE $column = ?", expected = null) {
        it.setObject(1, id)
    }
}

internal fun migrationColumnNames(tableName: String): Set<String> =
    ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
        connection.metaData.getColumns(null, "public", tableName, null).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("COLUMN_NAME")) }
        }
    }
