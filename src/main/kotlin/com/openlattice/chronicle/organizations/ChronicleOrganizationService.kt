package com.openlattice.chronicle.organizations

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.util.ensureVanilla
import org.springframework.stereotype.Service
import java.sql.Connection
import java.util.EnumSet
import java.util.Optional
import java.util.UUID

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Service
public open class ChronicleOrganizationService(
    private val storageResolver: StorageResolver,
    private val authorizationManager: AuthorizationManager,
    private val idGenerationService: HazelcastIdGenerationService,
    override val auditingManager: AuditingManager
) :AuditingComponent {
    public companion object {

        /**
         * 1. organization ids
         */
        private val GET_ORGANIZATION_SQL = """
            SELECT * FROM ${ORGANIZATIONS.name} WHERE ${ORGANIZATION_ID.name} = ANY(?)
        """.trimIndent()
        private val ORG_COLS = ORGANIZATIONS.columns.joinToString(",") { it.name }

        /**
         * 1. organization id
         * 2. title
         * 3. description
         */
        private val INSERT_ORGANIZATION_SQL = """
            INSERT INTO ${ORGANIZATIONS.name} ($ORG_COLS) VALUES (?,?,?)
        """.trimIndent()

        private val GET_ALL_ORGANIZATIONS_SQL = """
            SELECT * FROM ${ORGANIZATIONS.name}
        """.trimIndent()
    }

    public fun createOrganization(owner: Principal, organization: Organization) : UUID {
        organization.id = idGenerationService.getNextId()
        val aclKey = AclKey(organization.id)
        val platform = storageResolver.getPlatformStorage()
        platform.connection.use { conn ->
            AuditedTransactionBuilder<Unit>(conn, auditingManager)
                .transaction { connection ->
                    createOrganization(
                        connection,
                        Principals.getCurrentUser(),
                        organization
                    )
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            AclKey(organization.id),
                            Principals.getCurrentSecurablePrincipal().id,
                            Principals.getCurrentUser(),
                            AuditEventType.CREATE_ORGANIZATION,
                            "",
                            organization.id,
                            UUID(0, 0),
                            mapOf()
                        )
                    )
                }
                .buildAndRun()
        }

        PinnedPlatformConnection.afterCommitOrNow(platform, aclKey) { authorizationManager.ensureAceIsLoaded(aclKey, owner) }
        return organization.id
    }

    public fun createOrganization(connection: Connection, owner: Principal, organization: Organization) {
        RLSConnectionCustomizer.authorizeCreatedOrganization(connection, organization.id)
        insertOrganization(connection, organization)
        authorizationManager.createUnnamedSecurableObject(
            connection,
            AclKey(organization.id),
            owner,
            EnumSet.allOf(Permission::class.java),
            SecurableObjectType.Organization
        )
    }

    private fun insertOrganization(connection: Connection, organization: Organization) {
        connection.prepareStatement(INSERT_ORGANIZATION_SQL).use { ps ->
            ps.setObject(1, organization.id)
            ps.setString(2, organization.title)
            ps.setString(3, organization.description)
            ps.executeUpdate()
        }
    }

    public fun maybeGetOrganization(organizationId: UUID): Optional<Organization> {
        return Optional.ofNullable(getOrganizations(listOf(organizationId)).firstOrNull())
    }

    public fun getOrganization(organizationId: UUID): Organization {
        return getOrganizations(listOf(organizationId)).first() //Since organization id is primary key in db, we're guaranteed one unique row
    }

    public fun getOrganizations(organizationIds: Collection<UUID>): Iterable<Organization> {
        val storage = storageResolver.getDefaultPlatformStorage()
        val flavor = storage.flavor
        val hds = storage.dataSource
        ensureVanilla(flavor)
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(
                hds,
                GET_ORGANIZATION_SQL
            ) { ps -> ps.setArray(1, PostgresArrays.createUuidArray(ps.connection, organizationIds)) }
        ) { ResultSetAdapters.organization(it) }
    }

    public fun removeMemberFromAllOrganizations(principal: Principal) {
        val allOrgs = searchOrganizations()
        allOrgs.forEach { org ->
            val aclKey = AclKey(org.id)
            authorizationManager.removePermission(aclKey, principal, EnumSet.allOf(Permission::class.java))
        }
    }

    

    

    

    

    

    

    

    public fun searchOrganizations(): Collection<Organization> {
        val storage = storageResolver.getDefaultPlatformStorage()
        val flavor = storage.flavor
        val hds = storage.dataSource
        ensureVanilla(flavor)
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, GET_ALL_ORGANIZATIONS_SQL) {}
        ) { ResultSetAdapters.organization(it) }.toList()
    }

}
