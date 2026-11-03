package com.openlattice.chronicle.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest

class SizeLimitedRequestWrapperTest {
    @Test fun exactEofAndZeroLengthReadsDoNotOverflow() {
        val request = MockHttpServletRequest().also { it.setContent(byteArrayOf(1, 2, 3, 4)) }
        val bounded = SizeLimitedRequestWrapper(request, 4)
        val stream = bounded.inputStream
        for (value in 1..4) assertEquals(value, stream.read())
        assertEquals(-1, stream.read())
        assertEquals(0, stream.read(byteArrayOf(), 0, 0))
        assertSame(stream, bounded.inputStream)
    }

    @Test fun overflowStaysRejectedAcrossAccessors() {
        val request = MockHttpServletRequest().also { it.setContent(byteArrayOf(1, 2, 3, 4, 5)) }
        val bounded = SizeLimitedRequestWrapper(request, 4)
        assertEquals(4, bounded.inputStream.read(ByteArray(4)))
        assertThrows(RequestBodyTooLargeException::class.java) { bounded.inputStream.read() }
        assertThrows(RequestBodyTooLargeException::class.java) { bounded.inputStream.read() }
    }

    /**
     * A chunked upload (no Content-Length) is bounded by this wrapper, but the container still owns the stream:
     * its finished and ready state, its async read listener and closing it all reach the container's stream.
     */
    @Test fun boundedStreamLeavesStreamStateAndLifecycleToTheContainer() {
        var finished = false
        var ready = false
        var registeredListener: jakarta.servlet.ReadListener? = null
        var closed = false
        val container = object : jakarta.servlet.ServletInputStream() {
            override fun read(): Int = -1
            override fun isFinished(): Boolean = finished
            override fun isReady(): Boolean = ready
            override fun setReadListener(readListener: jakarta.servlet.ReadListener?) { registeredListener = readListener }
            override fun close() { closed = true }
        }
        val request = object : jakarta.servlet.http.HttpServletRequestWrapper(MockHttpServletRequest()) {
            override fun getInputStream(): jakarta.servlet.ServletInputStream = container
        }
        val stream = SizeLimitedRequestWrapper(request, 4).inputStream
        assertEquals(false to false, stream.isFinished to stream.isReady)
        finished = true
        ready = true
        assertEquals(true to true, stream.isFinished to stream.isReady)
        val listener = object : jakarta.servlet.ReadListener {
            override fun onDataAvailable() = Unit
            override fun onAllDataRead() = Unit
            override fun onError(t: Throwable) = Unit
        }
        stream.setReadListener(listener)
        assertSame(listener, registeredListener)
        stream.close()
        assertEquals(true, closed)
    }

    @Test fun readersCountBytesRatherThanCharacters() {
        val request = MockHttpServletRequest().also {
            it.characterEncoding = "UTF-8"
            it.setContent("雪雪".toByteArray(Charsets.UTF_8))
        }
        assertEquals("雪雪", SizeLimitedRequestWrapper(request, 6).reader.readText())
        val oversized = MockHttpServletRequest().also {
            it.characterEncoding = "UTF-8"
            it.setContent("雪雪".toByteArray(Charsets.UTF_8))
        }
        assertThrows(RequestBodyTooLargeException::class.java) { SizeLimitedRequestWrapper(oversized, 5).reader.readText() }
    }
}
