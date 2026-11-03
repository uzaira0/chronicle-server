package com.openlattice.chronicle.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.databind.JsonNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyCreateResponse
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.authorization.AccessCheck
import com.openlattice.chronicle.authorization.Ace
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.AclData
import com.openlattice.chronicle.authorization.AclExplanation
import com.openlattice.chronicle.authorization.Authorization
import com.openlattice.chronicle.authorization.AuthorizedObjectsSearchResult
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.study.EnrollmentResponse
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ExchangeParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.services.delete.DataDeletionOperation
import com.openlattice.chronicle.services.download.StudyExportAvailability
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentBootstrapResponse
import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.EnrollmentManifest
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.study.StudySettingsAuditEntry
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.ParticipantDataPurgeSummary
import com.openlattice.chronicle.study.ParticipantPurgeRequest
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageBlock
import com.openlattice.chronicle.survey.AppUsageDayApp
import com.openlattice.chronicle.survey.AppUsageDaySurvey
import com.openlattice.chronicle.survey.AppUsageSurveyDay
import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.openlattice.chronicle.survey.Question
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.openlattice.chronicle.users.DirectedAclKeys
import com.openlattice.chronicle.users.UserSearchFields
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookDeliveryInfo
import com.openlattice.chronicle.webhooks.WebhookRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import java.lang.reflect.AnnotatedParameterizedType
import java.lang.reflect.AnnotatedType
import java.util.Optional
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.springframework.http.ResponseEntity

class OpenApiModelContractTest {
    private val mapper = ObjectMappers.getJsonMapper()
    private val document: JsonNode = checkNotNull(javaClass.getResourceAsStream("/openapi-schemas.json")).use {
        mapper.readTree(it)
    }
    private val schemas: JsonNode = document.path("schemas")
    private val typedModelContracts = listOf(ApiKeyCreateRequest::class, ApiKeyCreateResponse::class, ApiKeyInfo::class,
        CreateParticipantFormAccessCodeRequest::class, ExchangeParticipantFormAccessCodeRequest::class,
        ParticipantFormAccessCodeResponse::class, ParticipantFormSessionResponse::class, MobileReminderConfiguration::class,
        MobileReminderForm::class, StudyCloneRequest::class, ParticipantPurgeRequest::class, ParticipantDataPurgeSummary::class,
        ComplianceViolation::class, StudyExportAvailability::class, ReviewerEnrollmentBootstrapResponse::class,
        WebhookCreateRequest::class, WebhookDeliveryInfo::class, WebhookRegistration::class, Question::class,
        TimeUseDiaryResponse::class)
    private val studySettingVariants = StudySetting::class.java.getAnnotation(JsonSubTypes::class.java).value.toList()

    @Test fun documentedPropertiesMatchTheProductionJacksonContract() {
        val mismatches = mutableListOf<String>()
        for (model in listOf(Questionnaire::class.java, QuestionnaireResponse::class.java, AppUsageSurveyDays::class.java,
            AppUsageSurveyDay::class.java, AppUsageDaySurvey::class.java, AppUsageDayApp::class.java, AppUsageBlock::class.java,
            AppUsageAnswer::class.java,
            EnrollmentManifest::class.java, ParticipantNotification::class.java, Ace::class.java, AclExplanation::class.java,
            DirectedAclKeys::class.java, Organization::class.java, DeviceUsage::class.java, StudySettingsAuditEntry::class.java,
            CollectionAcknowledgmentEntry::class.java, AndroidDeviceSensorAvailability::class.java, StudyLimits::class.java,
            DataDeletionOperation::class.java, Study::class.java, MobileParticipationStatus::class.java,
            CaptureWindow::class.java, HardStop::class.java, EnrollmentResponse::class.java) + typedModelContracts.map { it.java } +
            studySettingVariants.map { it.value.java }) {
            val serialized = mapper.serializationConfig.introspect(mapper.constructType(model)).findProperties()
                .filter { it.couldSerialize() }.map { it.name }.toSet()
            val documented = schemas.path(model.simpleName).path("properties").fieldNames().asSequence().toSet() - "@class"
            if (serialized != documented) {
                mismatches += "${model.simpleName}: undocumented=${serialized - documented}, retired=${documented - serialized}"
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    /** Generated web types follow the spec, so a field the server always needs or may null must say so there. */
    @Test fun documentedRequiredAndNullableFieldsMatchTheKotlinConstructors() {
        val mismatches = mutableListOf<String>()
        for (model in listOf(Questionnaire::class, QuestionnaireResponse::class, QuestionnaireUpdate::class, AppUsageSurveyDays::class,
            AppUsageSurveyDay::class, AppUsageDaySurvey::class, AppUsageDayApp::class, AppUsageBlock::class, AppUsageAnswer::class,
            DeviceUsage::class, ParticipantNotification::class, StudySettingsAuditEntry::class, ChronicleUserProfile::class,
            Organization::class, Ace::class, Acl::class, AclData::class, AclExplanation::class, AccessCheck::class,
            Authorization::class, AuthorizedObjectsSearchResult::class, Principal::class, DirectedAclKeys::class,
            UserSearchFields::class, IosBatterySample::class,
            DataDeletionOperation::class, MobileParticipationStatus::class, CaptureWindow::class, HardStop::class,
            EnrollmentResponse::class) + typedModelContracts + studySettingVariants.map { it.value }) {
            val schema = schemas.path(checkNotNull(model.simpleName))
            val serialized = mapper.serializationConfig.introspect(mapper.constructType(model.java)).findProperties()
                .filter { it.couldSerialize() }.map { it.name }.toSet()
            val parameters = checkNotNull(model.primaryConstructor).parameters.filter { it.jsonName() in serialized }
            val required = parameters.filter { !it.isOptional }.map { it.jsonName() }.toSet()
            val documentedRequired = schema.path("required").map { it.asText() }.toSet()
            if (required != documentedRequired) {
                mismatches += "${model.simpleName} required: missing=${required - documentedRequired}, " +
                    "extra=${documentedRequired - required}"
            }
            parameters.filter {
                it.type.isMarkedNullable != schema.path("properties").path(it.jsonName()).path("nullable").asBoolean(false)
            }.forEach { mismatches += "${model.simpleName}.${it.jsonName()} nullable=${it.type.isMarkedNullable}" }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun KParameter.jsonName(): String = findAnnotation<JsonProperty>()?.value ?: checkNotNull(name)

    @Test fun documentedStudySettingVariantsMatchTheAllowedJacksonTypes() {
        val expected = studySettingVariants.associate { "#/components/schemas/${it.value.simpleName}" to it.name }
        val declared = schemas.path("StudySetting").path("oneOf").associate { variant ->
            val parts = variant.path("allOf").toList()
            val reference = parts.firstOrNull { it.has("\$ref") }?.path("\$ref")?.asText().orEmpty()
            val name = parts.firstOrNull { it.has("properties") }?.path("properties")?.path("@class")
                ?.path("enum")?.firstOrNull()?.asText().orEmpty()
            reference to name
        }
        assertEquals(expected, declared)
    }

    @Test fun primitiveAndEmptyControllerResponsesMatchThePublishedOperations() {
        val paths = document.path("paths")
        assertFalse("The generated contract must include operations", paths.isMissingNode)
        val controllers = SpecToControllerParityTest().discoverControllerEndpoints().groupBy {
            canonicalPath(it.path) to it.method.lowercase()
        }
        val mismatches = mutableListOf<String>()
        for ((path, operations) in paths.fields()) {
            for ((verb, operation) in operations.fields()) {
                for (endpoint in controllers[canonicalPath(path) to verb].orEmpty()) {
                    val type = endpoint.handler?.genericReturnType?.let(::primitiveWireType) ?: continue
                    mismatches += responseMismatches(verb, path, operation, type)
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches.distinct())
    }

    private fun canonicalPath(path: String): String =
        path.removePrefix("/chronicle").replace(Regex("\\{[^}]+\\}"), "{}").trimEnd('/')

    private fun resolveSchema(schema: JsonNode): JsonNode =
        if (schema.has("\$ref")) schemas.at(schema.path("\$ref").asText().removePrefix("#/components/schemas")) else schema

    private fun responseMismatches(verb: String, path: String, operation: JsonNode, type: String): List<String> {
        val mismatches = mutableListOf<String>()
        for ((status, response) in operation.path("responses").fields()) {
            if (!status.startsWith('2')) continue
            val content = response.path("content")
            val documented = resolveSchema(content.path("application/json").path("schema"))
            val matches = if (type == "void") content.size() == 0 else documented.path("type").asText() == type
            if (!matches) {
                mismatches += "$verb $path $status: controller=$type, documented=${documented.path("type").asText("empty")}"
            }
        }
        return mismatches
    }

    private fun primitiveWireType(type: Type): String? {
        if (type is ParameterizedType && type.rawType == ResponseEntity::class.java) {
            return type.actualTypeArguments.singleOrNull()?.let(::primitiveWireType)
        }
        // StringHttpMessageConverter passes pre-serialized JSON through unchanged.
        return when (type) {
            Void.TYPE, Void::class.java, Unit::class.java -> "void"
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> "boolean"
            Byte::class.javaPrimitiveType, Short::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Byte::class.javaObjectType, Short::class.javaObjectType, Int::class.javaObjectType, Long::class.javaObjectType -> "integer"
            Float::class.javaPrimitiveType, Double::class.javaPrimitiveType, Float::class.javaObjectType, Double::class.javaObjectType -> "number"
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> "string"
            else -> null
        }
    }

    @Test fun documentedBoundsMatchEveryApiModelFieldAndContainerElement() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter { _, _ -> true }
        val apiLocation = com.openlattice.chronicle.study.ParticipantAnnotationsUpdate::class.java.protectionDomain.codeSource.location
        val domainLocation = Study::class.java.protectionDomain.codeSource.location
        val settingModels = studySettingVariants.map { it.value.java }.toSet()
        val mismatches = mutableListOf<String>()
        val models = scanner.findCandidateComponents("com.openlattice.chronicle").map {
            Class.forName(it.beanClassName, false, javaClass.classLoader)
        }.filter { model ->
            when (model.protectionDomain.codeSource?.location) {
                apiLocation -> true
                domainLocation -> schemas.has(model.simpleName) || model in settingModels
                else -> false
            }
        }
        for (model in models) {
            for (property in mapper.serializationConfig.introspect(mapper.constructType(model)).findProperties()) {
                val field = property.field?.annotated
                val getter = property.getter?.annotated
                val type = field?.annotatedType ?: getter?.annotatedReturnType ?: continue
                compareBounds(type, field?.annotations?.toList().orEmpty() + getter?.annotations?.toList().orEmpty(),
                    schemas.path(model.simpleName).path("properties").path(property.name),
                    "${model.simpleName}.${property.name}", mismatches)
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun compareBounds(type: AnnotatedType, annotations: List<Annotation>, schema: JsonNode,
                              path: String, mismatches: MutableList<String>) {
        val resolved = resolveSchema(schema)
        val constraints = (annotations + type.annotations).distinct()
        for (constraint in constraints) {
            when (constraint) {
                is Size -> compareSize(constraint, constraints, resolved, path, mismatches)
                is Min -> if (resolved.path("minimum").asLong(Long.MIN_VALUE) != constraint.value) mismatches += "$path minimum"
                is Max -> if (resolved.path("maximum").asLong(Long.MAX_VALUE) != constraint.value) mismatches += "$path maximum"
            }
        }
        if (type is AnnotatedParameterizedType) {
            val raw = (type.type as java.lang.reflect.ParameterizedType).rawType
            type.annotatedActualTypeArguments.forEachIndexed { index, element ->
                val nested = nestedSchema(raw, index, resolved)
                compareBounds(element, emptyList(), nested, "$path[$index]", mismatches)
            }
        }
    }

    private fun compareSize(constraint: Size, constraints: List<Annotation>, schema: JsonNode,
                            path: String, mismatches: MutableList<String>) {
        val unit = when (schema.path("type").asText()) {
            "array" -> "Items"
            "object" -> "Properties"
            else -> "Length"
        }
        val minimum = maxOf(constraint.min, if (constraints.any { it is NotBlank || it is NotEmpty }) 1 else 0)
        if (unit == "Length") {
            compareUtf16Size(constraint, minimum, schema, path, mismatches)
            return
        }
        if (schema.path("min$unit").asInt(0) != minimum || schema.path("max$unit").asInt(Int.MAX_VALUE) != constraint.max) {
            mismatches += "$path @Size(${constraint.min}, ${constraint.max}) differs from OpenAPI"
        }
    }

    private fun compareUtf16Size(constraint: Size, minimum: Int, schema: JsonNode,
                                 path: String, mismatches: MutableList<String>) {
        val unicodeMinimum = minimum / 2 + minimum % 2
        val expected = mapOf("x-minLengthUtf16" to minimum, "x-maxLengthUtf16" to constraint.max,
            "minLength" to unicodeMinimum, "maxLength" to constraint.max)
        val documented = expected.mapValues { (key, _) ->
            schema.path(key).asInt(if (key.contains("maxLength")) Int.MAX_VALUE else 0)
        }
        if (documented != expected) mismatches += "$path @Size(${constraint.min}, ${constraint.max}) differs from OpenAPI UTF-16 bounds"
    }

    private fun nestedSchema(raw: Type, index: Int, schema: JsonNode): JsonNode = when {
        raw == Optional::class.java -> schema
        raw is Class<*> && Map::class.java.isAssignableFrom(raw) && index == 1 -> schema.path("additionalProperties")
        raw is Class<*> && Collection::class.java.isAssignableFrom(raw) -> schema.path("items")
        else -> com.fasterxml.jackson.databind.node.MissingNode.getInstance()
    }
}
