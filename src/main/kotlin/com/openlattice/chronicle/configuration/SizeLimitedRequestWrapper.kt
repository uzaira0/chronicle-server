package com.openlattice.chronicle.configuration

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Objects

/** Permit EOF at exactly the limit; consume at most one extra byte to distinguish overflow. */
internal class SizeLimitedRequestWrapper(request: HttpServletRequest, maxBytes: Long) : HttpServletRequestWrapper(request) {
    private val limitedStream by lazy { SizeLimitedInputStream(super.getInputStream(), maxBytes) }
    override fun getInputStream(): ServletInputStream = limitedStream
    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream,
        characterEncoding?.let { Charset.forName(it) } ?: StandardCharsets.ISO_8859_1))
}

private class SizeLimitedInputStream(private val original: ServletInputStream, private val maxBytes: Long) : ServletInputStream() {
    private var bytesRead = 0L

    override fun read(): Int {
        checkLimit()
        val value = original.read()
        if (value != -1) count(1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        Objects.checkFromIndexSize(offset, length, bytes.size)
        if (length == 0) return 0
        checkLimit()
        val allowed = minOf(length.toLong(), maxBytes - bytesRead + 1).toInt()
        val read = original.read(bytes, offset, allowed)
        if (read > 0) count(read)
        return read
    }

    private fun count(read: Int) {
        bytesRead += read
        checkLimit()
    }

    private fun checkLimit() {
        if (bytesRead > maxBytes) throw RequestBodyTooLargeException()
    }

    override fun close() = original.close()
    override fun isFinished(): Boolean = original.isFinished
    override fun isReady(): Boolean = original.isReady
    override fun setReadListener(listener: ReadListener?) = original.setReadListener(listener)
}

internal class RequestBodyTooLargeException : IOException("Request body exceeded maximum size")
