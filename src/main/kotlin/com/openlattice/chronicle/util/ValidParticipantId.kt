package com.openlattice.chronicle.util

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ParticipantIdConstraintValidator::class])
public annotation class ValidParticipantId(
    val message: String = "Invalid participant ID format",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

public class ParticipantIdConstraintValidator : ConstraintValidator<ValidParticipantId, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return false
        return try { validateParticipantId(value); true } catch (_: IllegalArgumentException) { false }
    }
}
