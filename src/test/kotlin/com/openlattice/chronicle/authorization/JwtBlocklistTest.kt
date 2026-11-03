package com.openlattice.chronicle.authorization

import com.openlattice.chronicle.storage.ResolvedStorage

import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.contract.JwtSessionContract
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.testing.isolatedHazelcastConfig
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
import org.testcontainers.containers.PostgreSQLContainer
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
            val config = isolatedHazelcastConfig("jwt-blocklist-test")
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
        assertEquals(issued.plus(window), blocklist.sessionIdleDeadline(jti, token, issued, absolute, window))
        val renewed = now.plusSeconds(120)
        assertNull(blocklist.credentialRejectionReason(jti, token, issued, absolute, window, { renewed }))
        assertEquals(issued.plus(window), blocklist.sessionIdleDeadline(jti, token, issued, absolute, window))
        assertTrue(blocklist.touchActivity(blocklist.sessionActivityIdentity(jti, token), issued, absolute, window,
            JwtBlocklist.ActivityTiming(renewed)))
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
    fun testRevokeAllWrittenByRestoreIsRead() {
        val timestamp = Instant.now()
        HazelcastMap.JWT_BLOCKLIST.getMap(hz).set(JwtSessionContract.revokeAllBeforeKey, timestamp.epochSecond)

        val retrieved = blocklist.getRevokeAllTimestamp()
        assertNotNull("Revoke-all timestamp should be set", retrieved)
        assertEquals(timestamp.epochSecond, retrieved!!.epochSecond)
    }

    @Test
    fun testGetRevokeAllTimestampReturnsNullWhenNotSet() {
        // Use a fresh Hazelcast instance with its own blocklist to ensure no prior state
        val freshConfig = isolatedHazelcastConfig("jwt-blocklist-fresh")
        val freshHz = Hazelcast.newHazelcastInstance(freshConfig)
        try {
            val freshBlocklist = JwtBlocklist(freshHz)
            assertNull("getRevokeAllTimestamp should return null when not set", freshBlocklist.getRevokeAllTimestamp())
        } finally {
            freshHz.shutdown()
        }
    }

    @Test
    fun testEachBlockedTokenIsBlockedIndependently() {
        val freshConfig = isolatedHazelcastConfig("jwt-blocklist-count")
        val freshHz = Hazelcast.newHazelcastInstance(freshConfig)
        try {
            val freshBlocklist = JwtBlocklist(freshHz)
            freshBlocklist.blockToken("count-test-1", Instant.now().plusSeconds(3600))
            assertTrue(freshBlocklist.isBlocked("count-test-1"))
            assertFalse(freshBlocklist.isBlocked("count-test-2"))

            freshBlocklist.blockToken("count-test-2", Instant.now().plusSeconds(3600))
            assertTrue(freshBlocklist.isBlocked("count-test-2"))
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

    @Test fun blockTokenByValueReportsWhetherTheTokenWasBlocked() {
        val live = "value-${UUID.randomUUID()}"
        assertTrue(blocklist.blockTokenByValue(live, Instant.now().plusSeconds(3600)))
        assertTrue(blocklist.isBlockedByValue(live))
        val expired = "expired-value-${UUID.randomUUID()}"
        assertFalse(blocklist.blockTokenByValue(expired, Instant.now().minusSeconds(1)))
        assertFalse(blocklist.isBlockedByValue(expired))
    }

    @Test fun privilegeCutoffRejectsATokenIssuedAtTheCutoffOrWithoutAnIssueTime() {
        val cutoff = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val subject = "boundary-${UUID.randomUUID()}"
        blocklist.revokePrincipalBefore(subject, cutoff)
        for (issued in listOf(cutoff, null)) {
            assertEquals("Token was issued before a privilege change", blocklist.credentialRejectionReason(
                null, "boundary-${UUID.randomUUID()}", issued, cutoff.plusSeconds(3600), java.time.Duration.ofMinutes(15),
                now = { cutoff.plusSeconds(1) }, subject = subject,
            ))
        }
    }

    @Test fun aSessionWithoutAnIssueTimeOrBeforeItsExpiryIsNeverActive() {
        val now = Instant.now()
        val window = java.time.Duration.ofMinutes(15)
        assertFalse(blocklist.isSessionActive("no-issue-${UUID.randomUUID()}", null, now.plusSeconds(60), window, now))
        assertFalse(blocklist.isSessionActive("no-expiry-${UUID.randomUUID()}", now, null, window, now))
        assertFalse(blocklist.isSessionActive("at-expiry-${UUID.randomUUID()}", now.minusSeconds(60), now, window, now))
    }

    @Test fun activityIsKeyedByANonBlankJtiElseByTheTokenValue() {
        assertEquals("JTI:abc", blocklist.sessionActivityIdentity("abc", "token-value"))
        assertEquals("token-value", blocklist.sessionActivityIdentity(" ", "token-value"))
        assertEquals("token-value", blocklist.sessionActivityIdentity(null, "token-value"))
    }

    @Test fun noIdleDeadlineWithoutAnIssueOrExpiryTime() {
        val window = java.time.Duration.ofMinutes(15)
        assertNull(blocklist.sessionIdleDeadline(null, "deadline-${UUID.randomUUID()}", null, Instant.now().plusSeconds(60), window))
        assertNull(blocklist.sessionIdleDeadline(null, "deadline-${UUID.randomUUID()}", Instant.now(), null, window))
    }

    @Test fun durableWritesPurgeOnlyABoundedBatchOfExpiredState() {
        val limit = JwtBlocklist.EXPIRED_STATE_PURGE_LIMIT
        ChronicleContractTestSchema.prodPostgresContainer("jwt_state_cleanup").use { pg ->
            pg.start()
            ChronicleContractTestSchema.waitForQueryReady(pg)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(pg)
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
            for ((index, role) in listOf("chronicle_app", "chronicle").withIndex()) {
                HikariDataSource().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    connectionInitSql = "SET ROLE $role"
                }.use { dataSource ->
                    val storage = mock<StorageResolver>()
                    whenever(storage.getDefaultPlatformStorage()).thenReturn(
                        ResolvedStorage(PostgresFlavor.VANILLA, RLSDataSources.wrapUncachedRequestScoped(dataSource)))
                    val durable = JwtBlocklist(hz, storage)
                    val tokenId = "cleanup-${UUID.randomUUID()}"
                    assertTrue(durable.blockToken(tokenId, Instant.now().plusSeconds(3600)))
                    assertTrue(durable.isBlocked(tokenId))
                    pg.createConnection("").use { connection ->
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

    /** `./chronicle rotate-secret dashboard` and restore write this cutoff; the running backend must honour it. */
    @Test fun selfhostSessionCutoffRefusesEarlierTokensAndAcceptsLaterOnes() {
        ChronicleContractTestSchema.prodPostgresContainer("jwt_session_cutoff").use { pg ->
            pg.start()
            ChronicleContractTestSchema.waitForQueryReady(pg)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(pg)
            HikariDataSource().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                connectionInitSql = "SET ROLE chronicle_app"
            }.use { dataSource -> assertSelfhostCutoffHonoured(pg, dataSource) }
        }
    }

    private fun assertSelfhostCutoffHonoured(pg: PostgreSQLContainer<*>, dataSource: HikariDataSource) {
        val storage = mock<StorageResolver>()
        whenever(storage.getDefaultPlatformStorage()).thenReturn(
            ResolvedStorage(PostgresFlavor.VANILLA, RLSDataSources.wrapUncachedRequestScoped(dataSource)))
        val durable = JwtBlocklist(hz, storage)
        val window = java.time.Duration.ofMinutes(15)
        val earlier = Instant.now().minusSeconds(60)
        fun rejection(issued: Instant) = durable.credentialRejectionReason(
            null, "cutoff-token-${issued.epochSecond}", issued, issued.plusSeconds(3600), window, { issued.plusSeconds(1) })
        assertNull(rejection(earlier))

        pg.createConnection("").use { connection ->
            connection.createStatement().use { it.execute(selfhostSessionCutoffSql()) }
        }

        val cutoff = requireNotNull(durable.getRevokeAllTimestamp())
        assertNotNull(rejection(earlier))
        assertNotNull(rejection(cutoff))
        assertNull(rejection(cutoff.plusSeconds(1)))
    }

    /** The exact text selfhost/chronicle_ops/dump.py hands restore.sh and rotate-secret.sh. */
    private fun selfhostSessionCutoffSql(): String {
        val selfhost = generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { it.resolve("selfhost") }
            .first { it.resolve("chronicle_ops/dump.py").isFile }
        val process = ProcessBuilder("python3", "-c",
            "from chronicle_ops.dump import SESSION_CUTOFF_SQL; print(SESSION_CUTOFF_SQL)")
            .apply { environment()["PYTHONPATH"] = selfhost.path }
            .start()
        val sql = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
        return sql
    }
}
