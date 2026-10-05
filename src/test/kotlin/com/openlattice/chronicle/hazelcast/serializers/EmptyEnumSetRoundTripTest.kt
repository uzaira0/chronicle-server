package com.openlattice.chronicle.hazelcast.serializers

import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder
import com.openlattice.chronicle.authorization.DelegatedPermissionEnumSet
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.serializers.StudyLimitsStreamSerializer
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** EnumSet.copyOf(Collection) throws on an empty non-EnumSet collection; empty sets are valid values. */
class EmptyEnumSetRoundTripTest {
    @Test
    fun studyLimitsWithNoFeaturesRoundTrip() {
        val serializer = StudyLimitsStreamSerializer()
        val ss = DefaultSerializationServiceBuilder().build()
        val limits = StudyLimits(features = EnumSet.noneOf(StudyFeature::class.java))
        val out = ss.createObjectDataOutput(1).also { serializer.write(it, limits) }

        val readBack = serializer.read(ss.createObjectDataInput(out.toByteArray()))

        assertEquals(limits, readBack)
    }

    @Test
    fun emptyPermissionSetWraps() {
        assertTrue(DelegatedPermissionEnumSet.wrap(emptySet<Permission>()).isEmpty())
    }
}
