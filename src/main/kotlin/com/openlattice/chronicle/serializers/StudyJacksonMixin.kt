package com.openlattice.chronicle.serializers

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.datatype.jsr310.ser.OffsetDateTimeSerializer
import java.time.OffsetDateTime

internal abstract class StudyJacksonMixin {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonSerialize(using = EndDateSerializer::class)
    abstract fun getEndedAt(): OffsetDateTime

    class EndDateSerializer : JsonSerializer<OffsetDateTime>() {
        override fun isEmpty(provider: SerializerProvider, value: OffsetDateTime): Boolean = value == OffsetDateTime.MAX

        override fun serialize(value: OffsetDateTime, generator: JsonGenerator, provider: SerializerProvider) {
            OffsetDateTimeSerializer.INSTANCE.serialize(value, generator, provider)
        }
    }
}
