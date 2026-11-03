package com.openlattice.chronicle.contract

import com.geekbeast.mappers.mappers.ObjectMappers
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** Length-only counterexamples shared with actual Ajv; this does not assert general Bean Validation parity. */
class OpenApiStringLengthContractTest {
    @Test fun declaredUtf16BudgetsAgreeWithThePublicBeanValidatorAtSupplementaryBoundaries() {
        val mapper = ObjectMappers.getJsonMapper()
        val schemas = checkNotNull(javaClass.getResourceAsStream("/openapi-schemas.json")).use {
            mapper.readTree(it).path("schemas")
        }
        val fields = checkNotNull(javaClass.getResourceAsStream("/string-length-cases.json")).use { mapper.readTree(it) }
        Validation.buildDefaultValidatorFactory().use { factory ->
            for (field in fields) {
                compareField(factory.validator, schemas, field)
            }
        }
    }

    private fun compareField(validator: Validator, schemas: JsonNode, field: JsonNode) {
        val model = Class.forName(field.path("className").asText())
        val property = field.path("property").asText()
        val name = field.path("model").asText()
        val schema = schemas.path(name).path("properties").path(property)
        val maximum = schema.path("x-maxLengthUtf16").asInt(-1)
        val minimum = schema.path("x-minLengthUtf16").asInt(0)
        check(maximum >= 0) { "$name.$property needs an explicit UTF-16 length budget" }
        for (value in boundarySamples(minimum, maximum)) {
            val lengthViolations = validator.validateValue(model, property, value).filter {
                val annotation = it.constraintDescriptor.annotation
                annotation is Size || annotation is NotBlank || annotation is NotEmpty
            }
            assertEquals(
                "$name.$property at ${value.length} UTF-16 units",
                value.length in minimum..maximum,
                lengthViolations.isEmpty(),
            )
        }
    }

    private fun boundarySamples(minimum: Int, maximum: Int): List<String> = listOf(
        "",
        "a".repeat(maximum),
        "a".repeat(maximum + 1),
        "\uD83D\uDE00".repeat(maximum / 2) + if (maximum % 2 == 0) "" else "a",
        "\uD83D\uDE00".repeat(maximum),
        "\uD83D\uDE00".repeat((minimum + 1) / 2),
    )
}
