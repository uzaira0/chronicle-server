package com.openlattice.chronicle.util.tests

import com.geekbeast.rhizome.hazelcast.DelegatedIntList
import com.openlattice.chronicle.authorization.AclKeySet
import com.openlattice.chronicle.serializers.AclKeyStreamSerializer
import kotlin.random.Random

public class InternalTestDataFactory private constructor() {
    internal companion object {

        @JvmStatic
        public fun delegatedIntList(): DelegatedIntList {
            return DelegatedIntList(
                listOf(
                    Random.nextInt(), Random.nextInt(), Random.nextInt(), Random.nextInt(), Random.nextInt(),
                    Random.nextInt(), Random.nextInt(), Random.nextInt(), Random.nextInt(), Random.nextInt()
                )
            )
        }

        @JvmStatic
        public fun aclKeySet(): AclKeySet {
            return AclKeySet(
                mutableSetOf(
                    AclKeyStreamSerializer().generateTestValue(),
                    AclKeyStreamSerializer().generateTestValue(),
                    AclKeyStreamSerializer().generateTestValue(),
                    AclKeyStreamSerializer().generateTestValue(),
                    AclKeyStreamSerializer().generateTestValue(),
                    AclKeyStreamSerializer().generateTestValue(),
                    AclKeyStreamSerializer().generateTestValue()
                )
            )
        }
    }

}
