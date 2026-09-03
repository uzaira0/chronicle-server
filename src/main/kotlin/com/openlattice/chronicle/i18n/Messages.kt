/*
 * Copyright (C) 2026. Chronicle.
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
package com.openlattice.chronicle.i18n

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import java.util.Locale

/**
 * Translation support for text the server generates for a human to read.
 *
 * English (`messages.properties`) is the only table this repository maintains. A translator adds
 * `messages_<lang>.properties` next to it and the request's `Accept-Language` header selects it;
 * anything missing from a translated table falls back to the English entry.
 *
 * Field names, enum values, identifiers, audit descriptions and log lines are deliberately NOT
 * routed through here — only strings a participant or researcher reads on a screen.
 */
public object Messages {
    /**
     * Shared [MessageSource]. Held statically rather than injected so that the same table serves
     * request handling, servlet filters (which run before the DispatcherServlet resolves a locale)
     * and plain unit tests that construct responses without a Spring context.
     */
    public val source: MessageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
        // Never let the JVM's host locale decide: an untranslated locale falls back to English.
        setFallbackToSystemLocale(false)
        // A missing key must degrade to the key, never fail a request with NoSuchMessageException.
        setUseCodeAsDefaultMessage(true)
    }

    /** Resolves [key] in the locale Spring negotiated for the current request (English by default). */
    public fun get(key: String): String = resolve(key, LocaleContextHolder.getLocale(), null)

    /** Resolves [key] with `{0}`-style arguments in the current request locale. */
    public fun format(key: String, vararg args: Any?): String =
        resolve(key, LocaleContextHolder.getLocale(), args)

    /**
     * Resolves [key] in the locale the servlet container parsed from `Accept-Language`.
     * Used by security filters, which run before the DispatcherServlet populates
     * [LocaleContextHolder].
     */
    public fun get(key: String, request: HttpServletRequest): String = resolve(key, request.locale, null)

    /** Resolves [key] in English. For audit records, which must stay in one language. */
    public fun en(key: String): String = resolve(key, Locale.ENGLISH, null)

    private fun resolve(key: String, locale: Locale?, args: Array<out Any?>?): String =
        source.getMessage(key, args, locale ?: Locale.ENGLISH)
}
