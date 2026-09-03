package com.openlattice.chronicle.validation

import com.openlattice.chronicle.i18n.Messages
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

private val EMAIL_PATTERN = Regex(".*@.*\\..*")
private val SSN_PATTERN = Regex("\\d{3}-\\d{2}-\\d{4}")
private val PHONE_PATTERN = Regex("^\\+?\\d[\\d\\s-]{8,}$")
private val WHITESPACE_PATTERN = Regex("\\s+")
private const val MAX_PARTICIPANT_ID_LENGTH = 255

// reason: PII-rejection validator legitimately raises one ResponseStatusException per distinct PII
// shape detected (length / email / SSN / phone / name); each is a separate 400 reason
@Suppress("ThrowsCount")
public fun validateParticipantIdNotPii(participantId: String) {
    if (participantId.length > MAX_PARTICIPANT_ID_LENGTH) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            Messages.format("error.participantId.tooLong", MAX_PARTICIPANT_ID_LENGTH.toString()),
        )
    }
    val trimmed = participantId.trim()
    when {
        EMAIL_PATTERN.matches(trimmed) ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.participantId.email"))
        SSN_PATTERN.matches(trimmed) ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.participantId.ssn"))
        PHONE_PATTERN.matches(trimmed) ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.participantId.phone"))
        looksLikeFullName(trimmed) ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.participantId.name"))
    }
}

private fun looksLikeFullName(value: String): Boolean {
    val parts = value.trim().split(WHITESPACE_PATTERN).filter { it.isNotEmpty() }
    return parts.size >= 2 && parts.all(::looksLikeCapitalizedNamePart)
}

private fun looksLikeCapitalizedNamePart(part: String): Boolean {
    return part.length >= 2 &&
        part[0].isUpperCase() &&
        part.drop(1).all { it.isLowerCase() }
}
