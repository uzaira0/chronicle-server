package com.openlattice.chronicle.ids

import com.geekbeast.hazelcast.IHazelcastClientProvider
import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.config.QueueConfig
import com.hazelcast.config.SerializerConfig
import com.hazelcast.core.Hazelcast
import com.openlattice.chronicle.mapstores.ids.Range
import com.openlattice.chronicle.serializers.RangeStreamSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HazelcastIdNetworkDeadlineTest {
    @Test
    fun `live Hazelcast connection with stalled network cannot exceed the consumer deadline`() {
        val config = Config().apply {
            clusterName = "id-network-${UUID.randomUUID()}"
            setProperty("hazelcast.operation.thread.count", "2")
            setProperty("hazelcast.operation.generic.thread.count", "2")
            networkConfig.setPort(ServerSocket(0).use { it.localPort }).setPortAutoIncrement(false)
            networkConfig.join.multicastConfig.isEnabled = false
            addQueueConfig(QueueConfig("ID_GENERATION").setMaxSize(1))
            serializationConfig.addSerializerConfig(SerializerConfig()
                .setTypeClass(Range::class.java).setImplementation(RangeStreamSerializer()))
        }
        val member = Hazelcast.newHazelcastInstance(config)
        try {
            member.getMap<Long, Range>("ID_GENERATION").put(0, Range(0))
            PausableRelay(member.cluster.localMember.address.port).use { relay ->
                val clientConfig = ClientConfig().apply {
                    clusterName = config.clusterName
                    networkConfig.setSmartRouting(false).addAddress("127.0.0.1:${relay.port}")
                    setProperty("hazelcast.client.invocation.timeout.seconds", "120")
                    serializationConfig.addSerializerConfig(SerializerConfig()
                        .setTypeClass(Range::class.java).setImplementation(RangeStreamSerializer()))
                }
                val client = HazelcastClient.newHazelcastClient(clientConfig)
                try {
                    val provider = mock<IHazelcastClientProvider>()
                    whenever(provider.getClient("IDS")).thenReturn(client)
                    val service = HazelcastIdGenerationService(provider, true)
                    val caller = Executors.newSingleThreadExecutor()
                    try {
                        // Keep TCP alive while withholding traffic in both directions. Hazelcast's
                        // untimed invocation future would wait for the 120-second network deadline.
                        relay.pause()
                        val result = caller.submit<ResponseStatusException> {
                            assertThrows(ResponseStatusException::class.java) { service.getNextId() }
                        }
                        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.get(6, TimeUnit.SECONDS).statusCode)
                        assertEquals(true, relay.trafficBlocked.await(1, TimeUnit.SECONDS))
                    } finally {
                        relay.resume()
                        service.shutdown()
                        caller.shutdownNow()
                    }
                } finally { client.shutdown() }
            }
        } finally { member.shutdown() }
    }

    private class PausableRelay(private val memberPort: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = listener.localPort
        val trafficBlocked = CountDownLatch(1)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val threads = Executors.newCachedThreadPool { task ->
            Thread(task, "id-test-relay").apply { isDaemon = true }
        }
        @Volatile private var gate: CountDownLatch? = null

        init {
            threads.execute {
                try {
                    while (!listener.isClosed) {
                        val downstream = listener.accept().also(sockets::add)
                        val upstream = Socket("127.0.0.1", memberPort).also(sockets::add)
                        forward(downstream, upstream)
                        forward(upstream, downstream)
                    }
                } catch (_: java.io.IOException) { }
            }
        }

        fun pause() { gate = CountDownLatch(1) }
        fun resume() { gate?.countDown(); gate = null }

        private fun forward(source: Socket, destination: Socket) {
            threads.execute {
                try {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.getInputStream().read(buffer)
                        if (count < 0) break
                        gate?.let { trafficBlocked.countDown(); it.await() }
                        destination.getOutputStream().write(buffer, 0, count)
                        destination.getOutputStream().flush()
                    }
                } catch (_: java.io.IOException) {
                } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }

        override fun close() {
            resume()
            listener.close()
            sockets.forEach(Socket::close)
            threads.shutdownNow()
        }
    }
}
