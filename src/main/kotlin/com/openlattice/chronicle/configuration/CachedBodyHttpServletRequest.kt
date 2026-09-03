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

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.Enumeration
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper

/**
 * A wrapper for HttpServletRequest that caches the request body for multiple reads.
 *
 * This is necessary for HMAC signature verification because:
 * 1. The request body (InputStream) can typically only be read once
 * 2. We need to read the body to compute the HMAC signature
 * 3. The downstream controller/handler also needs to read the body
 *
 * By caching the body bytes, we can provide the body content multiple times
 * through the overridden getInputStream() and getReader() methods.
 *
 * Security considerations:
 * - The cached body is stored in memory, so this should only be used for
 *   requests where body size is controlled (enforce request size limits)
 * - The original request's input stream is fully consumed during construction
 *
 * @author uzaira0
 */
public open class CachedBodyHttpServletRequest(
    request: HttpServletRequest,
    private val cachedBody: ByteArray = request.inputStream.readBytes(),
) : HttpServletRequestWrapper(request) {

    /**
     * Returns the cached request body as a byte array.
     * This is used by the signature verification filter to compute the HMAC.
     */
    public fun getCachedBody(): ByteArray = cachedBody.copyOf()

    /**
     * Returns a new ServletInputStream that reads from the cached body.
     * This allows the request body to be read multiple times.
     */
    override fun getInputStream(): ServletInputStream {
        return CachedBodyServletInputStream(cachedBody)
    }

    /**
     * Returns a new BufferedReader that reads from the cached body.
     * This allows the request body to be read multiple times via getReader().
     */
    override fun getReader(): BufferedReader {
        val inputStream = ByteArrayInputStream(cachedBody)
        return BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    }

    /**
     * A ServletInputStream implementation that reads from a cached byte array.
     */
    private class CachedBodyServletInputStream(
        private val cachedBody: ByteArray
    ) : ServletInputStream() {

        private val inputStream = ByteArrayInputStream(cachedBody)

        override fun read(): Int = inputStream.read()

        override fun read(b: ByteArray): Int = inputStream.read(b)

        override fun read(b: ByteArray, off: Int, len: Int): Int = inputStream.read(b, off, len)

        override fun isFinished(): Boolean = inputStream.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener?) {
            // Not implemented for synchronous reads
            // This is called by async servlet processing which we don't use here
            throw UnsupportedOperationException("Async read listeners are not supported")
        }

        override fun available(): Int = inputStream.available()

        override fun close() = inputStream.close()

        override fun skip(n: Long): Long = inputStream.skip(n)

        override fun mark(readlimit: Int) = inputStream.mark(readlimit)

        override fun reset() = inputStream.reset()

        override fun markSupported(): Boolean = inputStream.markSupported()
    }
}

/**
 * Presents a decoded request body downstream while removing the transport
 * content encoding and correcting the content length.
 */
internal class DecodedBodyHttpServletRequest(
    request: HttpServletRequest,
    private val decodedBody: ByteArray,
) : CachedBodyHttpServletRequest(request, decodedBody) {

    override fun getContentLength(): Int = decodedBody.size

    override fun getContentLengthLong(): Long = decodedBody.size.toLong()

    override fun getHeader(name: String): String? {
        return when {
            name.equals(CONTENT_ENCODING, ignoreCase = true) -> null
            name.equals(CONTENT_LENGTH, ignoreCase = true) -> decodedBody.size.toString()
            else -> super.getHeader(name)
        }
    }

    override fun getHeaders(name: String): Enumeration<String> {
        return when {
            name.equals(CONTENT_ENCODING, ignoreCase = true) -> Collections.emptyEnumeration()
            name.equals(CONTENT_LENGTH, ignoreCase = true) ->
                Collections.enumeration(listOf(decodedBody.size.toString()))
            else -> super.getHeaders(name)
        }
    }

    override fun getHeaderNames(): Enumeration<String> {
        val names = Collections.list(super.getHeaderNames())
            .filterNot { it.equals(CONTENT_ENCODING, ignoreCase = true) }
        return Collections.enumeration(names)
    }

    override fun getIntHeader(name: String): Int {
        return if (name.equals(CONTENT_LENGTH, ignoreCase = true)) {
            decodedBody.size
        } else {
            super.getIntHeader(name)
        }
    }

    private companion object {
        private const val CONTENT_ENCODING = "Content-Encoding"
        private const val CONTENT_LENGTH = "Content-Length"
    }
}
