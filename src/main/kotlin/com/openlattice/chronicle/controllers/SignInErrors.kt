package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.i18n.Messages
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** A sign-in feature this server does not offer answers 404 with the standard ApiError body. */
internal fun signInNotFound(messageKey: String) = ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get(messageKey))
