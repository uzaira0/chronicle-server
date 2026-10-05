package com.openlattice.chronicle.authorization

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.storage.StorageResolver

/** Reads current persisted membership rather than a never-populated resolved-principal cache. */
public class CreatorPrincipalResolver(private val storageResolver: StorageResolver,
    private val authConfiguration: ChronicleAuthConfiguration) {
    public fun resolve(userId: String): Set<Principal> = buildSet {
        add(Principal(PrincipalType.USER, userId))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("""
                WITH RECURSIVE membership(acl_key) AS (
                    SELECT acl_key FROM principals WHERE principal_type = 'USER' AND principal_id = ?
                    UNION
                    SELECT tree.acl_key FROM principal_trees tree
                    JOIN membership child ON tree.principal_of_acl_key = child.acl_key
                )
                SELECT principal_type, principal_id FROM principals JOIN membership USING (acl_key)
            """.trimIndent()).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) add(Principal(PrincipalType.valueOf(rows.getString(1)), rows.getString(2)))
                }
            }
        }
        // Local self-host users have authoritative membership in the server's loaded auth config.
        authConfiguration.users.firstOrNull { it.id == userId }?.appMetadata?.get("roles")
            .let { it as? Collection<*> }.orEmpty().filterIsInstance<String>().filter { it in SystemRole }
            .forEach { add(Principal(PrincipalType.ROLE, it)) }
    }
}
