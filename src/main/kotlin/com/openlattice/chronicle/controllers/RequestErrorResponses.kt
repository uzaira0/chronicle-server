package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.generated.CollectionConsentContracts
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.studies.CollectionAcknowledgmentRejectedException
import com.openlattice.chronicle.services.upload.UploadRowRejectedException
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.context.SecurityContextHolder

internal class RequestErrorResponses(private val config: ErrorSanitizationConfig) {
    companion object {
        const val JSON_CONSTRAINT_EXCEEDED = "JSON_CONSTRAINT_EXCEEDED"
    }

    /**
     * The row-level code marks a row's own defect, the ack-rejected code an acknowledgment the server can never
     * accept, and the JSON-constraint code a body the parser refused.
     */
    fun bodyErrorCode(e: Exception): String? = when {
        e is UploadRowRejectedException -> UploadLimits.UPLOAD_ROW_REJECTED_CODE
        e is CollectionAcknowledgmentRejectedException -> CollectionConsentContracts.COLLECTION_ACK_REJECTED_CODE
        generateSequence<Throwable>(e) { it.cause }.any { it is StreamConstraintsException } -> JSON_CONSTRAINT_EXCEEDED
        else -> null
    }
    /**
     * Bean validation of a device upload checks the fields of its rows, so on a device key's request it is a row
     * defect; any other request's validation failure carries no code.
     */
    fun validationErrorCode(): String? = UploadLimits.UPLOAD_ROW_REJECTED_CODE.takeIf {
        (SecurityContextHolder.getContext().authentication as? ApiKeyAuthenticationToken)?.participantId != null
    }

    fun invalidArgumentMessage(e: Exception): String = when {
        e is HttpMessageNotReadableException -> Messages.get("error.request.invalidBody")
        config.sanitizeErrors -> config.sanitizeMessage(e.message)
        else -> e.message ?: Messages.get("error.request.invalid")
    }
}
