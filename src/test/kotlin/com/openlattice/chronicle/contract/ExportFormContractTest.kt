package com.openlattice.chronicle.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openlattice.chronicle.controllers.SurveyController
import com.openlattice.chronicle.controllers.TimeUseDiaryController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

/**
 * Binds every documented field of the browser-form export POSTs to the parameter the server handler binds: the
 * same names, the same required flags, and the media types the handler writes.
 */
class ExportFormContractTest {
    private data class Export(val operationId: String, val controller: KClass<*>, val handler: String, val media: Set<String>)

    private val exports = listOf(
        Export("postGetQuestionnaireResponses", SurveyController::class, "postQuestionnaireResponses",
            setOf("application/json", "text/csv")),
        Export("postGetStudyTUDSubmissions", TimeUseDiaryController::class, "postStudyTUDSubmissions", setOf("text/csv")),
        Export("postGetParticipantsTudSubmissions", TimeUseDiaryController::class, "postParticipantsTudSubmissions",
            setOf("text/csv")),
    )

    @Test
    fun exportFormFieldsAndResponseMediaMatchTheServerHandlers() {
        val operations = ObjectMapper(YAMLFactory()).readTree(File("../chronicle-api/chronicle.yaml"))["paths"]
            .flatMap { path -> path.toList() }.filter { it.has("operationId") }.associateBy { it["operationId"].asText() }
        for (export in exports) {
            val operation = checkNotNull(operations[export.operationId]) { export.operationId }
            val schema = operation["requestBody"]["content"]["application/x-www-form-urlencoded"]["schema"]
            val required = schema["required"]?.map { it.asText() }?.toSet().orEmpty()
            val documented = schema["properties"].fieldNames().asSequence().filter { it != "_csrf" }
                .associateWith { it in required }
            assertEquals(export.operationId, serverFields(export), documented)
            assertEquals(export.operationId, export.media, operation["responses"]["200"]["content"].fieldNames().asSequence().toSet())
        }
    }

    /** Field name to required, as Spring binds the handler's @RequestParam and @ModelAttribute parameters. */
    private fun serverFields(export: Export): Map<String, Boolean> {
        val handler = export.controller.members.single {
            it.name == export.handler && it.findAnnotation<PostMapping>() != null
        }
        return handler.parameters.flatMap { parameter ->
            val param = parameter.findAnnotation<RequestParam>()
            when {
                param != null -> listOf(param.value to !parameter.type.isMarkedNullable)
                parameter.findAnnotation<ModelAttribute>() != null ->
                    checkNotNull(parameter.type.jvmErasure.primaryConstructor).parameters.map {
                        checkNotNull(it.name) to (!it.isOptional && !it.type.isMarkedNullable)
                    }
                else -> emptyList()
            }
        }.toMap()
    }
}
