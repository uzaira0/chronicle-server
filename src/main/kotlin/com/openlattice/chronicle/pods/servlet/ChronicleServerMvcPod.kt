/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 *
 * You can contact the owner of the copyright at support@openlattice.com
 */
// DEPRECATION: intentionally registers the @Deprecated CandidateController for component
// scanning; the prior wildcard import masked this warning. Suppress keeps -Werror green
// without changing scan behavior.
@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.pods.servlet

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.constants.CustomMediaType
import com.openlattice.chronicle.controllers.AdminController
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.controllers.CandidateController
import com.openlattice.chronicle.controllers.ChronicleServerExceptionHandler
import com.openlattice.chronicle.controllers.ExportController
import com.openlattice.chronicle.controllers.NotificationController
import com.openlattice.chronicle.controllers.PrincipalDirectoryController
import com.openlattice.chronicle.controllers.StudyController
import com.openlattice.chronicle.controllers.TestHookController
import com.openlattice.chronicle.controllers.TokenRevocationController
import com.openlattice.chronicle.controllers.legacy.ChronicleController
import com.openlattice.chronicle.controllers.v2.ChronicleControllerV2
import com.openlattice.chronicle.converters.LegacyPostgresDownloadCsvHttpMessageConverter
import com.openlattice.chronicle.converters.PostgresDownloadCsvHttpMessageConverter
import com.openlattice.chronicle.converters.TimeUseDiaryDownloadCsvHttpMessageConverter
import com.openlattice.chronicle.converters.YamlHttpMessageConverter
import com.openlattice.chronicle.i18n.Messages
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import jakarta.inject.Inject
import java.util.Locale

@Configuration
// basePackageClasses selects packages to scan; every controller under the listed controller
// packages is discovered recursively. It is not an allowlist of individual controller types.
@Suppress("DEPRECATION")
@ComponentScan(
    basePackageClasses = [
        AuthTokenController::class,
        CandidateController::class,
        ChronicleController::class,
        ChronicleServerExceptionHandler::class,
        ChronicleControllerV2::class,
        NotificationController::class,
        StudyController::class,
        AdminController::class,
        PrincipalDirectoryController::class,
        TokenRevocationController::class,
        ExportController::class,
        TestHookController::class
    ],
    includeFilters = [ComponentScan.Filter(
        value = [Controller::class, RestControllerAdvice::class],
        type = FilterType.ANNOTATION
    )]
)
@EnableAsync
public open class ChronicleServerMvcPod : WebMvcConfigurationSupport() {
    @Inject
    private lateinit var defaultObjectMapper: ObjectMapper

    override fun configureMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        super.addDefaultHttpMessageConverters(converters)
        for (converter in converters) {
            if (converter is MappingJackson2HttpMessageConverter) {
                converter.objectMapper = defaultObjectMapper
            }
        }
        converters.add(PostgresDownloadCsvHttpMessageConverter())
        converters.add(LegacyPostgresDownloadCsvHttpMessageConverter())
        converters.add(TimeUseDiaryDownloadCsvHttpMessageConverter())
        converters.add(YamlHttpMessageConverter())
    }

    @Suppress("DEPRECATION")
    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        configurer.setUseTrailingSlashMatch(true)
    }

    override fun configureContentNegotiation(configurer: ContentNegotiationConfigurer) {
        configurer.parameterName(FILE_TYPE)
            .favorParameter(true)
            .mediaType("csv", CustomMediaType.TEXT_CSV)
            .mediaType("json", MediaType.APPLICATION_JSON)
            .mediaType("yaml", CustomMediaType.TEXT_YAML)
            .defaultContentType(MediaType.APPLICATION_JSON)
    }

    /**
     * Selects the response language from the request's `Accept-Language` header, falling back to
     * English when the header is absent or names a language with no `messages_<lang>.properties`
     * table. The resolved locale lands in `LocaleContextHolder`, which [Messages] reads.
     */
    override fun localeResolver(): LocaleResolver = AcceptHeaderLocaleResolver().apply {
        setDefaultLocale(Locale.ENGLISH)
    }

    /** Exposes the message table used by [Messages] as the context's `messageSource`. */
    @Bean
    public open fun messageSource(): MessageSource = Messages.source

    internal companion object {
        public const val FILE_TYPE = "fileType"
    }
}
