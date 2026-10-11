package com.openlattice.chronicle.authorization

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.upgrades.FlywayMigrationService
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.time.Instant
import java.util.*

class JwtBlocklistTest {

    companion object {
        private lateinit var hz: HazelcastInstance
        private lateinit var blocklist: JwtBlocklist

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val config = Config()
            config.clusterName = "jwt-blocklist-test-${UUID.randomUUID()}"
            config.networkConfig.join.multicastConfig.isEnabled = false
            hz = Hazelcast.newHazelcastInstance(config)
            blocklist = JwtBlocklist(hz)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            hz.shutdown()
        }
    }

    @Test
    fun testBlockTokenWithFutureExpiryReturnsTrue() {
        val tokenId = "jti-future-${UUID.randomUUID()}"
        val expiresAt = Instant.now().plusSeconds(3600)

        val result = blocklist.blockToken(tokenId, expiresAt)

        assertTrue("blockToken should return true for future expiry", result)
        assertTrue("Token should be in blocklist", blocklist.isBlocked(tokenId))
    }

    @Test fun idleDeadlineUsesValidatedActivityAndNeverExceedsAbsoluteExpiry() {
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val jti = "idle-deadline-${UUID.randomUUID()}"
        val token = "deadline-token-${UUID.randomUUID()}"
        val issued = now.minusSeconds(60)
        val absolute = now.plusSeconds(3600)
        val window = java.time.Duration.ofMinutes(5)
        assertNull(blocklist.credentialRejectionReason(jti, token, issued, absolute, window, { now }))
        assertEquals(now.plus(window), blocklist.sessionIdleDeadline(jti, token, issued, absolute, window))
        val renewed = now.plusSeconds(120)
        assertNull(blocklist.credentialRejectionReason(jti, token, issued, absolute, window, { renewed }))
        assertEquals(renewed.plus(window), blocklist.sessionIdleDeadline(jti, token, issued, absolute, window))
        assertEquals(absolute, blocklist.sessionIdleDeadline(jti, token, issued, absolute, java.time.Duration.ofHours(2)))
    }

    @Test
    fun testBlockTokenWithPastExpiryReturnsFalse() {
        val tokenId = "jti-past-${UUID.randomUUID()}"
        val expiresAt = Instant.now().minusSeconds(3600)

        val result = blocklist.blockToken(tokenId, expiresAt)

        assertFalse("blockToken should return false for past expiry", result)
        assertFalse("Expired token should not be in blocklist", blocklist.isBlocked(tokenId))
    }

    @Test
    fun testIsBlockedReturnsTrueForBlockedToken() {
        val tokenId = "jti-blocked-${UUID.randomUUID()}"
        blocklist.blockToken(tokenId, Instant.now().plusSeconds(3600))

        assertTrue(blocklist.isBlocked(tokenId))
    }

    @Test
    fun testIsBlockedReturnsFalseForUnblockedToken() {
        assertFalse(blocklist.isBlocked("jti-nonexistent-${UUID.randomUUID()}"))
    }

    @Test
    fun testBlockTokenByValueUsesSha256() {
        val tokenValue = "eyJhbGciOiJIUzI1NiJ9.test-token-${UUID.randomUUID()}"
        val expiresAt = Instant.now().plusSeconds(3600)

        blocklist.blockTokenByValue(tokenValue, expiresAt)

        // Compute expected SHA-256 hash
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(tokenValue.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertTrue("Token should be blocked by its SHA-256 hash", blocklist.isBlocked(expectedHash))
        assertTrue("isBlockedByValue should find the token", blocklist.isBlockedByValue(tokenValue))
    }

    @Test
    fun testIsBlockedByValueReturnsFalseForUnblockedToken() {
        assertFalse(blocklist.isBlockedByValue("not-blocked-token-${UUID.randomUUID()}"))
    }

    @Test
    fun testRevokeAllBeforeSetsTimestamp() {
        val timestamp = Instant.now()
        blocklist.revokeAllBefore(timestamp)

        val retrieved = blocklist.getRevokeAllTimestamp()
        assertNotNull("Revoke-all timestamp should be set", retrieved)
        assertEquals(timestamp.epochSecond, retrieved!!.epochSecond)
    }

    @Test
    fun testGetRevokeAllTimestampReturnsNullWhenNotSet() {
        // Use a fresh Hazelcast instance with its own blocklist to ensure no prior state
        val freshConfig = Config()
        freshConfig.clusterName = "jwt-blocklist-fresh-${UUID.randomUUID()}"
        freshConfig.networkConfig.join.multicastConfig.isEnabled = false
        val freshHz = Hazelcast.newHazelcastInstance(freshConfig)
        try {
            val freshBlocklist = JwtBlocklist(freshHz)
            assertNull("getRevokeAllTimestamp should return null when not set", freshBlocklist.getRevokeAllTimestamp())
        } finally {
            freshHz.shutdown()
        }
    }

    @Test
    fun testGetBlockedCountIncrementsAfterBlocking() {
        val freshConfig = Config()
        freshConfig.clusterName = "jwt-blocklist-count-${UUID.randomUUID()}"
        freshConfig.networkConfig.join.multicastConfig.isEnabled = false
        val freshHz = Hazelcast.newHazelcastInstance(freshConfig)
        try {
            val freshBlocklist = JwtBlocklist(freshHz)
            assertEquals(0L, freshBlocklist.getBlockedCount())

            freshBlocklist.blockToken("count-test-1", Instant.now().plusSeconds(3600))
            assertEquals(1L, freshBlocklist.getBlockedCount())

            freshBlocklist.blockToken("count-test-2", Instant.now().plusSeconds(3600))
            assertEquals(2L, freshBlocklist.getBlockedCount())
        } finally {
            freshHz.shutdown()
        }
    }

    @Test fun privilegeCutoffRejectsTheAffectedSubjectAndPreservesOthers() {
        val cutoff = Instant.now()
        val subject = "changed-${UUID.randomUUID()}"
        blocklist.revokePrincipalBefore(subject, cutoff)
        val issued = cutoff.minusSeconds(10)
        val expiry = cutoff.plusSeconds(3600)
        assertNotNull(blocklist.credentialRejectionReason(UUID.randomUUID().toString(), "changed-token", issued, expiry,
            java.time.Duration.ofMinutes(15), subject = subject))
        assertNull(blocklist.credentialRejectionReason(UUID.randomUUID().toString(), "unrelated-token", cutoff.plusSeconds(1), expiry,
            java.time.Duration.ofMinutes(15), subject = "unrelated"))
    }

    @Test fun durableWritesPurgeOnlyABoundedBatchOfExpiredState() {
        val limit = JwtBlocklist.EXPIRED_STATE_PURGE_LIMIT
        ChronicleContractTestSchema.prodPostgresContainer("jwt_state_cleanup").use { pg ->
            pg.start()
            ChronicleContractTestSchema.waitForQueryReady(pg)
            pg.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)
            assertTrue(FlywayMigrationService.baseConfiguration().dataSource(pg.jdbcUrl, pg.username, pg.password)
                .target("122").load().migrate().success)
            pg.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""
                        INSERT INTO jwt_session_state (state_group, state_key, state_value, expires_at)
                        SELECT 'expired-' || (n % 2), n::text, 1, now() - interval '1 day'
                        FROM generate_series(1, ${limit * 2}) n
                    """.trimIndent())
                    statement.executeUpdate("""
                        INSERT INTO jwt_session_state (state_group, state_key, state_value, expires_at) VALUES
                            ('retained', 'unexpired', 1, now() + interval '1 day'),
                            ('retained', 'permanent', 1, NULL)
                    """.trimIndent())
                }
            }
            assertTrue(ChronicleContractTestSchema.migrate(pg).success)
            for ((index, role) in listOf("chronicle_app", "chronicle").withIndex()) {
                HikariDataSource().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    connectionInitSql = "SET ROLE $role"
                }.use { dataSource ->
                    val storage = mock<StorageResolver>()
                    whenever(storage.getDefaultPlatformStorage()).thenReturn(PostgresFlavor.VANILLA to dataSource)
                    val durable = JwtBlocklist(hz, storage)
                    val tokenId = "cleanup-${UUID.randomUUID()}"
                    assertTrue(durable.blockToken(tokenId, Instant.now().plusSeconds(3600)))
                    assertTrue(durable.isBlocked(tokenId))
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("""
                                SELECT count(*) FILTER (WHERE expires_at <= now()),
                                    count(*) FILTER (WHERE expires_at > now()),
                                    count(*) FILTER (WHERE expires_at IS NULL)
                                FROM jwt_session_state
                            """.trimIndent()).use { rows ->
                                assertTrue(rows.next())
                                assertEquals(limit * (1 - index), rows.getInt(1))
                                assertEquals(index + 2, rows.getInt(2))
                                assertEquals(1, rows.getInt(3))
                            }
                        }
                    }
                }
            }
        }
    }

}
