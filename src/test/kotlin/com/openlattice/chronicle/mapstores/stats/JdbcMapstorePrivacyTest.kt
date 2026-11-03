package com.openlattice.chronicle.mapstores.stats

import com.geekbeast.postgres.mapstores.AbstractBasePostgresMapstore
import com.geekbeast.rhizome.mapstores.TestableSelfRegisteringMapStore
import com.geekbeast.rhizome.mapstores.SelfRegisteringMapStore
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.mapstores.MapstoresPod
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.util.ChronicleStorageException
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.Bean
import org.springframework.core.ResolvableType
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

class JdbcMapstorePrivacyTest {
    private val retryableSqlState = "08006"
    @Test fun `generic Java mapstore failures never render keys or SQL values`() {
        assertPrivateOperations("mapstore-private-key-sentinel", "mapstore-private-value-sentinel")
    }

    @Test fun `every registered participant mapstore holder stays private at the shared log owner`() {
        val stores = MapstoresPod::class.java.declaredMethods
            .filter { it.isAnnotationPresent(Bean::class.java) }
            .filter { method ->
                ResolvableType.forMethodReturnType(method).`as`(SelfRegisteringMapStore::class.java)
                    .generics.any { type -> participantAccessors(type.resolve()).isNotEmpty() }
            }
            .map { it.returnType }
        assertTrue("Participant mapstore discovery must not silently become empty", stores.isNotEmpty())
        for (storeType in stores) {
            val store = storeType.getConstructor(HikariDataSource::class.java).newInstance(mock<HikariDataSource>())
                as TestableSelfRegisteringMapStore<*, *>
            val key = requireNotNull(store.generateTestKey())
            for (label in participantLabels(key)) {
                assertFalse("${storeType.simpleName} key rendering includes a participant label", key.toString().contains(label))
            }
            assertPrivateOperations(key, requireNotNull(store.generateTestValue()))
        }
    }

    private fun assertPrivateOperations(key: Any, value: Any) {
        val sqlValue = "mapstore-private-sql-sentinel"
        val sentinels = listOf(key, value).flatMap { holder ->
            if (holder is String) listOf(holder) else participantLabels(holder)
        } + sqlValue
        val actions: Map<String, (PrivacyMapstore) -> Unit> = mapOf(
            "store" to { it.store(key, value) },
            "storeAll" to { it.storeAll(mapOf(key to value)) },
            "delete" to { it.delete(key) },
            "deleteAll" to { it.deleteAll(listOf(key)) },
            "load" to { it.load(key) },
            "loadAll" to { it.loadAll(listOf(key)) },
            "loadAllKeys" to { it.loadAllKeys().iterator() },
        )
        for (bindingFailure in listOf(false, true)) for ((operation, action) in actions) {
            val source = failureSource(sqlValue, bindingFailure)
            assertPrivateFailure(operation, sentinels) {
                action(PrivacyMapstore(source))
            }
        }
    }

    private fun participantAccessors(type: Class<*>?) = type?.methods.orEmpty()
        .filter { it.name == "getParticipantId" && it.parameterCount == 0 && it.returnType == String::class.java }

    private fun participantLabels(holder: Any): List<String> = participantAccessors(holder.javaClass)
        .map { accessor -> accessor.invoke(holder) as String }

    private fun failureSource(value: String, bindingFailure: Boolean): HikariDataSource {
        val source = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val failure = SQLException(value, retryableSqlState)
        whenever(source.connection).thenReturn(connection)
        if (bindingFailure) {
            whenever(connection.prepareStatement(any())).thenThrow(failure)
            whenever(connection.createStatement()).thenThrow(failure)
        } else {
            whenever(connection.prepareStatement(any())).thenReturn(statement)
            whenever(connection.createStatement()).thenReturn(statement)
            whenever(statement.execute()).thenThrow(failure)
            whenever(statement.executeUpdate()).thenThrow(failure)
            whenever(statement.executeBatch()).thenThrow(failure)
            whenever(statement.executeQuery()).thenThrow(failure)
            whenever(statement.executeQuery(any())).thenThrow(failure)
        }
        return source
    }

    private fun assertPrivateFailure(operation: String, sentinels: List<String>, action: () -> Unit) {
        ScopedLogCapture(PrivacyMapstore::class.java).use { logs ->
            val failure = assertThrows(RuntimeException::class.java) { action() }
            assertNull("$operation must drop the SQL cause", failure.cause)
            assertEquals(retryableSqlState, (failure as ChronicleStorageException).sqlState)
            for (sentinel in sentinels) {
                assertFalse("$operation exception includes a private value", failure.toString().contains(sentinel))
                assertFalse("$operation logs include a private value", logs.messages.joinToString("\n").contains(sentinel))
            }
        }
    }

    private class PrivacyMapstore(source: HikariDataSource) : AbstractBasePostgresMapstore<Any, Any>(
        "privacy-test", ChroniclePostgresTables.PARTICIPANT_STATS, source,
    ) {
        override fun bind(statement: PreparedStatement, key: Any, value: Any) = Unit
        override fun bind(statement: PreparedStatement, key: Any, offset: Int): Int = offset + 2
        override fun mapToKey(rows: ResultSet): String = "key"
        override fun mapToValue(rows: ResultSet): String = "value"
        override fun generateTestKey(): String = "key"
        override fun generateTestValue(): String = "value"
    }
}
