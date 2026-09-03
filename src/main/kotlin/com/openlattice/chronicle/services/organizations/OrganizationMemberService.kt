package com.openlattice.chronicle.services.organizations

import com.openlattice.chronicle.organizations.OrganizationMember
import com.openlattice.chronicle.organizations.OrganizationQuotas
import com.openlattice.chronicle.organizations.OrganizationRole
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.*

public open class OrganizationMemberService(
    private val storageResolver: StorageResolver
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(OrganizationMemberService::class.java)

        private const val INSERT_MEMBER_SQL = """
            INSERT INTO organization_members (organization_id, user_id, role)
            VALUES (?, ?, ?)
            ON CONFLICT (organization_id, user_id) DO UPDATE SET role = EXCLUDED.role
        """

        private const val LIST_MEMBERS_SQL = """
            SELECT organization_id, user_id, role, added_at
            FROM organization_members
            WHERE organization_id = ?
            ORDER BY added_at
        """

        private const val DELETE_MEMBER_SQL = """
            DELETE FROM organization_members WHERE organization_id = ? AND user_id = ?
        """

        private const val GET_QUOTAS_SQL = """
            SELECT * FROM organization_quotas WHERE organization_id = ?
        """

        private const val UPSERT_QUOTAS_SQL = """
            INSERT INTO organization_quotas
                (organization_id, max_studies, max_participants_per_study, max_api_keys_per_study, max_webhooks_per_study, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (organization_id) DO UPDATE
                SET max_studies = EXCLUDED.max_studies,
                    max_participants_per_study = EXCLUDED.max_participants_per_study,
                    max_api_keys_per_study = EXCLUDED.max_api_keys_per_study,
                    max_webhooks_per_study = EXCLUDED.max_webhooks_per_study,
                    updated_at = now()
        """
    }

    public fun addMember(organizationId: UUID, member: OrganizationMember) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(INSERT_MEMBER_SQL).use { ps ->
                ps.setObject(1, organizationId)
                ps.setString(2, member.userId)
                ps.setString(3, member.role.name)
                ps.executeUpdate()
            }
        }
        logger.info("Member {} added to org {} with role {}", member.userId, organizationId, member.role)
    }

    public fun listMembers(organizationId: UUID): List<OrganizationMember> {
        val members = mutableListOf<OrganizationMember>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(LIST_MEMBERS_SQL).use { ps ->
                ps.setObject(1, organizationId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    members.add(OrganizationMember(
                        organizationId = rs.getObject("organization_id", UUID::class.java),
                        userId = rs.getString("user_id"),
                        role = OrganizationRole.valueOf(rs.getString("role")),
                        addedAt = rs.getObject("added_at", OffsetDateTime::class.java)
                    ))
                }
            }
        }
        return members
    }

    /**
     * Returns the role of a user in the given organization, or null if they are not a member.
     * Used by OrganizationAuthorizationAspect for access control.
     */
    public fun getMemberRole(organizationId: UUID, userId: String): OrganizationRole? {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT role FROM organization_members WHERE organization_id = ? AND user_id = ?"
            ).use { ps ->
                ps.setObject(1, organizationId)
                ps.setString(2, userId)
                val rs = ps.executeQuery()
                return if (rs.next()) OrganizationRole.valueOf(rs.getString("role")) else null
            }
        }
    }

    public fun removeMember(organizationId: UUID, userId: String) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(DELETE_MEMBER_SQL).use { ps ->
                ps.setObject(1, organizationId)
                ps.setString(2, userId)
                val deleted = ps.executeUpdate()
                check(deleted > 0) { "Member $userId not found in organization $organizationId" }
            }
        }
        logger.info("Member {} removed from org {}", userId, organizationId)
    }

    public fun getQuotas(organizationId: UUID): OrganizationQuotas {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_QUOTAS_SQL).use { ps ->
                ps.setObject(1, organizationId)
                val rs = ps.executeQuery()
                return if (rs.next()) {
                    OrganizationQuotas(
                        organizationId = rs.getObject("organization_id", UUID::class.java),
                        maxStudies = rs.getInt("max_studies"),
                        maxParticipantsPerStudy = rs.getInt("max_participants_per_study"),
                        maxApiKeysPerStudy = rs.getInt("max_api_keys_per_study"),
                        maxWebhooksPerStudy = rs.getInt("max_webhooks_per_study")
                    )
                } else {
                    OrganizationQuotas(organizationId = organizationId)
                }
            }
        }
    }

    public fun updateQuotas(organizationId: UUID, quotas: OrganizationQuotas): OrganizationQuotas {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(UPSERT_QUOTAS_SQL).use { ps ->
                ps.setObject(1, organizationId)
                ps.setInt(2, quotas.maxStudies)
                ps.setInt(3, quotas.maxParticipantsPerStudy)
                ps.setInt(4, quotas.maxApiKeysPerStudy)
                ps.setInt(5, quotas.maxWebhooksPerStudy)
                ps.executeUpdate()
            }
        }
        logger.info("Quotas updated for org {}", organizationId)
        return quotas.copy(organizationId = organizationId)
    }
}
