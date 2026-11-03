package com.openlattice.chronicle.converters

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractGenericHttpMessageConverter
import java.lang.reflect.Type

/** Streams a participant download as one JSON array without holding it in memory. */
public class PostgresDownloadJsonHttpMessageConverter(
    private val mapper: ObjectMapper,
) : AbstractGenericHttpMessageConverter<PostgresDownloadWrapper>(MediaType.APPLICATION_JSON) {
    override fun supports(clazz: Class<*>): Boolean = PostgresDownloadWrapper::class.java.isAssignableFrom(clazz)

    override fun canRead(type: Type, contextClass: Class<*>?, mediaType: MediaType?): Boolean = false

    override fun writeInternal(t: PostgresDownloadWrapper, type: Type?, outputMessage: HttpOutputMessage) {
        writeJsonRows(mapper.writer(), outputMessage.body, t)
    }

    override fun read(type: Type, contextClass: Class<*>?, inputMessage: HttpInputMessage): PostgresDownloadWrapper =
        throw UnsupportedOperationException("Participant downloads are not a request body")

    override fun readInternal(clazz: Class<out PostgresDownloadWrapper>, inputMessage: HttpInputMessage): PostgresDownloadWrapper =
        throw UnsupportedOperationException("Participant downloads are not a request body")
}
