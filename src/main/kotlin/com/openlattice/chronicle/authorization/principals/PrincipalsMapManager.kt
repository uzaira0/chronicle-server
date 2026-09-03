package com.openlattice.chronicle.authorization.principals

import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.Role
import com.openlattice.chronicle.authorization.SecurablePrincipal

/**
 * @author Drew Bailey (drew@openlattice.com)
 */
public interface PrincipalsMapManager {
    public companion object {
        public fun findPrincipal(p: Principal): Predicate<AclKey, SecurablePrincipal> {
            return Predicates.equal(PrincipalMapstore.PRINCIPAL_INDEX, p)
        }

        public fun hasPrincipalType(principalType: PrincipalType): Predicate<AclKey, SecurablePrincipal> {
            return Predicates.equal<AclKey, SecurablePrincipal>(PrincipalMapstore.PRINCIPAL_TYPE_INDEX, principalType)
        }

        public fun getFirstSecurablePrincipal(
            principals: IMap<AclKey, SecurablePrincipal>,
            p: Predicate<AclKey, SecurablePrincipal>
        ): SecurablePrincipal {
            return principals.values(p).first()
        }
    }

    public fun lookupRole(aclKey: AclKey): Role

    public fun lookupRole(principal: Principal): Role

    public fun getAllRoles(): Set<Role>

    public fun getSecurablePrincipal(principalId: String): SecurablePrincipal

    public fun getSecurablePrincipal(aclKey: AclKey): SecurablePrincipal?
    
    public fun getSecurablePrincipal(principal: Principal): SecurablePrincipal

    public fun getSecurablePrincipals(aclKeys: Set<AclKey>): Map<AclKey, SecurablePrincipal>

    public fun getAclKeyByPrincipal(ps: Set<Principal>): Map<Principal, AclKey>

}
