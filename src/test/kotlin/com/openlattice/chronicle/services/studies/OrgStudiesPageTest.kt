package com.openlattice.chronicle.services.studies

import com.geekbeast.postgres.streams.BasePostgresIterable
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.util.UUID

/** A client stops at the first short page, so an erased study must not make a page short. */
class OrgStudiesPageTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("org_studies_page")

        @BeforeClass
        @JvmStatic
        fun migrate() = ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

        @AfterClass
        @JvmStatic
        fun clear() = ChronicleContractTestSchema.clearSchema(postgres)
    }

    @Test
    fun anErasedStudyOnTheFirstPageDoesNotShortenIt() {
        val reader = "reader"
        val organizationId = UUID.randomUUID()
        val (erased, newer, older) = List(3) { UUID.randomUUID() }
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                listOf(erased to "now()", newer to "now() - interval '1 day'", older to "now() - interval '2 days'")
                    .forEach { (studyId, createdAt) ->
                        statement.execute("INSERT INTO studies (study_id, title, created_at) VALUES ('$studyId', 'page', $createdAt)")
                        statement.execute(
                            "INSERT INTO permissions (acl_key, principal_type, principal_id, permissions) " +
                                "VALUES (ARRAY['$studyId'::uuid], 'USER', '$reader', ARRAY['READ'])",
                        )
                        statement.execute(
                            "INSERT INTO organization_studies (organization_id, study_id, user_id, settings) " +
                                "VALUES ('$organizationId', '$studyId', '$reader', '{}')",
                        )
                    }
                statement.execute(
                    "INSERT INTO data_deletion_operations (operation_id, study_id, mode, status, requested_by, " +
                        "idempotency_key, registry_version) VALUES ('${UUID.randomUUID()}', '$erased', 'STUDY_ERASURE', " +
                        "'QUARANTINED', 'owner', '${UUID.randomUUID()}', 1)",
                )
            }
        }

        val page = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
        }).use { hds ->
            // The same read StudyService.getOrgStudies streams.
            BasePostgresIterable(StreamingReadTransaction(hds, StudyService.GET_ORG_STUDIES_SQL, 256) { statement ->
                statement.setString(1, reader)
                statement.setObject(2, organizationId)
                statement.setInt(3, 2)
                statement.setInt(4, 0)
            }) { rows -> rows.getObject("study_id", UUID::class.java) }.toList()
        }

        assertEquals(listOf(newer, older), page)
    }
}
