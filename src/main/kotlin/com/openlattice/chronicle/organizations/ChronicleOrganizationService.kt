package com.openlattice.chronicle.organizations

import com.geekbeast.mappers.mappers.ObjectMappers
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
import com.openlattice.chronicle.settings.AppComponent
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.ensureVanilla
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
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
        private val mapper = ObjectMappers.newJsonMapper()

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
         * 4. settings
         */
        private val INSERT_ORGANIZATION_SQL = """
            INSERT INTO ${ORGANIZATIONS.name} ($ORG_COLS) VALUES (?,?,?,?::jsonb)
        """.trimIndent()

        /**
         * 1. organization id
         */
        private val GET_ORG_SETTINGS_SQL = """
            SELECT ${SETTINGS.name} FROM ${ORGANIZATIONS.name} WHERE ${ORGANIZATION_ID.name} = ?
        """.trimIndent()

        /**
         * 1. settings (jsonb)
         * 2. organization id
         */
        private val UPDATE_ORG_SETTINGS_SQL = """
            UPDATE ${ORGANIZATIONS.name} SET ${SETTINGS.name} = ?::jsonb WHERE ${ORGANIZATION_ID.name} = ?
        """.trimIndent()

        private val GET_ALL_ORGANIZATIONS_SQL = """
            SELECT * FROM ${ORGANIZATIONS.name}
        """.trimIndent()
    }

    public fun createOrganization(owner: Principal, organization: Organization) : UUID {
        organization.id = idGenerationService.getNextId()
        val aclKey = AclKey(organization.id)
        storageResolver.getPlatformStorage().connection.use { conn ->
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

        authorizationManager.ensureAceIsLoaded(aclKey, owner)
        return organization.id
    }

    public fun createOrganization(connection: Connection, owner: Principal, organization: Organization) {
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
            ps.setObject(4, mapper.writeValueAsString(organization.settings))
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
        val (flavor, hds) = storageResolver.getDefaultPlatformStorage()
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

    public fun getOrganizationSettings(organizationId: UUID): OrganizationSettings {
        val hds = storageResolver.getPlatformStorage()
        return hds.connection.use { connection ->
            connection.prepareStatement(GET_ORG_SETTINGS_SQL).use { ps ->
                ps.setObject(1, organizationId)
                ps.executeQuery().use { rs -> parseOrganizationSettings(rs) }
            }
        }
    }

    private fun parseOrganizationSettings(rs: ResultSet): OrganizationSettings {
        if (!rs.next()) {
            return OrganizationSettings()
        }
        val settingsJson = rs.getString(SETTINGS.name) ?: return OrganizationSettings()
        return mapper.readValue(settingsJson, OrganizationSettings::class.java)
    }

    public fun setOrganizationSettings(organizationId: UUID, settings: OrganizationSettings) {
        val hds = storageResolver.getPlatformStorage()
        hds.connection.use { connection ->
            connection.prepareStatement(UPDATE_ORG_SETTINGS_SQL).use { ps ->
                ps.setString(1, mapper.writeValueAsString(settings))
                ps.setObject(2, organizationId)
                ps.executeUpdate()
            }
        }
    }

    public fun getChronicleDataCollectionSettings(organizationId: UUID): ChronicleDataCollectionSettings {
        return getOrganizationSettings(organizationId).chronicleDataCollection
    }

    public fun setChronicleDataCollectionSettings(organizationId: UUID, dataCollectionSettings: ChronicleDataCollectionSettings) {
        val existing = getOrganizationSettings(organizationId)
        val updated = existing.copy(chronicleDataCollection = dataCollectionSettings)
        setOrganizationSettings(organizationId, updated)
    }

    public fun getAppComponentSettings(organizationId: UUID, appComponent: AppComponent): Map<String, Any> {
        return getOrganizationSettings(organizationId).appSettings[appComponent] ?: emptyMap()
    }

    public fun setAppComponentSettings(organizationId: UUID, appComponent: AppComponent, settings: Map<String, Any>) {
        val existing = getOrganizationSettings(organizationId)
        val updatedAppSettings = existing.appSettings.toMutableMap()
        updatedAppSettings[appComponent] = settings
        val updated = existing.copy(appSettings = updatedAppSettings)
        setOrganizationSettings(organizationId, updated)
    }

    public fun searchOrganizations(): Collection<Organization> {
        val (flavor, hds) = storageResolver.getDefaultPlatformStorage()
        ensureVanilla(flavor)
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(hds, GET_ALL_ORGANIZATIONS_SQL) {}
        ) { ResultSetAdapters.organization(it) }.toList()
    }

}
