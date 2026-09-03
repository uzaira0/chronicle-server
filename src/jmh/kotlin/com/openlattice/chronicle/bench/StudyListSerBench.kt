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
open class StudyListSerBench {

    private lateinit var mapper: ObjectMapper
    private lateinit var studyList: List<Map<String, Any>>

    @Setup(Level.Trial)
    fun setUp() {
        mapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
            registerModule(KotlinModule.Builder().build())
        }

        studyList = (1..100).map { i ->
            mapOf(
                "id" to java.util.UUID.randomUUID().toString(),
                "title" to "Study $i",
                "contact" to "researcher$i@university.edu",
                "description" to "A study description for study number $i",
                "lat" to 37.4275 + (i * 0.001),
                "lon" to -122.1697 + (i * 0.001),
                "storage" to "chronicle",
                "version" to "1.0",
                "notificationsEnabled" to (i % 2 == 0),
                "organizationIds" to listOf(java.util.UUID.randomUUID().toString())
            )
        }
    }

    @Benchmark
    fun serialize100Studies(): String {
        return mapper.writeValueAsString(studyList)
    }

    @Benchmark
    fun deserialize100Studies(): List<Map<String, Any>> {
        val json = mapper.writeValueAsString(studyList)
        return mapper.readValue(json, object : TypeReference<List<Map<String, Any>>>() {})
    }
}
