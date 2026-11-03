package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Assert.assertTrue
import org.springframework.security.core.context.SecurityContextHolder

abstract class RLSConnectionFixture {
    companion object {
        @ClassRule
        @JvmField
        val database = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")
    }

    // JUnit creates one instance per test, inside the class rule, so the container is already up here.
    protected val hds: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = database.jdbcUrl
        username = database.username
        password = database.password
        maximumPoolSize = 1
        minimumIdle = 1
    })

    @Before
    fun setUp() {
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
        SecurityContextHolder.clearContext()
        hds.close()
    }

    protected val postgres get() = database

    protected fun currentSetting(connection: Connection, setting: String): String? {
        connection.prepareStatement("SELECT current_setting(?, true)").use { statement ->
            statement.setString(1, setting)
            statement.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getString(1)
            }
        }
    }

}
