package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.generated.UploadLimits
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

private val UPLOAD_EDGE_TIMEOUT: Duration = Duration.ofSeconds(60)
private const val CONCURRENT_UPLOADS = 2

/** Real app bytes -> shipped Caddy -> production servlet wire/decompression filters; no database. */
class UploadBodyLimitEdgeTest {
    @Test fun maximumAndroidBodiesPassEdgeAndServerWithoutChangingBytes() {
        val directory = checkNotNull(System.getenv("CHRONICLE_ANDROID_BATCH_DIR")) {
            "CHRONICLE_ANDROID_BATCH_DIR must contain the Android test artifacts"
        }
        val fixture = Path.of(directory)
        check(Files.isRegularFile(fixture.resolve("plaintext.body"))) { "Missing Android plaintext body" }
        val backend = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mapper = JacksonSecurityConfig().secureObjectMapper()
        val executor = Executors.newFixedThreadPool(CONCURRENT_UPLOADS)
        backend.executor = executor
        backend.createContext("/") { exchange ->
            val request = MockHttpServletRequest(exchange.requestMethod, exchange.requestURI.path)
            exchange.requestHeaders.forEach { (key, values) -> values.forEach { request.addHeader(key, it) } }
            request.setContent(exchange.requestBody.readBytes())
            val response = MockHttpServletResponse()
            val filters = uploadSizeFilters()
            filters.first().doFilter(request, response) { wireRequest, wireResponse ->
                filters.last().doFilter(wireRequest, wireResponse) { decoded, _ ->
                    val body = decoded.inputStream.readBytes()
                    check(mapper.readTree(body).isArray) { "Expected a collected upload array" }
                    response.status = 200
                    response.writer.write(digest(body))
                }
            }
            val bytes = response.contentAsByteArray
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        backend.start()
        try {
            CaddyUploadFixture(backend.address.port).use { edge ->
                edge.withFailureLog {
                    assertAcceptedBodies(edge.uri, fixture, "plaintext")
                    val oversized = ByteArray(UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt() + 1)
                    val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(edge.uri)
                        .timeout(UPLOAD_EDGE_TIMEOUT)
                        .expectContinue(true)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(oversized)).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(HttpStatus.CONTENT_TOO_LARGE.value(), response.statusCode())
                    println("Edge rejected ${oversized.size} bytes with ${response.statusCode()}")
                }
            }
        } finally {
            backend.stop(0)
            executor.shutdownNow()
        }
    }

    private fun assertAcceptedBodies(uri: URI, fixture: Path, name: String) {
        val bytes = Files.readAllBytes(fixture.resolve("$name.body"))
        check(bytes.size >= UploadLimits.MAX_REQUEST_SIZE_BYTES - UploadLimits.REQUEST_HEADER_HEADROOM_BYTES - 3) {
            "Android fixture is below the boundary"
        }
        val heap = ManagementFactory.getMemoryMXBean()
        val peak = AtomicLong(heap.heapMemoryUsage.used)
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({ peak.accumulateAndGet(heap.heapMemoryUsage.used, ::maxOf) }, 0, 1, TimeUnit.MILLISECONDS)
        val client = HttpClient.newHttpClient()
        try {
            val requests = List(CONCURRENT_UPLOADS) {
                client.sendAsync(HttpRequest.newBuilder(uri).timeout(UPLOAD_EDGE_TIMEOUT).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
            }
            requests.forEach {
                val response = it.join()
                assertEquals(name, 200, response.statusCode())
                assertEquals(name, digest(bytes), response.body())
            }
        } finally {
            sampler.shutdownNow()
        }
        println("Android $name: ${bytes.size} bytes accepted; concurrent=$CONCURRENT_UPLOADS; observed fixture JVM heap peak=${peak.get()} bytes")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

private class CaddyUploadFixture(upstreamPort: Int) : AutoCloseable {
    private val directory = uploadEdgeDirectory()
    private val logFile = directory.resolve("edge.log")
    private val name = "chronicle-as-upload-${UUID.randomUUID()}"
    private var started = false
    private val port = ServerSocket(0).use { it.localPort }
    val uri: URI = URI.create("http://127.0.0.1:$port/chronicle/v4/study/test/participant/test/android")

    init {
        try {
            Files.writeString(directory.resolve("edge.Caddyfile"), """
                import /etc/caddy/snippets.caddy
                {
                    admin off
                    auto_https off
                    order rate_limit before basic_auth
                }
                http://127.0.0.1:$port {
                    import chronicle_common
                    reverse_proxy 127.0.0.1:$upstreamPort
                }
            """.trimIndent())
            Files.copy(Path.of(checkNotNull(System.getenv("CHRONICLE_CADDY_SNIPPETS"))), directory.resolve("snippets.caddy"),
                StandardCopyOption.REPLACE_EXISTING)
            docker("run", "-d", "--name", name, "--network", "host", "--read-only", "--tmpfs", "/config",
                "--tmpfs", "/data", "-v", "$directory/edge.Caddyfile:/etc/caddy/Caddyfile:ro",
                "-v", "$directory/snippets.caddy:/etc/caddy/snippets.caddy:ro", "--entrypoint", "caddy",
                checkNotNull(System.getenv("SELFHOST_CADDY_TEST_IMAGE")), "run", "--config", "/etc/caddy/Caddyfile")
            started = true
            awaitReady()
        } catch (error: Exception) {
            try { throw failureWithLogs(error) } finally { close() }
        }
    }

    fun withFailureLog(action: () -> Unit) {
        try { action() } catch (error: Throwable) { throw failureWithLogs(error) }
    }

    private fun failureWithLogs(error: Throwable): AssertionError {
        if (started) docker("logs", name)
        return AssertionError("Caddy upload fixture failed:\n${edgeLog()}", error)
    }

    private fun awaitReady() {
        repeat(100) {
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        error("Caddy did not start")
    }

    private fun docker(vararg arguments: String) {
        val process = ProcessBuilder(listOf("docker") + arguments)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .start()
        check(process.waitFor() == 0) { "Caddy fixture command failed:\n${edgeLog()}" }
    }

    private fun edgeLog(): String = if (Files.exists(logFile)) Files.readString(logFile) else "No Caddy log was produced"

    override fun close() {
        try { if (started) docker("rm", "-f", name) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

internal fun uploadEdgeDirectory(environment: Map<String, String> = System.getenv()): Path =
    Files.createTempDirectory(Path.of(environment["TMPDIR"] ?: "build").toAbsolutePath(), "upload-edge-")
