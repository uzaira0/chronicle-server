package com.openlattice.chronicle.filters

import com.geekbeast.mappers.mappers.ObjectMappers
import jakarta.servlet.http.HttpServletResponse

internal fun writeFilterError(response: HttpServletResponse, status: Int, message: String) {
    response.status = status
    response.contentType = "application/json"
    // JSON is UTF-8 (RFC 8259); bytes avoid the container's ISO-8859-1 writer default without a charset parameter.
    response.outputStream.write(ObjectMappers.getJsonMapper().writeValueAsBytes(linkedMapOf("status" to status, "message" to message)))
}
