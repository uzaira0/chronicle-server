package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.configuration.RequestBodyTooLargeException
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.upload.MAX_UPLOAD_ROWS
import com.openlattice.chronicle.services.upload.requireUploadBatchSize
import jakarta.validation.constraints.Size
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Type

/** Checks the existing wire-row bound before Jackson allocates any row objects. */
@RestControllerAdvice
public class JsonArrayRowLimitAdvice(private val mapper: ObjectMapper) : RequestBodyAdviceAdapter() {
    override fun supports(parameter: MethodParameter, targetType: Type,
                          converterType: Class<out HttpMessageConverter<*>>): Boolean = maximumRows(parameter) != null

    override fun beforeBodyRead(inputMessage: HttpInputMessage, parameter: MethodParameter, targetType: Type,
                                converterType: Class<out HttpMessageConverter<*>>): HttpInputMessage {
        val maximum = maximumRows(parameter) ?: return inputMessage
        val subtype = inputMessage.headers.contentType?.subtype
        if (subtype != null && subtype != "json" && !subtype.endsWith("+json")) return inputMessage
        val original = inputMessage.body
        val maximumBytes = UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt()
        val replay = if (original.markSupported()) original else {
            val bytes = original.readNBytes(maximumBytes + 1)
            if (bytes.size > maximumBytes) throw RequestBodyTooLargeException()
            ByteArrayInputStream(bytes)
        }
        replay.mark(maximumBytes + 1)
        try {
            mapper.factory.createParser(replay).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE).use { parser ->
                countRows(parser, parameter, maximum)
            }
        } catch (failure: JsonProcessingException) {
            throw HttpMessageNotReadableException("Invalid JSON request body", failure, inputMessage)
        }
        replay.reset()
        return object : HttpInputMessage {
            override fun getHeaders(): HttpHeaders = inputMessage.headers
            override fun getBody(): InputStream = replay
        }
    }

    private fun maximumRows(parameter: MethodParameter): Int? = when {
        parameter.parameterType == ChronicleData::class.java -> MAX_UPLOAD_ROWS
        Collection::class.java.isAssignableFrom(parameter.parameterType) ->
            parameter.getParameterAnnotation(Size::class.java)?.max?.takeIf { it < Int.MAX_VALUE }
        else -> null
    }

    private fun countRows(parser: JsonParser, parameter: MethodParameter, maximum: Int) {
        if (parser.nextToken() == JsonToken.START_ARRAY) {
            var count = 0
            var token = parser.nextToken()
            while (token != JsonToken.END_ARRAY) {
                if (token == null) throw JsonParseException(parser, "Unexpected end of JSON array")
                count++
                if (count > maximum) refuseRows(parameter, count, maximum)
                parser.skipChildren()
                token = parser.nextToken()
            }
        } else parser.skipChildren()
        if (parser.nextToken() != null) throw JsonParseException(parser, "Trailing JSON content")
    }

    private fun refuseRows(parameter: MethodParameter, count: Int, maximum: Int) {
        if (parameter.parameterType == ChronicleData::class.java) requireUploadBatchSize(count)
        val name = parameter.parameterName ?: "request"
        val errors = BeanPropertyBindingResult(null, name)
        errors.addError(FieldError(name, name, "${Messages.get("error.request.validationFailed")} (max=$maximum)"))
        throw MethodArgumentNotValidException(parameter, errors)
    }
}
