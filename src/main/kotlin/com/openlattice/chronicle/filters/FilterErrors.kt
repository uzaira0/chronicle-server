package com.openlattice.chronicle.filters

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.controllers.ApiError
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus

internal fun filterError(status: Int, message: String, code: String? = null, retryAfterSeconds: Long? = null): ApiError =
    ApiError(status, HttpStatus.resolve(status)?.reasonPhrase ?: "Request Failed", message,
        errorId = ApiError.generateErrorId(), code = code, retryAfterSeconds = retryAfterSeconds)

internal fun writeFilterError(
    response: HttpServletResponse,
    status: Int,
    message: String,
    code: String? = null,
    retryAfterSeconds: Long? = null,
) {
    response.status = status
    response.contentType = "application/json"
    // JSON is UTF-8 (RFC 8259); bytes avoid the container's ISO-8859-1 writer default without a charset parameter.
    val body = filterError(status, message, code, retryAfterSeconds)
    response.outputStream.write(ObjectMappers.getJsonMapper().writeValueAsBytes(body))
}
