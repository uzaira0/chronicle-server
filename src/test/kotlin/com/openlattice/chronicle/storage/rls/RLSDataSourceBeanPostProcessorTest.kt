package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.pods.RLSSecurityPod
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.util.UUID

class RLSDataSourceBeanPostProcessorTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")
    }

    private lateinit var hds: HikariDataSource

    @Before
    fun setUp() {
        hds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 1
                minimumIdle = 1
            }
        )
        // The request-path wrapper now drops to chronicle_app via SET ROLE; the role must
        // exist in this bare container (mirrors docker/init-db-roles.sql).
        hds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "DO ${'$'}${'$'} BEGIN " +
                        "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'chronicle_app') THEN " +
                        "CREATE ROLE chronicle_app NOSUPERUSER NOBYPASSRLS; END IF; END ${'$'}${'$'};"
                )
            }
        }
    }

    @After
    fun tearDown() {
        RLSRequestContext.clear()
        if (::hds.isInitialized) {
            hds.close()
        }
    }

    @Test
    fun `spring managed hikari datasource beans are wrapped for request scoped RLS`() {
        val postProcessor = RLSSecurityPod.rlsAwareHikariDataSourcePostProcessor()
        val processed = postProcessor.postProcessAfterInitialization(hds, "platformDataSource")

        assertNotSame(hds, processed)
        val wrapped = processed as HikariDataSource
        val studyId = UUID.randomUUID()
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "user-rls",
                authorizedStudyIds = setOf(studyId),
                isAdmin = false
            )
        )

        wrapped.connection.use { connection ->
            assertEquals("user-rls", currentSetting(connection, "app.current_user_id"))
            assertEquals(studyId.toString(), currentSetting(connection, "app.authorized_studies"))
        }

        hds.connection.use { connection ->
            assertEquals("", currentSetting(connection, "app.current_user_id"))
            assertEquals("", currentSetting(connection, "app.authorized_studies"))
        }
    }

    private fun currentSetting(connection: Connection, setting: String): String? {
        connection.prepareStatement("SELECT current_setting(?, true)").use { statement ->
            statement.setString(1, setting)
            statement.executeQuery().use { rs ->
                rs.next()
                return rs.getString(1)
            }
        }
    }
}
