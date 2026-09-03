package com.openlattice.chronicle.mapstores.stats

import com.geekbeast.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.SerializationConfig
import com.hazelcast.config.SerializerConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.serializers.ParticipantKeyStreamSerializer
import com.openlattice.chronicle.serializers.ParticipantStatsStreamSerializer
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

class ParticipantStatsCacheBackupReplicationTest {
    private val members = mutableListOf<HazelcastInstance>()
    private val clusterName = "participant-stats-backup-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        members.asReversed()
            .filter { it.lifecycleService.isRunning }
            .forEach { it.shutdown() }
    }

    @Test
    fun `atomic compare-and-set merge survives abrupt primary loss from its synchronous backup`() {
        val primaryMember = newMember("primary")
        val primaryAddress = primaryMember.cluster.localMember.address
        val backupMember = newMember(
            "backup",
            listOf("${primaryAddress.host}:${primaryAddress.port}"),
        )
        await("both members to join the test cluster") {
            primaryMember.cluster.members.size == 2 &&
                    backupMember.cluster.members.size == 2
        }
        await("cluster to become safe before writing") {
            members.all { it.partitionService.isClusterSafe }
        }

        val initial = TestDataFactory.participantStats()
        val distinctDate = initial.androidUniqueDates.maxOrNull()?.plusDays(1)
            ?: LocalDate.parse("2100-01-01")
        val incoming = initial.copy(
            androidUniqueDates = initial.androidUniqueDates + distinctDate,
        )
        val expected = mergeParticipantStats(initial, incoming)
        assertTrue("The update fixture must change the cached value", expected != initial)
        val key = ParticipantKey(initial.studyId, initial.participantId)
        val participantStats: IMap<ParticipantKey, ParticipantStats> =
            primaryMember.getMap(MAP_NAME)
        val cache = HazelcastParticipantStatsCache(participantStats) { false }

        participantStats[key] = initial
        cache.merge(incoming)
        await("merged value to be synchronously backed up") {
            members.all { it.partitionService.isClusterSafe }
        }

        assertEquals(expected, participantStats[key])
        val ownerUuid = checkNotNull(primaryMember.partitionService.getPartition(key).owner).uuid
        val owner = members.single { it.cluster.localMember.uuid == ownerUuid }
        val survivor = members.single { it !== owner }
        val ownerMap = owner.getMap<ParticipantKey, ParticipantStats>(MAP_NAME)
        val backupMap = survivor.getMap<ParticipantKey, ParticipantStats>(MAP_NAME)

        await("local map statistics to expose the synchronous backup") {
            ownerMap.localMapStats.ownedEntryCount >= 1 &&
                    backupMap.localMapStats.backupEntryCount >= 1
        }

        owner.lifecycleService.terminate()
        await("backup promotion after abrupt primary loss") {
            survivor.cluster.members.size == 1 && survivor.partitionService.isClusterSafe
        }
        assertEquals(expected, backupMap[key])
    }

    private fun newMember(
        name: String,
        seedMembers: List<String> = emptyList(),
    ): HazelcastInstance {
        val config = Config("participant-stats-$name-${UUID.randomUUID()}").apply {
            clusterName = this@ParticipantStatsCacheBackupReplicationTest.clusterName
            networkConfig.port = 0
            networkConfig.isPortAutoIncrement = false
            networkConfig.interfaces
                .setEnabled(true)
                .addInterface("127.0.0.1")
            networkConfig.join.apply {
                multicastConfig.isEnabled = false
                autoDetectionConfig.isEnabled = false
                tcpIpConfig
                    .setEnabled(true)
                    .setMembers(seedMembers)
            }
            addMapConfig(MapConfig(MAP_NAME).setBackupCount(1))
            serializationConfig = SerializationConfig().apply {
                add(ParticipantKeyStreamSerializer())
                add(ParticipantStatsStreamSerializer())
            }
        }
        return Hazelcast.newHazelcastInstance(config).also(members::add)
    }

    private fun SerializationConfig.add(serializer: SelfRegisteringStreamSerializer<*>) {
        addSerializerConfig(
            SerializerConfig()
                .setTypeClass(serializer.clazz)
                .setImplementation(serializer),
        )
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos()
        while (!condition()) {
            assertTrue("Timed out waiting for $description", System.nanoTime() < deadline)
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
    }

    private companion object {
        private const val MAP_NAME = "participant-stats-backup-test"
        private val WAIT_TIMEOUT = Duration.ofSeconds(15)
        private val POLL_INTERVAL = Duration.ofMillis(25)
    }
}
