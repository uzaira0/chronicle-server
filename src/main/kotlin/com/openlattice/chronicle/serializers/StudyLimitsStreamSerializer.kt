/*
 * Copyright (C) 2017. OpenLattice, Inc
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */
package com.openlattice.chronicle.serializers

import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.openlattice.chronicle.hazelcast.StreamSerializerTypeIds
import com.geekbeast.hazelcast.serializers.TestableSelfRegisteringStreamSerializer
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyLimits
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.OffsetDateTime

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Component
public open class StudyLimitsStreamSerializer : TestableSelfRegisteringStreamSerializer<StudyLimits> {
    public companion object {
        @JvmStatic
        @Throws(IOException::class)
        public fun serializeStudyDuration(out: ObjectDataOutput, studyDuration: StudyDuration) {
            out.writeShort(studyDuration.years.toInt())
            out.writeShort(studyDuration.months.toInt())
            out.writeInt(studyDuration.days)
        }

        @JvmStatic
        @Throws(IOException::class)
        public fun deserializeStudyDuration(input: ObjectDataInput): StudyDuration {
            return StudyDuration(
                input.readShort(),
                input.readShort(),
                input.readInt()
            )
        }
    }

    override fun generateTestValue(): StudyLimits {
        return StudyLimits()
    }

    override fun getTypeId(): Int = StreamSerializerTypeIds.STUDY_LIMITS.ordinal

    override fun getClazz(): Class<out StudyLimits> {
        return StudyLimits::class.java
    }

    override fun write(out: ObjectDataOutput, obj: StudyLimits) {
        out.writeBoolean(obj.studyDuration != null)
        obj.studyDuration?.let { serializeStudyDuration(out, it) }
        serializeStudyDuration(out, obj.dataRetentionDuration)
        out.writeObject(obj.studyEnds)
        out.writeObject(obj.studyDataExpires)
    }

    override fun read(input: ObjectDataInput): StudyLimits {
        val studyDuration = if (input.readBoolean()) deserializeStudyDuration(input) else null
        val dataRetention = deserializeStudyDuration(input)
        val studyEnds = input.readObject<OffsetDateTime?>()
        val studyDataExpires = input.readObject<OffsetDateTime?>()
        return StudyLimits(studyDuration, dataRetention, studyEnds, studyDataExpires)
    }
}
