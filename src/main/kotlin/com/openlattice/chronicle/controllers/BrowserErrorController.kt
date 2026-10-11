package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.observability.ChronicleMetrics
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RateLimit(type = RateLimitType.SENSITIVE)
public class BrowserErrorController(mapper: ObjectMapper) {
    internal companion object {
        const val MAX_REPORT_BYTES = 2048
    }

    public enum class Kind { ERROR, UNHANDLED_REJECTION }
    public data class Report(val kind: Kind)
    private val reader = mapper.readerFor(Report::class.java)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @PostMapping("/chronicle/v3/observability/browser-errors", "/v3/observability/browser-errors", consumes = ["application/json"])
    public fun report(request: HttpServletRequest): ResponseEntity<Void> {
        val bytes = request.inputStream.readNBytes(MAX_REPORT_BYTES + 1)
        if (bytes.size > MAX_REPORT_BYTES) return ResponseEntity.status(413).build()
        val report: Report? = try { reader.readValue<Report>(bytes) }
        catch (_: JsonProcessingException) { return ResponseEntity.badRequest().build() }
        if (report == null) return ResponseEntity.badRequest().build()
        // Only a finite error kind crosses the boundary; messages, URLs and stacks are rejected.
        ChronicleMetrics.browserErrorsTotal.labels(report.kind.name).inc()
        return ResponseEntity.noContent().build()
    }
}
