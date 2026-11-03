package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** The database refuses study values the application would never write. */
class StudyColumnConstraintsTest {
    companion object {
        private const val CHECK_VIOLATION = "23514"

        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("study_column_constraints")

        @BeforeClass
        @JvmStatic
        fun migrate() = ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

        @AfterClass
        @JvmStatic
        fun clear() = ChronicleContractTestSchema.clearSchema(postgres)
    }

    @Test
    fun everyLifecycleStatusIsStoredAndAnUnknownOneIsRefused() {
        postgres.createConnection("").use { connection ->
            StudyLifecycleStatus.entries.forEach { insertStudy(connection, it.name) }

            val failure = assertThrows(SQLException::class.java) { insertStudy(connection, "DELETED") }
            assertEquals(CHECK_VIOLATION, failure.sqlState)
        }
    }

    private fun insertStudy(connection: Connection, status: String): UUID = UUID.randomUUID().also { studyId ->
        connection.prepareStatement("INSERT INTO studies (study_id, title, lifecycle_status) VALUES (?, 'constraints', ?)").use {
            it.setObject(1, studyId)
            it.setString(2, status)
            it.executeUpdate()
        }
    }
}
