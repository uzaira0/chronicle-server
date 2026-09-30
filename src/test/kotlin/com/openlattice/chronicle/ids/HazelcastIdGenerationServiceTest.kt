package com.openlattice.chronicle.ids

import com.geekbeast.hazelcast.IHazelcastClientProvider
import com.hazelcast.collection.IQueue
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.mapstores.ids.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HazelcastIdGenerationServiceTest {
    @Test
    fun `producer retries failed lock acquisition without unlocking the failed attempt`() {
        val expected = UUID.randomUUID()
        val attempts = AtomicInteger()
        withService { scrolls, queue, block, available ->
            Mockito.doAnswer {
                if (attempts.getAndIncrement() == 0) throw IllegalStateException("transient lock failure")
                null
            }.`when`(scrolls).lock(0L)
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenReturn(mapOf(0L to listOf(expected)))
            Mockito.doAnswer { invocation ->
                available.put(invocation.getArgument(0))
                block.await()
            }.`when`(queue).put(any())
        }.use { fixture ->
            assertEquals(expected, fixture.available.poll(3, TimeUnit.SECONDS))
            Mockito.verify(fixture.scrolls, Mockito.times(1)).unlock(0L)
        }
    }

    @Test
    fun `producer retries an interrupted distributed queue put`() {
        val expected = UUID.randomUUID()
        val attempts = AtomicInteger()
        withService { scrolls, queue, block, available ->
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenReturn(mapOf(0L to listOf(expected)))
            Mockito.doAnswer { invocation ->
                if (attempts.getAndIncrement() == 0) throw InterruptedException("transient queue interruption")
                available.put(invocation.getArgument(0))
                block.await()
            }.`when`(queue).put(any())
        }.use { fixture ->
            assertEquals(expected, fixture.available.poll(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `producer retries after transient generation failure`() {
        val expected = UUID.randomUUID()
        val attempts = AtomicInteger()
        withService { scrolls, queue, block, available ->
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenAnswer {
                if (attempts.getAndIncrement() == 0) throw IllegalStateException("transient generation failure")
                mapOf(0L to listOf(expected))
            }
            Mockito.doAnswer { invocation ->
                available.put(invocation.getArgument(0))
                block.await()
            }.`when`(queue).put(any())
        }.use { fixture ->
            assertEquals(expected, fixture.available.poll(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `exhausted consumers return unavailable instead of waiting indefinitely`() {
        withService { scrolls, _, _, _ ->
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenThrow(IllegalStateException("generation unavailable"))
        }.use { fixture ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val outcome = executor.submit<ResponseStatusException> {
                    assertThrows(ResponseStatusException::class.java) { fixture.service.getNextId() }
                }
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, outcome.get(6, TimeUnit.SECONDS).statusCode)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `stalled distributed invocation is bounded even when server poll timeout is ignored`() {
        withService { scrolls, queue, block, _ ->
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenThrow(IllegalStateException("unavailable"))
            Mockito.`when`(queue.poll(any(), any())).thenAnswer {
                // Simulate a network future that does not observe the server-side poll timeout or interruption.
                while (block.count > 0) {
                    try { block.await() } catch (_: InterruptedException) { }
                }
                null
            }
        }.use { fixture ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<ResponseStatusException> {
                    assertThrows(ResponseStatusException::class.java) { fixture.service.getNextId() }
                }
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.get(6, TimeUnit.SECONDS).statusCode)
            } finally { fixture.block.countDown(); executor.shutdownNow() }
        }
    }

    @Test
    fun `distributed reserved IDs are skipped within the same invocation deadline`() {
        withService { scrolls, _, _, available ->
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenThrow(IllegalStateException("unavailable"))
            available.put(UUID(0, 1))
        }.use { fixture ->
            val expected = UUID.randomUUID()
            fixture.available.put(expected)
            assertEquals(expected, fixture.service.getNextId())
        }
    }

    @Test
    fun `returned and reserved IDs keep their existing allocation semantics`() {
        withService { scrolls, _, _, _ ->
            Mockito.`when`(scrolls.executeOnEntries<List<UUID>>(any())).thenThrow(IllegalStateException("generation unavailable"))
        }.use { fixture ->
            val expected = UUID.randomUUID()
            fixture.service.returnIds(listOf(UUID(0, 1), expected))
            assertEquals(expected, fixture.service.getNextId())
        }
    }

    private fun withService(configure: (IMap<Long, Range>, IQueue<UUID>, CountDownLatch, LinkedBlockingQueue<UUID>) -> Unit): Fixture {
        val clients = mock<IHazelcastClientProvider>()
        val instance = mock<HazelcastInstance>()
        val scrolls = mock<IMap<Long, Range>>()
        val queue = mock<IQueue<UUID>>()
        val available = LinkedBlockingQueue<UUID>()
        val block = CountDownLatch(1)
        Mockito.`when`(clients.getClient("IDS")).thenReturn(instance)
        Mockito.`when`(instance.getMap<Long, Range>("ID_GENERATION")).thenReturn(scrolls)
        Mockito.`when`(instance.getQueue<UUID>("ID_GENERATION")).thenReturn(queue)
        Mockito.`when`(scrolls.isEmpty).thenReturn(false)
        Mockito.`when`(queue.take()).thenAnswer { available.take() }
        Mockito.`when`(queue.poll(any(), any())).thenAnswer { invocation ->
            available.poll(invocation.getArgument(0), invocation.getArgument(1))
        }
        configure(scrolls, queue, block, available)
        return Fixture(HazelcastIdGenerationService(clients), scrolls, available, block)
    }

    private class Fixture(
        val service: HazelcastIdGenerationService,
        val scrolls: IMap<Long, Range>,
        val available: LinkedBlockingQueue<UUID>,
        val block: CountDownLatch,
    ) : AutoCloseable {
        override fun close() {
            service.javaClass.methods.firstOrNull { it.name == "shutdown" }?.invoke(service)
            block.countDown()
        }
    }
}
