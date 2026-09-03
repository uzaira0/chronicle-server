package com.openlattice.chronicle.authorization

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
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
}
