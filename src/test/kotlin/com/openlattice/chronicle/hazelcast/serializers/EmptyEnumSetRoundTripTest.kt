package com.openlattice.chronicle.hazelcast.serializers

import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder
import com.openlattice.chronicle.authorization.DelegatedPermissionEnumSet
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.serializers.StudyLimitsStreamSerializer
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** EnumSet.copyOf(Collection) throws on an empty non-EnumSet collection; empty sets are valid values. */
class EmptyEnumSetRoundTripTest {
    @Test
    fun studyLimitsWithAndWithoutAnEndRoundTrip() {
        val serializer = StudyLimitsStreamSerializer()
        val ss = DefaultSerializationServiceBuilder().build()
        for (limits in listOf(StudyLimits(), StudyLimits(studyDuration = StudyDuration(days = 30)))) {
            val out = ss.createObjectDataOutput(1).also { serializer.write(it, limits) }

            assertEquals(limits, serializer.read(ss.createObjectDataInput(out.toByteArray())))
        }
    }

    @Test
    fun emptyPermissionSetWraps() {
        assertTrue(DelegatedPermissionEnumSet.wrap(emptySet<Permission>()).isEmpty())
    }
}
