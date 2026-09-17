package com.openlattice.chronicle.organizations

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

/**
 * OrganizationAuthorizationAspect resolves roles from organization_members only, so an
 * organization whose creator has no row there is 403 on every member/quota endpoint forever.
 * createOrganization must seed the creator as OWNER in the creating transaction.
 */
class OrganizationOwnerMembershipTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var hds: HikariDataSource
        private lateinit var service: ChronicleOrganizationService

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_org_owner_membership")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            hds = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                },
            )
            val storageResolver = object : StorageResolver(
                Mockito.mock(DataSourceManager::class.java),
                Mockito.mock(ChronicleStorageConfiguration::class.java),
            ) {
                override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource = hds
            }
            service = ChronicleOrganizationService(
                storageResolver,
                Mockito.mock(AuthorizationManager::class.java),
                Mockito.mock(HazelcastIdGenerationService::class.java),
                Mockito.mock(AuditingManager::class.java),
            )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (Companion::hds.isInitialized) hds.close()
            if (Companion::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `creating an organization seeds the creator as OWNER`() {
        val organizationId = UUID.randomUUID()
        create(organizationId, Principal(PrincipalType.USER, "creator-1"))
        assertEquals("OWNER", memberRole(organizationId, "creator-1"))
    }

    @Test
    fun `a role principal never becomes a member`() {
        val organizationId = UUID.randomUUID()
        create(organizationId, Principal(PrincipalType.ROLE, "GLOBAL_ADMIN_ROLE"))
        assertNull(memberRole(organizationId, "GLOBAL_ADMIN_ROLE"))
    }

    private fun create(organizationId: UUID, owner: Principal) {
        hds.connection.use { connection ->
            connection.autoCommit = false
            service.createOrganization(connection, owner, Organization(id = organizationId, title = "membership"))
            connection.commit()
        }
    }

    private fun memberRole(organizationId: UUID, userId: String): String? = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT role FROM organization_members WHERE organization_id = ? AND user_id = ?").use { statement ->
            statement.setObject(1, organizationId)
            statement.setString(2, userId)
            statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
        }
    }
}
