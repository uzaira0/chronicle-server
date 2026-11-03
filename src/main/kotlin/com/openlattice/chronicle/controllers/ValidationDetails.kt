package com.openlattice.chronicle.controllers

import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.HandlerMethodValidationException

internal object ValidationDetails {
    /** One "path: message" line per violation; a list body's row is named by its index, such as `[3].users[0]`. */
    fun of(exception: Exception): List<String>? = when (exception) {
        is MethodArgumentNotValidException -> exception.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        is HandlerMethodValidationException -> exception.parameterValidationResults.flatMap { result ->
            val row = result.containerIndex?.let { "[$it]" }.orEmpty()
            result.resolvableErrors.map { error ->
                val field = (error as? FieldError)?.field?.let { if (row.isEmpty()) it else "$row.$it" }
                "${field ?: row.ifEmpty { result.methodParameter.parameterName }}: ${error.defaultMessage}"
            }
        }
        else -> emptyList()
    }.ifEmpty { null }
}
