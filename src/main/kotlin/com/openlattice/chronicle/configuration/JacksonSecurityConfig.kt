/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.datatype.guava.GuavaModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.blackbird.BlackbirdModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.serializers.FullQualifiedNameJacksonSerializer.registerWithMapper
import com.openlattice.chronicle.serializers.StudyJacksonMixin
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.generated.UploadLimits
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Security-hardened Jackson ObjectMapper configuration.
 *
 * This configuration addresses several critical security concerns:
 *
 * 1. POLYMORPHIC DESERIALIZATION ATTACKS (CVE-2017-7525 and related):
 *    - Default typing is DISABLED by default
 *    - Polymorphic models use @JsonTypeInfo(Id.NAME) with a @JsonSubTypes list,
 *      so a payload can only name a subtype the model itself lists
 *
 * 2. MASS ASSIGNMENT ATTACKS:
 *    - FAIL_ON_UNKNOWN_PROPERTIES = true rejects unexpected JSON fields
 *    - Attackers cannot inject fields that don't exist on DTOs
 *    - STRICT_DUPLICATE_DETECTION rejects a repeated key, which would otherwise overwrite a
 *      field after the model's init validated it
 *
 * 3. DENIAL OF SERVICE:
 *    - Reasonable limits are enforced via Spring's request size limits
 *    - See SecurityHardeningConfig for request body limits
 *
 * Security Best Practices:
 * - Never enable default typing globally
 * - Always validate input against expected schema
 * - List polymorphic subtypes on the model with @JsonSubTypes
 */
@Configuration
public open class JacksonSecurityConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(JacksonSecurityConfig::class.java)

        /**
         * Applies security hardening settings to an ObjectMapper.
         * Call this on any ObjectMapper that processes untrusted input.
         */
        public fun hardenObjectMapper(mapper: ObjectMapper): ObjectMapper {
            mapper.factory.setStreamReadConstraints(mapper.factory.streamReadConstraints().rebuild()
                .maxDocumentLength(UploadLimits.MAX_REQUEST_SIZE_BYTES).build())
            // CRITICAL: Disable default typing to prevent polymorphic deserialization attacks
            // This is the most important security setting for Jackson
            mapper.deactivateDefaultTyping()

            // Reject unknown properties to prevent mass assignment attacks
            // If a JSON payload contains fields not in the target class, fail fast
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

            // Refuse a repeated key: once every creator argument is read Jackson builds the object, then writes a
            // later repeat straight into the field, past the model's init checks (CollectionAcknowledgment).
            mapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)

            // A malformed scalar must not become a valid notes/tag string.
            for (shape in listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
                mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail)
            }

            // Fail on null for primitives to catch data issues early
            mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)

            // Fail on numbers for enums - don't allow numeric enum values
            mapper.configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, true)

            // NOTE: FAIL_ON_NULL_CREATOR_PROPERTIES is NOT enabled because Chronicle API
            // legitimately uses null IDs for entity creation (IDs are auto-generated by server).
            // This is a valid pattern, not a security issue.

            // Case-sensitive property names (defense in depth)
            @Suppress("DEPRECATION")
            mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, false)

            logger.info("Jackson ObjectMapper security hardening applied: " +
                    "default typing disabled, FAIL_ON_UNKNOWN_PROPERTIES enabled")

            return mapper
        }
    }

    /**
     * Primary secure ObjectMapper bean for the application.
     * This replaces the default ObjectMapper with a security-hardened version.
     */
    @Bean
    @Primary
    public fun secureObjectMapper(): ObjectMapper {
        val mapper = ObjectMapper()

        // Register standard modules
        mapper.registerModule(Jdk8Module())
        mapper.registerModule(JavaTimeModule())
        mapper.registerModule(GuavaModule())
        mapper.registerModule(BlackbirdModule())
        mapper.registerModule(KotlinModule.Builder().build())
        mapper.registerModule(ObjectMappers.setOrderModule())

        // Standard serialization settings
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)

        // Apply security hardening
        hardenObjectMapper(mapper)

        // Register Chronicle-specific serializers
        registerWithMapper(mapper)
        mapper.addMixIn(Study::class.java, StudyJacksonMixin::class.java)

        logger.info("Security-hardened ObjectMapper initialized as primary bean")
        return mapper
    }
}
