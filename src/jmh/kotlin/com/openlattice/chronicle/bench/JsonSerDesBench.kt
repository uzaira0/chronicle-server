package com.openlattice.chronicle.bench

import com.fasterxml.jackson.core.type.TypeReference
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
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class JsonSerDesBench {

    private lateinit var mapper: ObjectMapper
    private lateinit var fixtureJson: String
    private lateinit var deserializedEvents: List<Map<String, Any>>

    private val typeRef = object : TypeReference<List<Map<String, Any>>>() {}

    @Setup(Level.Trial)
    fun setUp() {
        mapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
            registerModule(KotlinModule.Builder().build())
        }

        // Load the fixture from the test resources on the classpath
        val stream = javaClass.classLoader.getResourceAsStream("bench-fixtures/upload-1000.json")
            ?: error("Fixture file bench-fixtures/upload-1000.json not found on classpath")
        fixtureJson = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        deserializedEvents = mapper.readValue(fixtureJson, typeRef)
    }

    @Benchmark
    fun deserialize1000Events(): List<Map<String, Any>> {
        return mapper.readValue(fixtureJson, typeRef)
    }

    @Benchmark
    fun serialize1000Events(): String {
        return mapper.writeValueAsString(deserializedEvents)
    }
}
