package com.openlattice.chronicle.services.auth

import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Rotation used to read the row without locking it and then mark it rotated unconditionally, so
 * two concurrent presentations of the same refresh token both saw rotated_at = NULL, both minted
 * a successor and both committed. One-time rotation and its theft detection were defeated.
 */
class RefreshTokenRotationRaceTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var hds: HikariDataSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_refresh_rotation")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE refresh_tokens (
                            id UUID PRIMARY KEY,
                            user_id UUID NOT NULL,
                            token_hash TEXT NOT NULL UNIQUE,
                            family_id UUID NOT NULL,
                            expires_at TIMESTAMPTZ NOT NULL,
                            rotated_at TIMESTAMPTZ,
                            revoked BOOLEAN NOT NULL DEFAULT FALSE,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            ip_address TEXT,
                            user_agent TEXT
                        )
                        """.trimIndent(),
                    )
                }
            }
            hds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
                minimumIdle = 2
            })
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            hds.close()
            postgres.stop()
        }
    }

    private lateinit var service: RefreshTokenService

    @Before
    fun before() {
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(hds)
        service = RefreshTokenService(
            storageResolver = storageResolver,
            jwtKeyMaterial = JwtKeyMaterial(
                algorithm = "HS256",
                keyId = "test",
                rsaPublicKey = null,
                rsaPrivateKey = null,
                hmacSecret = ByteArray(32) { 1 },
            ),
            issuer = "https://issuer.example",
            audience = "chronicle",
            accessTokenExpiryMinutes = 15,
            refreshTokenExpiryDays = 7,
            requireMfa = false,
        )
    }

    @Test
    fun onlyOneOfTwoConcurrentRotationsOfTheSameTokenSucceeds() {
        val userId = UUID.randomUUID().toString()
        val issued = service.createRefreshToken(userId, "192.0.2.1", "test")

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val task = Callable {
            barrier.await(10, TimeUnit.SECONDS)
            runCatching { service.rotateRefreshToken(issued.refreshToken, "192.0.2.1", "test") }
        }
        val results = try {
            pool.invokeAll(listOf(task, task)).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            "Exactly one concurrent use of a refresh token may rotate it",
            1,
            results.count { it.isSuccess },
        )
        val failure = results.first { it.isFailure }.exceptionOrNull()
        assertTrue("Expected a refresh-token failure, got $failure", failure is RefreshTokenException)

        // The loser is treated as reuse, which revokes the whole family.
        hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM refresh_tokens WHERE revoked = true"
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertTrue("Token family must be revoked on detected reuse", rs.getInt(1) > 0)
                }
            }
        }
    }

    @Test
    fun sequentialReuseOfARotatedTokenIsRejected() {
        val userId = UUID.randomUUID().toString()
        val issued = service.createRefreshToken(userId, "192.0.2.1", "test")

        service.rotateRefreshToken(issued.refreshToken, "192.0.2.1", "test")

        val failure = runCatching {
            service.rotateRefreshToken(issued.refreshToken, "192.0.2.1", "test")
        }.exceptionOrNull()
        assertTrue("Reusing a rotated token must fail, got $failure", failure is RefreshTokenException)
    }
}
