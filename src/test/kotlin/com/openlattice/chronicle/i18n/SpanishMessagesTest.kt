package com.openlattice.chronicle.i18n

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.pods.servlet.ChronicleServerMvcPod
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.surveys.AppUsageDayConflictException
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.survey.AppUsageDayConflict
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.validation.validateParticipantIdNotPii
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

/** A request carrying [acceptLanguage], as Jetty parses it. */
internal fun requestWithLanguage(acceptLanguage: String, method: String = "GET", uri: String = "/") =
    MockHttpServletRequest(method, uri).apply { addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage) }

/** Runs [block] in the locale the server's own resolver picks for [acceptLanguage], as the DispatcherServlet does. */
internal fun <T> inRequestLanguage(acceptLanguage: String, block: () -> T): T {
    LocaleContextHolder.setLocale(
        ChronicleServerMvcPod(ObjectMapper()).localeResolver().resolveLocale(requestWithLanguage(acceptLanguage)),
    )
    return try {
        block()
    } finally {
        LocaleContextHolder.resetLocaleContext()
    }
}

class SpanishMessagesTest {
    @After
    fun clearContexts() {
        LocaleContextHolder.resetLocaleContext()
        SecurityContextHolder.clearContext()
        RLSRequestContext.clear()
    }

    @Test
    fun esClSpanishResolvesTheSpanishTable() {
        assertEquals("Encuesta de Chronicle", inRequestLanguage("es-CL,es;q=0.9") { Messages.get("reminder.appUsage.title") })
        assertEquals("Encuesta de Chronicle", inRequestLanguage("es") { Messages.get("reminder.appUsage.title") })
    }

    @Test
    fun englishAndUntranslatedLanguagesStayEnglish() {
        for (language in listOf("en-US", "sv-SE", "")) {
            assertEquals(language, "Chronicle Survey", inRequestLanguage(language) { Messages.get("reminder.appUsage.title") })
        }
        assertEquals("Chronicle Survey", Messages.en("reminder.appUsage.title"))
    }

    @Test
    fun appUsageConflictsReachTheSurveyPageInSpanish() {
        val reason = inRequestLanguage("es-CL") { AppUsageDayConflictException(AppUsageDayConflict.ALREADY_ANSWERED).reason }
        assertEquals("Este día ya tiene una respuesta para una de estas aplicaciones.", reason)
        val english = inRequestLanguage("en") { AppUsageDayConflictException(AppUsageDayConflict.ALREADY_ANSWERED).reason }
        assertEquals("This day already has an answer for one of these apps.", english)
    }

    @Test
    fun formFilterUsesTheServletParsedLanguage() {
        val service = mock<ParticipantFormAccessService>()
        val studyId = UUID.randomUUID()
        val participantId = "participant:test"
        whenever(service.resolveSession(any(), any(), eq(true))).thenReturn(ParticipantFormAccessScope(
            accessCodeId = UUID.randomUUID(),
            studyId = studyId,
            participantId = participantId,
            formKind = ParticipantFormKind.TIME_USE_DIARY,
            resourceId = null,
            logicalDate = LocalDate.now(),
            absoluteExpiresAt = OffsetDateTime.now().plusHours(1),
        ))
        fun send(language: String): MockHttpServletResponse {
            val request = requestWithLanguage(language, "POST",
                TimeUseDiaryApi.BASE + "/$studyId" + TimeUseDiaryApi.PARTICIPANT_PATH + "/$participantId").apply {
                setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session"))
                addHeader(ParticipantFormAccessFilter.CSRF_HEADER, "csrf")
                addHeader(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                addHeader(ChronicleHeaders.PARTICIPANT_FORM_DATE_HEADER, "not-a-day")
            }
            return MockHttpServletResponse().also {
                ParticipantFormAccessFilter(service).doFilter(request, it, FilterChain { _, _ -> })
            }
        }
        val spanish = send("es-CL")
        assertEquals(400, spanish.status)
        assertEquals("Este formulario requiere un día calendario válido.", spanish.errorMessage)
        assertEquals("A valid calendar day is required for this form.", send("en-US").errorMessage)
    }

    @Test
    fun researcherValidationErrorsAreSpanishWithTheirArguments() {
        val email = assertThrows(ResponseStatusException::class.java) {
            inRequestLanguage("es-CL") { validateParticipantIdNotPii("parent@example.com") }
        }
        assertEquals("El ID del participante no debe contener una dirección de correo electrónico", email.reason)
        val tooLong = assertThrows(ResponseStatusException::class.java) {
            inRequestLanguage("es-CL") { validateParticipantIdNotPii("p".repeat(256)) }
        }
        assertEquals("El ID del participante supera el máximo de 255 caracteres", tooLong.reason)
        assertEquals("Se requiere autenticación", inRequestLanguage("es") { Messages.get("error.unauthorized") })
    }

    @Test
    fun everySpanishRowMatchesAnEnglishKeyAndItsPlaceholders() {
        val english = table("messages.properties")
        val spanish = table("messages_es.properties")
        assertTrue("messages_es.properties is empty", spanish.isNotEmpty())
        for ((key, value) in spanish) {
            val source = english[key]
            assertTrue("$key is not in messages.properties", source != null)
            assertEquals("$key placeholders", placeholders(source!!), placeholders(value))
            if (placeholders(value).isNotEmpty()) {
                // MessageFormat swallows a single apostrophe and everything quoted after it.
                assertTrue("$key: double the apostrophe", !value.replace("''", "").contains('\''))
            }
        }
    }

    private fun placeholders(value: String): Set<String> = Regex("""\{\d+}""").findAll(value).map { it.value }.toSet()

    private fun table(name: String): Map<String, String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "$name missing" }
        return InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            Properties().apply { load(reader) }.entries.associate { (key, value) -> key.toString() to value.toString() }
        }
    }
}
