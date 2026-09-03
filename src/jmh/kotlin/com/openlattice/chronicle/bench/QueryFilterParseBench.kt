package com.openlattice.chronicle.bench

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class QueryFilterParseBench {

    private lateinit var mapper: ObjectMapper
    private lateinit var filterJsons: List<String>

    @Setup(Level.Trial)
    fun setUp() {
        mapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
            registerModule(KotlinModule.Builder().build())
        }

        filterJsons = (1..50).map { i ->
            """
            {
                "studyId": "${java.util.UUID.randomUUID()}",
                "participantId": "participant-$i",
                "startDate": "${OffsetDateTime.of(2026, 1, i % 28 + 1, 0, 0, 0, 0, ZoneOffset.UTC)}",
                "endDate": "${OffsetDateTime.of(2026, 6, i % 28 + 1, 23, 59, 59, 0, ZoneOffset.UTC)}",
                "appPackageNames": ["com.example.app$i", "com.example.other"],
                "interactionTypes": ["MOVE_TO_FOREGROUND", "MOVE_TO_BACKGROUND"]
            }
            """.trimIndent()
        }
    }

    @Benchmark
    fun parseFilterBatch(): List<Map<*, *>> {
        return filterJsons.map { mapper.readValue(it, Map::class.java) }
    }

    @Benchmark
    fun parseSingleFilter(): Map<*, *> {
        return mapper.readValue(filterJsons[0], Map::class.java)
    }
}
