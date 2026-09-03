package com.openlattice.chronicle.serializers

import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.hazelcast.StreamSerializerTypeIds
import com.geekbeast.hazelcast.serializers.AbstractEnumSerializer
import org.springframework.stereotype.Component

/**
 * @author Drew Bailey &lt;drew@openlattice.com&gt;
 */
@Component
public open class PrincipalTypeStreamSerializer: AbstractEnumSerializer<PrincipalType>() {

    public companion object {
        @JvmStatic
        public fun serialize(out: ObjectDataOutput, obj: PrincipalType): Unit =
            AbstractEnumSerializer.serialize(out, obj)
        @JvmStatic
        public fun deserialize(input: ObjectDataInput): PrincipalType =
            deserialize(PrincipalType::class.java, input)
    }

    override fun getTypeId(): Int {
        return StreamSerializerTypeIds.PRINCIPAL_TYPE.ordinal
    }

    override fun getClazz(): Class<PrincipalType> {
        return PrincipalType::class.java
    }
}
